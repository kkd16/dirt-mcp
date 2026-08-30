import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';
import { getMigrations } from 'better-auth/db/migration';
import { AccessError, AccessRepository } from '../dist/access/repository.js';
import { createAuth, normalizeHandle } from '../dist/auth.js';
import type { RuntimeConfig } from '../dist/config.js';
import type { DirtLogger } from '../dist/logging.js';
import { extractAccessToken } from '../dist/mcp-http.js';
import { openDatabase } from '../dist/storage.js';
import { createWebApp } from '../dist/web/app.js';

const directory = mkdtempSync(join(tmpdir(), 'dirt-web-test-'));
const config: RuntimeConfig = {
  authSecret: 'auth-secret-auth-secret-auth-secret-auth-secret',
  bridge: {
    origin: 'http://127.0.0.1:8765',
    token: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  },
  controlToken: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
  databasePath: join(directory, 'dirt.sqlite'),
  port: 3000,
  publicOrigin: 'http://127.0.0.1:3000',
  rpId: '127.0.0.1',
};
const database = openDatabase(config.databasePath);
const repository = new AccessRepository(database);
const auth = createAuth(config, database, repository);
const migration = await getMigrations(auth.options, { throwOnUnsafe: false });
assert.deepEqual(migration.unsafeChanges, []);
await migration.runMigrations();
repository.assertSchema();
insertUser('protected-user', 'protected', new Date('2026-08-30T11:00:00.000Z'));

after(() => {
  database.close();
  rmSync(directory, { recursive: true, force: true });
});

test('Better Auth is passkey-only and MCP OAuth uses the fixed narrow policy', async () => {
  assert.equal(auth.options.emailAndPassword?.enabled, false);
  assert.equal(auth.options.telemetry?.enabled, false);
  assert.equal(auth.options.logger?.disabled, true);
  assert.equal(auth.options.advanced?.defaultCookieAttributes?.sameSite, 'strict');
  assert.deepEqual(auth.options.advanced?.ipAddress?.ipAddressHeaders, ['x-forwarded-for']);
  assert.deepEqual(auth.options.advanced?.ipAddress?.trustedProxies, ['127.0.0.1/32', '::1/128']);
  assert.deepEqual(auth.options.disabledPaths, ['/passkey/delete-passkey', '/token']);
  const userFields = auth.options.user?.additionalFields ?? {};
  for (const field of ['handle', 'status', 'minecraftUuid', 'minecraftName']) {
    assert.equal(userFields[field]?.input, false);
  }

  const plugins = auth.options.plugins ?? [];
  const jwt = plugins.find((plugin: { id: string }) => plugin.id === 'jwt') as
    | { options?: Record<string, unknown> }
    | undefined;
  assert.equal(jwt?.options?.disableSettingJwtHeader, true);
  const oauth = plugins.find((plugin: { id: string }) => plugin.id === 'oauth-provider') as
    | { options?: Record<string, unknown> }
    | undefined;
  assert.ok(oauth !== undefined);
  assert.deepEqual(oauth.options?.scopes, ['dirt:mcp', 'offline_access']);
  assert.deepEqual(oauth.options?.grantTypes, ['authorization_code', 'refresh_token']);
  assert.equal(oauth.options?.accessTokenExpiresIn, 300);
  assert.equal(oauth.options?.codeExpiresIn, 300);
  assert.equal(oauth.options?.refreshTokenExpiresIn, 2_592_000);
  assert.equal(oauth.options?.allowDynamicClientRegistration, false);
  assert.equal(oauth.options?.allowUnauthenticatedClientRegistration, false);
  assert.equal(oauth.options?.clientRegistrationRequirePKCE, true);
  assert.equal(typeof oauth.options?.clientPrivileges, 'function');
  assert.equal(typeof oauth.options?.resourcePrivileges, 'function');

  const passkey = plugins.find((plugin: { id: string }) => plugin.id === 'passkey') as
    | { options?: Record<string, unknown> }
    | undefined;
  assert.ok(passkey !== undefined);
  const selection = passkey.options?.authenticatorSelection as Record<string, unknown>;
  assert.equal(selection.userVerification, 'required');
  assert.equal(selection.residentKey, 'required');
  const registration = passkey.options?.registration as Record<string, unknown>;
  assert.equal(registration.requireSession, false);
  const afterVerification = registration.afterVerification as (input: unknown) => Promise<unknown>;
  await assert.rejects(() =>
    afterVerification({
      ctx: { headers: new Headers() },
      verification: { registrationInfo: { userVerified: true } },
    }),
  );
});

