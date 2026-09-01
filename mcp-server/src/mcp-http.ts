import { requireMcpAuth } from '@better-auth/mcp';
import { createMcpHandler, isJsonContentType, type AuthInfo, type McpHttpHandler } from '@modelcontextprotocol/server';
import { randomUUID } from 'node:crypto';
import type { AccessRepository } from './access/repository.ts';
import type { DirtAuth } from './auth.ts';
import type { BridgeClient } from './bridge/client.ts';
import { BRIDGE_ROUTES, BridgeCapabilitiesSchema } from './bridge/contract.ts';
import type { RuntimeConfig } from './config.ts';
import { BodyTooLargeError, readBoundedText } from './http-body.ts';
import { safeErrorFields, type DirtLogger } from './logging.ts';
import { createDirtServer } from './server.ts';
import { toolConfigurationFromCapabilities } from './tools/configuration.ts';

const MAX_MCP_BODY_BYTES = 4 * 1_024 * 1_024;
const REQUIRED_SCOPE = 'dirt:mcp';
const ACCESS_TOKEN_PATTERN = /^(?:Bearer|DPoP) +([-A-Za-z0-9._~+/]+=*)$/iu;

export interface DirtMcpHandler {
  readonly fetch: (request: Request) => Promise<Response>;
  readonly close: () => Promise<void>;
}

export function createDirtMcpHandler(
  auth: DirtAuth,
  bridge: BridgeClient,
  repository: AccessRepository,
  config: Pick<RuntimeConfig, 'port' | 'publicOrigin'>,
  logger: DirtLogger,
): DirtMcpHandler {
  const resource = `${config.publicOrigin}/mcp`;
  const httpLogger = logger.child({ component: 'mcp_http' });
  const sdkHandler: McpHttpHandler = createMcpHandler(
    async () => {
      const capabilities = await bridge.request(BRIDGE_ROUTES.capabilities, randomUUID(), BridgeCapabilitiesSchema);
      return createDirtServer(bridge, toolConfigurationFromCapabilities(capabilities), logger);
    },
    {
      // Keep 2025-06-18 compatibility alongside 2026-07-28.
      legacy: 'stateless',
      onerror(error) {
        httpLogger.error('mcp.transport_error', 'MCP transport error.', safeErrorFields(error));
      },
    },
  );

  const authenticated = requireMcpAuth(
    auth,
    async (request, claims) => {
      const authorizationVersion = claims.dirt_auth_version;
      if (typeof claims.sub !== 'string' || !isAuthorizationVersion(authorizationVersion)) {
        return mcpAuthorizationError('The access token is not a valid Dirt authorization.', resource);
      }
      const user = repository.findMcpUser(claims.sub, authorizationVersion);
      if (user === null) {
        return jsonRpcError(403, -32_000, 'The Dirt account must be active and linked to a Minecraft account.');
      }
      if (typeof claims.azp !== 'string') {
        return mcpAuthorizationError('The access token does not identify its OAuth client.', resource);
      }
      const accessToken = extractAccessToken(request.headers.get('Authorization'));
      if (accessToken === null) return mcpAuthorizationError('Authentication is required.', resource);
      if (request.method !== 'POST') {
        return new Response('Method Not Allowed', { status: 405, headers: { Allow: 'POST' } });
      }
      if (!isJsonContentType(request.headers.get('Content-Type'))) {
        return new Response('Content-Type must be application/json.', { status: 415 });
      }
      let parsedBody: unknown;
      try {
        parsedBody = JSON.parse(await readBoundedText(request, MAX_MCP_BODY_BYTES));
      } catch (error: unknown) {
        if (error instanceof BodyTooLargeError) {
          return jsonRpcError(413, -32_600, 'Request body is too large.');
        }
        return jsonRpcError(400, -32_700, 'Parse error.');
      }
      const authInfo: AuthInfo = {
        token: accessToken,
        clientId: claims.azp,
        scopes: parseScopes(claims.scope),
        ...(typeof claims.exp === 'number' ? { expiresAt: claims.exp } : {}),
        resource: new URL(resource),
        extra: { userId: user.id, minecraftUuid: user.minecraftUuid },
      };
      return sdkHandler.fetch(request, { authInfo, parsedBody });
    },
    {
      resource,
      requiredScopes: [REQUIRED_SCOPE],
      challengeScopes: [REQUIRED_SCOPE],
      // Token issuer and audience stay public. Only public-key retrieval uses
      // loopback so production does not depend on public-DNS/NAT hairpinning.
      jwksUrl: `http://127.0.0.1:${config.port}/api/auth/jwks`,
    },
  );

  return { fetch: authenticated, close: () => sdkHandler.close() };
}

function isAuthorizationVersion(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function parseScopes(value: unknown): string[] {
  return typeof value === 'string' ? value.split(' ').filter((scope) => scope.length > 0) : [];
}

export function extractAccessToken(value: string | null): string | null {
  if (value === null) return null;
  const match = ACCESS_TOKEN_PATTERN.exec(value);
  return match?.[1] ?? null;
}

function mcpAuthorizationError(message: string, resource: string): Response {
  const metadata = new URL('/.well-known/oauth-protected-resource/mcp', resource).toString();
  const response = jsonRpcError(401, -32_000, message);
  response.headers.set(
    'WWW-Authenticate',
    `Bearer error="invalid_token", resource_metadata="${metadata}", scope="${REQUIRED_SCOPE}"`,
  );
  return response;
}

function jsonRpcError(status: number, code: number, message: string): Response {
  return new Response(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code, message } }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
