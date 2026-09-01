import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { serve } from '@hono/node-server';
import { getMigrations } from 'better-auth/db/migration';
import { AccessRepository } from '../dist/access/repository.js';
import { createAuth, createAuthOptions } from '../dist/auth.js';
import { BridgeClient } from '../dist/bridge/client.js';
import type { RuntimeConfig } from '../dist/config.js';
import type { DirtLogger } from '../dist/logging.js';
import { createDirtMcpHandler } from '../dist/mcp-http.js';
import { openDatabase } from '../dist/storage.js';
import { createWebApp } from '../dist/web/app.js';

const unavailableFetch = (): Response => new Response(null, { status: 503 });

test('signed MCP tokens verify through loopback JWKS and account state stays authoritative', async () => {
  let activeFetch: (request: Request) => Response | Promise<Response> = unavailableFetch;
  const httpServer = serve({
    fetch: (request) => activeFetch(request),
    hostname: '127.0.0.1',
    port: 0,
  });
  await once(httpServer, 'listening');
  const address = httpServer.address();
  assert.ok(address !== null && typeof address === 'object');

  const directory = mkdtempSync(join(tmpdir(), 'dirt-mcp-auth-test-'));
  const databasePath = join(directory, 'dirt.sqlite');
  const config: RuntimeConfig = {
    authSecret: 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
    bridge: {
      origin: 'http://127.0.0.1:8765',
      token: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    },
    controlToken: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
    databasePath,
    port: address.port,
    publicOrigin: 'https://dirt.example',
  };
  const database = openDatabase(databasePath);
  let mcp: ReturnType<typeof createDirtMcpHandler> | undefined;

  try {
    const repository = new AccessRepository(database);
    const freshSchema = await getMigrations(createAuthOptions(config, database, repository));
    await freshSchema.runMigrations();
    const auth = createAuth(config, database, repository);
    await auth.$context;

    const now = new Date().toISOString();
    database
      .prepare(
        'INSERT INTO "user" (id, name, email, emailVerified, image, createdAt, updatedAt, status, minecraftUuid, authorizationVersion) VALUES (?, ?, ?, 0, NULL, ?, ?, ?, ?, 0)',
      )
      .run(
        'linked-user',
        'LinkedPlayer',
        'linked-user@dirt.placeholder.invalid',
        now,
        now,
        'active',
        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      );

    let bridgeRequests = 0;
    const bridge = new BridgeClient(config.bridge, async () => {
      bridgeRequests += 1;
      return Response.json({ operations: [] });
    });
    mcp = createDirtMcpHandler(auth, bridge, repository, config, silentLogger);
    const app = createWebApp({
      auth,
      bridge,
      config,
      logger: silentLogger,
      mcp,
      repository,
      isLoopback: () => true,
    });
    let jwksRequests = 0;
    let jwksHost: string | null = null;
    activeFetch = (request) => {
      if (new URL(request.url).pathname === '/api/auth/jwks') {
        jwksRequests += 1;
        jwksHost = request.headers.get('Host');
      }
      return app.fetch(request);
    };

    const resource = `${config.publicOrigin}/mcp`;
    const publicHeaders = { Host: new URL(config.publicOrigin).host };
    const protectedMetadataResponse = await app.request('/.well-known/oauth-protected-resource/mcp', {
      headers: publicHeaders,
    });
    assert.equal(protectedMetadataResponse.status, 200);
    const protectedMetadata = (await protectedMetadataResponse.json()) as {
      resource?: string;
      authorization_servers?: string[];
      scopes_supported?: string[];
      dpop_signing_alg_values_supported?: string[];
    };
    assert.equal(protectedMetadata.resource, resource);
    assert.deepEqual(protectedMetadata.authorization_servers, [`${config.publicOrigin}/api/auth`]);
    assert.deepEqual(protectedMetadata.scopes_supported, ['dirt:mcp']);
    assert.ok((protectedMetadata.dpop_signing_alg_values_supported?.length ?? 0) > 0);

    const authorizationMetadataResponse = await app.request('/.well-known/oauth-authorization-server/api/auth', {
      headers: publicHeaders,
    });
    assert.equal(authorizationMetadataResponse.status, 200);
    const authorizationMetadata = (await authorizationMetadataResponse.json()) as {
      issuer?: string;
      grant_types_supported?: string[];
      code_challenge_methods_supported?: string[];
      client_id_metadata_document_supported?: boolean;
      registration_endpoint?: string;
    };
    assert.equal(authorizationMetadata.issuer, `${config.publicOrigin}/api/auth`);
    assert.deepEqual(authorizationMetadata.grant_types_supported, ['authorization_code', 'refresh_token']);
    assert.deepEqual(authorizationMetadata.code_challenge_methods_supported, ['S256']);
    assert.equal(authorizationMetadata.client_id_metadata_document_supported, true);
    assert.equal(authorizationMetadata.registration_endpoint, undefined);

    assert.ok(hasJwtSigner(auth.api));
    const jwtSigner: JwtSigner = auth.api;
    const issued = await jwtSigner.signJWT({
      body: {
        payload: {
          sub: 'linked-user',
          aud: resource,
          azp: 'https://client.example/client.json',
          scope: 'dirt:mcp',
          dirt_auth_version: 0,
        },
        overrideOptions: {
          jwt: {
            issuer: `${config.publicOrigin}/api/auth`,
            audience: resource,
            expirationTime: '5m',
          },
        },
      },
    });

    const malformedTokens = await Promise.all(
      [
        {
          sub: 'linked-user',
          aud: resource,
          azp: 'https://client.example/client.json',
          scope: 'dirt:mcp',
        },
        {
          aud: resource,
          azp: 'https://client.example/client.json',
          scope: 'dirt:mcp',
          dirt_auth_version: 0,
        },
      ].map((payload) =>
        jwtSigner.signJWT({
          body: {
            payload,
            overrideOptions: {
              jwt: {
                issuer: `${config.publicOrigin}/api/auth`,
                audience: resource,
                expirationTime: '5m',
              },
            },
          },
        }),
      ),
    );
    const rejectedMalformedTokens = [
      await mcp.fetch(mcpRequest(resource, malformedTokens[0]!.token)),
      await mcp.fetch(mcpRequest(resource, malformedTokens[1]!.token)),
    ];
    for (const rejected of rejectedMalformedTokens) {
      assert.equal(rejected.status, 401);
      assert.match(rejected.headers.get('WWW-Authenticate') ?? '', /error="invalid_token"/u);
    }
    assert.equal(bridgeRequests, 0);

    const authorized = await mcp.fetch(mcpRequest(resource, issued.token));
    assert.equal(authorized.status, 200);
    const result = (await authorized.json()) as { result?: { tools?: unknown[]; resultType?: string } };
    assert.deepEqual(result.result?.tools, []);
    assert.equal(result.result?.resultType, 'complete');
    assert.equal(jwksRequests, 1);
    assert.equal(jwksHost, `127.0.0.1:${config.port}`);
    assert.equal(bridgeRequests, 1);

    let contentLengthBodyCanceled = false;
    const contentLengthBody = new ReadableStream<Uint8Array>({
      cancel() {
        contentLengthBodyCanceled = true;
      },
    });
    const rejectedContentLength = await mcp.fetch(
      mcpStreamingRequest(resource, issued.token, contentLengthBody, {
        'Content-Length': String(4 * 1_024 * 1_024 + 1),
      }),
    );
    assert.equal(rejectedContentLength.status, 413);
    assert.equal(contentLengthBodyCanceled, true);
    assert.equal(bridgeRequests, 1);

    let streamedBodyCanceled = false;
    const streamedBody = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array(4 * 1_024 * 1_024));
        controller.enqueue(new Uint8Array(1));
      },
      cancel() {
        streamedBodyCanceled = true;
      },
    });
    const rejectedStream = await mcp.fetch(mcpStreamingRequest(resource, issued.token, streamedBody));
    assert.equal(rejectedStream.status, 413);
    assert.equal(streamedBodyCanceled, true);
    assert.equal(bridgeRequests, 1);

    repository.disableUser('LinkedPlayer');
    const disabled = await mcp.fetch(mcpRequest(resource, issued.token));
    assert.equal(disabled.status, 403);
    assert.equal(disabled.headers.get('WWW-Authenticate'), null);
    assert.equal(bridgeRequests, 1);

    repository.enableUser('LinkedPlayer');
    const superseded = await mcp.fetch(mcpRequest(resource, issued.token));
    assert.equal(superseded.status, 403);
    assert.equal(bridgeRequests, 1);

    const current = await jwtSigner.signJWT({
      body: {
        payload: {
          sub: 'linked-user',
          aud: resource,
          azp: 'https://client.example/client.json',
          scope: 'dirt:mcp',
          dirt_auth_version: 2,
        },
        overrideOptions: {
          jwt: {
            issuer: `${config.publicOrigin}/api/auth`,
            audience: resource,
            expirationTime: '5m',
          },
        },
      },
    });
    const reauthorized = await mcp.fetch(mcpRequest(resource, current.token));
    assert.equal(reauthorized.status, 200);
    assert.equal(bridgeRequests, 2);

    const legacyInitialize = await mcp.fetch(legacyInitializeRequest(resource, current.token));
    assert.equal(legacyInitialize.status, 200);
    assert.match(await legacyInitialize.text(), /"protocolVersion":"2025-06-18"/u);

    const legacyInitialized = await mcp.fetch(
      legacyRequest(resource, current.token, {
        jsonrpc: '2.0',
        method: 'notifications/initialized',
      }),
    );
    assert.equal(legacyInitialized.status, 202);

    const legacyTools = await mcp.fetch(
      legacyRequest(resource, current.token, { jsonrpc: '2.0', id: 2, method: 'tools/list' }),
    );
    assert.equal(legacyTools.status, 200);
    assert.match(await legacyTools.text(), /"tools":\[\]/u);
    const bridgeRequestsAfterLegacyHandshake = bridgeRequests;
    assert.ok(bridgeRequestsAfterLegacyHandshake > 2);

    repository.unlinkUser('LinkedPlayer');
    const supersededByUnlink = await mcp.fetch(mcpRequest(resource, current.token));
    assert.equal(supersededByUnlink.status, 403);
    assert.equal(bridgeRequests, bridgeRequestsAfterLegacyHandshake);
  } finally {
    if (mcp !== undefined) await mcp.close();
    await new Promise<void>((resolve, reject) => {
      httpServer.close((error) => (error === undefined ? resolve() : reject(error)));
    });
    database.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

function mcpRequest(resource: string, token: string): Request {
  return new Request(resource, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'MCP-Protocol-Version': '2026-07-28',
      'Mcp-Method': 'tools/list',
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method: 'tools/list',
      params: {
        _meta: {
          'io.modelcontextprotocol/protocolVersion': '2026-07-28',
          'io.modelcontextprotocol/clientInfo': { name: 'Dirt test client', version: '1' },
          'io.modelcontextprotocol/clientCapabilities': {},
        },
      },
    }),
  });
}