test('migration is deterministic and idempotent', async () => {
  const second = await getMigrations(auth.options, { throwOnUnsafe: false });
  assert.deepEqual(second.unsafeChanges, []);
  assert.deepEqual(second.toBeCreated, []);
  assert.deepEqual(second.toBeAdded, []);
  assert.deepEqual(second.toBeAddedIndexes, []);
  await second.runMigrations();
  repository.assertSchema();
});

test('authenticated update-user cannot self-enable or forge Dirt-owned identity fields', async () => {
  repository.disableUser('protected');
  const context = await auth.$context;
  const session = await context.internalAdapter.createSession('protected-user');
  assert.ok(session !== null);
  const signature = createHmac('sha256', config.authSecret).update(session.token, 'utf8').digest('base64');
  const cookieValue = encodeURIComponent(`${session.token}.${signature}`);
  const response = await auth.handler(
    new Request(`${config.publicOrigin}/api/auth/update-user`, {
      method: 'POST',
      headers: {
        Cookie: `${context.authCookies.sessionToken.name}=${cookieValue}`,
        'Content-Type': 'application/json',
        Origin: config.publicOrigin,
      },
      body: JSON.stringify({
        handle: 'attacker',
        status: 'active',
        minecraftUuid: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        minecraftName: 'SpoofedPlayer',
      }),
    }),
  );
  assert.ok(response.status === 200 || response.status === 400);
  const unchanged = repository.requireUserById('protected-user');
  assert.equal(unchanged.handle, 'protected');
  assert.equal(unchanged.status, 'disabled');
  assert.equal(unchanged.minecraftAccount, null);

  const clientResponse = await auth.handler(
    new Request(`${config.publicOrigin}/api/auth/oauth2/create-client`, {
      method: 'POST',
      headers: {
        Cookie: `${context.authCookies.sessionToken.name}=${cookieValue}`,
        'Content-Type': 'application/json',
        Origin: config.publicOrigin,
      },
      body: JSON.stringify({
        client_name: 'Untrusted client',
        redirect_uris: ['https://attacker.example/callback'],
        grant_types: ['authorization_code'],
        response_types: ['code'],
      }),
    }),
  );
  assert.ok(clientResponse.status === 401 || clientResponse.status === 403);
  const clientCount = database
    .prepare('SELECT COUNT(*) AS count FROM oauthClient WHERE userId = ?')
    .get('protected-user') as {
    count: number;
  };
  assert.equal(clientCount.count, 0);
});

