import { requireMcpAuth } from '@better-auth/mcp';
import {
  classifyInboundRequest,
  createMcpHandler,
  isJsonContentType,
  type AuthInfo,
  type InboundHttpRequest,
  type McpHttpHandler,
} from '@modelcontextprotocol/server';
import { randomUUID } from 'node:crypto';
import type { AccessRepository } from './access/repository.ts';
import type { DirtAuth } from './auth.ts';
import type { BridgeClient } from './bridge/client.ts';
import { BRIDGE_ROUTES, BridgeCapabilitiesSchema } from './bridge/contract.ts';
import type { RuntimeConfig } from './config.ts';
import type { DirtLogger } from './logging.ts';
import { createDirtServer } from './server.ts';
import { toolConfigurationFromCapabilities } from './tools/configuration.ts';

const MAX_MCP_BODY_BYTES = 4 * 1_024 * 1_024;
const REQUIRED_SCOPE = 'dirt:mcp';

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
  const sdkHandler: McpHttpHandler = createMcpHandler(
    async () => {
      const capabilities = await bridge.request(BRIDGE_ROUTES.capabilities, randomUUID(), BridgeCapabilitiesSchema);
      return createDirtServer(bridge, toolConfigurationFromCapabilities(capabilities), logger);
    },
    {
      legacy: 'reject',
      responseMode: 'auto',
      onerror(error) {
        logger.child({ component: 'mcp_http' }).error('mcp.transport_error', 'MCP transport error.', {
          error_type: error.name,
        });
      },
    },
  );

  const authenticated = requireMcpAuth(
    auth,
    async (request, claims) => {
      const userId = typeof claims.sub === 'string' ? claims.sub : undefined;
      const clientId = typeof claims.azp === 'string' ? claims.azp : undefined;
      const authorizationVersion = claims.dirt_auth_version;
      const scopes = parseScopes(claims.scope);
      if (userId === undefined || !isAuthorizationVersion(authorizationVersion)) {
        return mcpAuthorizationError(401, 'The access token is not a valid Dirt authorization.', resource);
      }
      const user = repository.findMcpUser(userId, authorizationVersion);
      if (user === null) {
        return jsonRpcError(403, -32_000, 'The Dirt account must be active and linked to a Minecraft account.');
      }
      if (clientId === undefined) {
        return mcpAuthorizationError(401, 'The access token does not identify its OAuth client.', resource);
      }
      const accessToken = extractAccessToken(request.headers.get('Authorization'));
      if (accessToken === null) return mcpAuthorizationError(401, 'Authentication is required.', resource);
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
      const inbound: InboundHttpRequest = {
        httpMethod: request.method,
        body: parsedBody,
      };
      const protocolVersionHeader = request.headers.get('MCP-Protocol-Version');
      const mcpMethodHeader = request.headers.get('Mcp-Method');
      const mcpNameHeader = request.headers.get('Mcp-Name');
      if (protocolVersionHeader !== null) inbound.protocolVersionHeader = protocolVersionHeader;
      if (mcpMethodHeader !== null) inbound.mcpMethodHeader = mcpMethodHeader;
      if (mcpNameHeader !== null) inbound.mcpNameHeader = mcpNameHeader;
      const classification = classifyInboundRequest(inbound);
      if (classification.kind === 'modern' && request.headers.get('MCP-Protocol-Version') === null) {
        return jsonRpcError(400, -32_600, 'MCP-Protocol-Version is required for 2026-07-28 requests.');
      }
      const authInfo: AuthInfo = {
        token: accessToken,
        clientId,
        scopes,
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
  if (typeof value === 'string') return value.split(' ').filter((scope) => scope.length > 0);
  return Array.isArray(value) ? value.filter((scope): scope is string => typeof scope === 'string') : [];
}

export function extractAccessToken(value: string | null): string | null {
  if (value === null) return null;
  const match = /^(?:Bearer|DPoP)[\t ]+(\S+)$/iu.exec(value);
  return match?.[1] ?? null;
}

function mcpAuthorizationError(status: 401 | 403, message: string, resource: string): Response {
  const metadata = new URL('/.well-known/oauth-protected-resource/mcp', resource).toString();
  const error = status === 401 ? 'invalid_token' : 'insufficient_scope';
  return new Response(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32_000, message } }), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'WWW-Authenticate': `Bearer error="${error}", resource_metadata="${metadata}", scope="${REQUIRED_SCOPE}"`,
    },
  });
}

function jsonRpcError(status: number, code: number, message: string): Response {
  return new Response(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code, message } }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

class BodyTooLargeError extends Error {}

async function readBoundedText(request: Request, maximumBytes: number): Promise<string> {
  const contentLength = request.headers.get('Content-Length');
  if (contentLength !== null && /^\d+$/u.test(contentLength) && Number(contentLength) > maximumBytes) {
    await cancelRequestBody(request);
    throw new BodyTooLargeError();
  }
  if (request.body === null) return '';
  let output = new Uint8Array(Math.min(maximumBytes, 8_192));
  let size = 0;
  try {
    await request.body.pipeTo(
      new WritableStream<Uint8Array>({
        write(chunk) {
          const nextSize = size + chunk.byteLength;
          if (nextSize > maximumBytes) throw new BodyTooLargeError();
          if (nextSize > output.byteLength) {
            const grown = new Uint8Array(Math.min(maximumBytes, Math.max(nextSize, output.byteLength * 2)));
            grown.set(output.subarray(0, size));
            output = grown;
          }
          output.set(chunk, size);
          size = nextSize;
        },
      }),
    );
  } catch (error: unknown) {
    if (error instanceof BodyTooLargeError) await cancelRequestBody(request);
    throw error;
  }
  return new TextDecoder('utf-8', { fatal: true }).decode(output.subarray(0, size));
}

async function cancelRequestBody(request: Request): Promise<void> {
  try {
    await request.body?.cancel();
  } catch {
    // The stream may already have been canceled by pipeTo.
  }
}