function legacyInitializeRequest(resource: string, token: string): Request {
  return legacyRequest(resource, token, {
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'Dirt legacy compatibility test', version: '1' },
    },
  });
}

function legacyRequest(resource: string, token: string, body: object): Request {
  return new Request(resource, {
    method: 'POST',
    headers: {
      Accept: 'application/json, text/event-stream',
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'MCP-Protocol-Version': '2025-06-18',
    },
    body: JSON.stringify(body),
  });
}

function mcpStreamingRequest(
  resource: string,
  token: string,
  body: ReadableStream<Uint8Array>,
  additionalHeaders: Readonly<Record<string, string>> = {},
): Request {
  const init: RequestInit & { readonly duplex: 'half' } = {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'MCP-Protocol-Version': '2026-07-28',
      'Mcp-Method': 'tools/list',
      ...additionalHeaders,
    },
    body,
    duplex: 'half',
  };
  return new Request(resource, init);
}

interface JwtSigner {
  signJWT(input: {
    body: {
      payload: Record<string, unknown>;
      overrideOptions: { jwt: { issuer: string; audience: string; expirationTime: string } };
    };
  }): Promise<{ token: string }>;
}

function hasJwtSigner(value: object): value is object & JwtSigner {
  return 'signJWT' in value && typeof value.signJWT === 'function';
}

const silentLogger: DirtLogger = {
  child() {
    return this;
  },
  info() {},
  warning() {},
  error() {},
};