test('invite, recovery, account status, and link transitions are atomic and bounded', () => {
  const now = new Date('2026-08-30T12:00:00.000Z');
  const invitation = repository.createInvitation(now);
  assert.equal(invitation.invitation.status, 'pending');
  assert.equal(repository.resolveInvitation(invitation.secret, now).recordId, invitation.invitation.id);
  assert.equal(repository.listInvitations(1, now).items[0]?.id, invitation.invitation.id);
  assert.equal(repository.revokeInvitation(invitation.invitation.id, now).status, 'revoked');
  assert.throws(() => repository.resolveInvitation(invitation.secret, now), AccessError);

  insertUser('user-one', 'builder', now);
  const recovery = repository.createRecovery('builder', now);
  assert.equal(new Date(recovery.expiresAt).getTime() - now.getTime(), 15 * 60 * 1_000);
  assert.equal(repository.resolveRecovery(recovery.secret, now).handle, 'builder');

  const first = repository.createMinecraftLinkChallenge('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Builder', now);
  assert.throws(
    () => repository.createMinecraftLinkChallenge('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Builder', now),
    (error) => error instanceof AccessError && error.code === 'conflict',
  );
  const linked = repository.consumeMinecraftLinkChallenge('user-one', first.code, now);
  assert.deepEqual(linked.minecraftAccount, {
    uuid: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    name: 'Builder',
  });
  assert.throws(() => repository.consumeMinecraftLinkChallenge('user-one', first.code, now), AccessError);

  const replacement = repository.createMinecraftLinkChallenge(
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    'Builder',
    new Date(now.getTime() + 10 * 60 * 1_000),
  );
  assert.equal(replacement.code.length, 12);

  const second = repository.createMinecraftLinkChallenge('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'OtherPlayer', now);
  assert.throws(
    () => repository.consumeMinecraftLinkChallenge('user-one', second.code, now),
    (error) => error instanceof AccessError && error.code === 'conflict',
  );
  assert.deepEqual(repository.findMcpUser('user-one'), {
    id: 'user-one',
    minecraftUuid: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  });
  repository.disableUser('builder');
  assert.equal(repository.findMcpUser('user-one'), null);
});

test('internal routes enforce loopback, UUIDv4 calls, exact envelopes, and bounded JSON', async () => {
  const app = createWebApp({
    auth,
    config,
    repository,
    clientScript: '',
    isLoopback: () => true,
    logger: silentLogger,
    mcp: { fetch: async () => new Response(null, { status: 501 }), close: async () => {} },
  });
  const validCallId = '11111111-1111-4111-8111-111111111111';
  const baseHeaders = {
    Authorization: `Bearer ${config.controlToken}`,
    'Content-Type': 'application/json',
    'X-Dirt-Call-Id': validCallId,
  };

  const invalidCall = await app.request('/internal/v1/access/users', {
    headers: { ...baseHeaders, 'X-Dirt-Call-Id': 'not-a-uuid' },
  });
  assert.equal(invalidCall.status, 400);
  assert.deepEqual(await invalidCall.json(), {
    callId: null,
    error: { code: 'invalid_request', message: 'X-Dirt-Call-Id must be a UUIDv4.' },
  });

  const unauthorized = await app.request('/internal/v1/access/users', {
    headers: { ...baseHeaders, Authorization: 'Bearer nope' },
  });
  assert.equal(unauthorized.status, 401);
  assert.equal((await unauthorized.json()).error.code, 'unauthorized');
  assert.equal(unauthorized.headers.get('WWW-Authenticate'), 'Bearer realm="dirt-mcp-control"');
  assert.equal(unauthorized.headers.get('Cache-Control'), 'no-store');

  const create = await app.request('/internal/v1/access/invitations', {
    method: 'POST',
    headers: baseHeaders,
    body: '{}',
  });
  assert.equal(create.status, 200);
  const created = (await create.json()) as Record<string, unknown>;
  assert.equal(created.callId, validCallId);
  assert.match(String(created.inviteUrl), /^http:\/\/127\.0\.0\.1:3000\/invite#token=/u);
  assert.equal(create.headers.get('Cache-Control'), 'no-store');

  const tooLarge = await app.request('/internal/v1/access/invitations', {
    method: 'POST',
    headers: { ...baseHeaders, 'Content-Length': '20000' },
    body: '{}',
  });
  assert.equal(tooLarge.status, 400);
  assert.equal((await tooLarge.json()).error.code, 'invalid_request');

  const missing = await app.request('/internal/v1/access/does-not-exist', { headers: baseHeaders });
  assert.equal(missing.status, 404);
  assert.deepEqual(await missing.json(), {
    callId: validCallId,
    error: { code: 'not_found', message: 'The requested access-control route does not exist.' },
  });

  const originalListUsers = repository.listUsers;
  repository.listUsers = () => {
    throw new Error('secret database detail');
  };
  try {
    const failed = await app.request('/internal/v1/access/users', { headers: baseHeaders });
    assert.equal(failed.status, 500);
    assert.deepEqual(await failed.json(), {
      callId: validCallId,
      error: { code: 'internal_error', message: 'An internal error occurred.' },
    });
  } finally {
    repository.listUsers = originalListUsers;
  }
});

test('web shell exposes a loopback health check and hardened consent copy', async () => {
  const app = createWebApp({
    auth,
    config,
    repository,
    clientScript: '',
    isLoopback: () => true,
    logger: silentLogger,
    mcp: { fetch: async () => new Response(null, { status: 501 }), close: async () => {} },
  });
  const health = await app.request('/healthz');
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), { status: 'ok' });
  const invite = await app.request('/invite', { headers: { Host: '127.0.0.1:3000' } });
  assert.equal(invite.status, 200);
  assert.match(await invite.text(), /Create your Dirt account/u);
  assert.equal(invite.headers.get('Cache-Control'), 'no-store');

  const asset = await app.request('/assets/app.js', { headers: { Host: '127.0.0.1:3000' } });
  assert.equal(asset.status, 200);
  assert.equal(asset.headers.get('Cache-Control'), 'no-cache');

  const jwks = await app.request('/api/auth/jwks', { headers: { Host: '127.0.0.1:3000' } });
  assert.equal(jwks.status, 200);
  assert.equal(jwks.headers.get('Cache-Control'), 'no-store');

  const otherLoopbackAuth = await app.request('/api/auth/get-session', {
    headers: { Host: '127.0.0.1:3000' },
  });
  assert.equal(otherLoopbackAuth.status, 200);

  const publicConfig = { ...config, publicOrigin: 'https://dirt.example', rpId: 'dirt.example' };
  const publicApp = createWebApp({
    auth,
    config: publicConfig,
    repository,
    clientScript: '',
    isLoopback: () => true,
    logger: silentLogger,
    mcp: { fetch: async () => new Response(null, { status: 501 }), close: async () => {} },
  });
  const loopbackJwks = await publicApp.request('/api/auth/jwks', {
    headers: { Host: '127.0.0.1:3000' },
  });
  assert.equal(loopbackJwks.status, 200);
  const rejectedLoopbackAuth = await publicApp.request('/api/auth/get-session', {
    headers: { Host: '127.0.0.1:3000' },
  });
  assert.equal(rejectedLoopbackAuth.status, 400);

  let canonicalAuthUrl = '';
  let canonicalAuthBody = '';
  let canonicalMcpUrl = '';
  let canonicalMcpBody = '';
  const proxyApp = createWebApp({
    auth: {
      async handler(request: Request) {
        canonicalAuthUrl = request.url;
        canonicalAuthBody = await request.text();
        return Response.json({ ok: true });
      },
    } as unknown as typeof auth,
    config: publicConfig,
    repository,
    clientScript: '',
    isLoopback: () => true,
    logger: silentLogger,
    mcp: {
      async fetch(request) {
        canonicalMcpUrl = request.url;
        canonicalMcpBody = await request.text();
        return Response.json({ ok: true });
      },
      async close() {},
    },
  });
  const proxyHeaders = {
    Host: 'dirt.example',
    Origin: 'https://dirt.example',
    'Content-Type': 'application/json',
    'X-Forwarded-Proto': 'https',
  };
  await proxyApp.request('/api/auth/test?state=one', {
    method: 'POST',
    headers: proxyHeaders,
    body: '{"auth":true}',
  });
  await proxyApp.request('/mcp?request=two', {
    method: 'POST',
    headers: proxyHeaders,
    body: '{"mcp":true}',
  });
  assert.equal(canonicalAuthUrl, 'https://dirt.example/api/auth/test?state=one');
  assert.equal(canonicalAuthBody, '{"auth":true}');
  assert.equal(canonicalMcpUrl, 'https://dirt.example/mcp?request=two');
  assert.equal(canonicalMcpBody, '{"mcp":true}');
});

test('handle normalization is current-only and rejects ambiguous account names', () => {
  assert.equal(normalizeHandle('  Player_One  '), 'player_one');
  for (const invalid of ['a', 'ab', '-player', 'player-', 'white space', 'UPPER CASE', 'a'.repeat(33)]) {
    assert.throws(() => normalizeHandle(invalid), AccessError);
  }
});

test('MCP authorization accepts standard Bearer and DPoP schemes case-insensitively', () => {
  assert.equal(extractAccessToken('Bearer access.token'), 'access.token');
  assert.equal(extractAccessToken('bearer access.token'), 'access.token');
  assert.equal(extractAccessToken('DPoP access.token'), 'access.token');
  assert.equal(extractAccessToken('dpop\taccess.token'), 'access.token');
  for (const invalid of [null, '', 'Basic access.token', 'Bearer', 'Bearer one two']) {
    assert.equal(extractAccessToken(invalid), null);
  }
});

function insertUser(id: string, handle: string, now: Date): void {
  database
    .prepare(
      'INSERT INTO "user" (id, name, email, emailVerified, image, createdAt, updatedAt, handle, status, minecraftUuid, minecraftName) VALUES (?, ?, ?, 0, NULL, ?, ?, ?, ?, NULL, NULL)',
    )
    .run(id, handle, `${id}@dirt.placeholder.invalid`, now.getTime(), now.getTime(), handle, 'active');
}

const silentLogger: DirtLogger = {
  child() {
    return this;
  },
  info() {},
  warning() {},
  error() {},
};
