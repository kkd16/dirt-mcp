import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';
import { getMigrations } from 'better-auth/db/migration';
import { AccessError, AccessRepository } from '../dist/access/repository.js';
import {
  createAuth,
  createAuthOptions,
  createOnboardingTicket,
  normalizeHandle,
  onboardingCookieName,
} from '../dist/auth.js';
import type { RuntimeConfig } from '../dist/config.js';
import type { DirtLogger, LogFields } from '../dist/logging.js';
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
  publicOrigin: 'http://localhost:3000',
  rpId: 'localhost',
};
const database = openDatabase(config.databasePath);
const repository = new AccessRepository(database);
const migration = await getMigrations(createAuthOptions(config, database, repository), { throwOnUnsafe: false });
assert.deepEqual(migration.unsafeChanges, []);
await migration.runMigrations();
repository.assertSchema();
const auth = createAuth(config, database, repository);
await auth.$context;
insertUser('protected-user', 'protected', new Date('2026-08-30T11:00:00.000Z'));

after(() => {
  database.close();
  rmSync(directory, { recursive: true, force: true });
});

test('Better Auth is passkey-only and MCP OAuth uses the fixed narrow policy', async () => {
  assert.equal(auth.options.emailAndPassword?.enabled, false);
  assert.equal(auth.options.telemetry?.enabled, false);
  assert.equal(auth.options.logger?.disabled, true);
  assert.equal(auth.options.onAPIError?.throw, true);
  assert.equal(auth.options.advanced?.defaultCookieAttributes?.sameSite, 'strict');
  assert.equal(auth.options.session?.freshAge, 300);
  assert.deepEqual(auth.options.advanced?.ipAddress?.ipAddressHeaders, ['x-forwarded-for']);
  assert.deepEqual(auth.options.advanced?.ipAddress?.trustedProxies, ['127.0.0.1/32', '::1/128']);
  assert.deepEqual(auth.options.disabledPaths, ['/passkey/delete-passkey', '/token']);
  const userFields = auth.options.user?.additionalFields ?? {};
  for (const field of ['handle', 'status', 'minecraftUuid', 'minecraftName', 'authorizationVersion']) {
    assert.equal(userFields[field]?.input, false);
  }
  assert.equal(userFields.authorizationVersion?.returned, false);
  assert.equal(userFields.authorizationVersion?.defaultValue, 0);

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
  assert.equal(typeof oauth.options?.customAccessTokenClaims, 'function');

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
  await assert.rejects(
    () =>
      afterVerification({
        ctx: { body: { createSession: false }, headers: new Headers() },
        verification: { registrationInfo: { userVerified: true } },
        user: { id: 'invite-record', name: 'builder' },
      }),
    (error) => error instanceof Error && /must create a fresh session/u.test(error.message),
  );

  const ticket = createOnboardingTicket(
    { kind: 'invitation', recordId: 'record', handle: 'builder' },
    config.authSecret,
  );
  const ticketHeaders = new Headers({
    Cookie: `${onboardingCookieName(config.publicOrigin)}=${ticket.value}`,
  });
  await assert.rejects(
    () =>
      afterVerification({
        ctx: { body: { createSession: true }, headers: ticketHeaders },
        verification: { registrationInfo: { userVerified: true } },
        user: { id: 'invite-other-record', name: 'builder' },
      }),
    (error) => error instanceof Error && /does not match this link/u.test(error.message),
  );

  const publicAuth = createAuth(
    { ...config, publicOrigin: 'https://dirt.example', rpId: 'dirt.example' },
    database,
    repository,
  );
  await publicAuth.$context;
  const publicPasskey = (publicAuth.options.plugins ?? []).find((plugin: { id: string }) => plugin.id === 'passkey') as
    | { options?: Record<string, unknown> }
    | undefined;
  const publicRegistration = publicPasskey?.options?.registration as Record<string, unknown>;
  const publicAfterVerification = publicRegistration.afterVerification as (input: unknown) => Promise<unknown>;
  await assert.rejects(
    () =>
      publicAfterVerification({
        ctx: {
          body: { createSession: true },
          headers: new Headers({ Cookie: `dirt-onboarding=${ticket.value}` }),
        },
        verification: { registrationInfo: { userVerified: true } },
        user: { id: 'invite-record', name: 'builder' },
      }),
    (error) => error instanceof Error && /valid invitation or recovery link/u.test(error.message),
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

test('Dirt identity fields are not writable and OAuth client administration is unavailable', async () => {
  repository.disableUser('protected');
  const response = await auth.handler(
    new Request(`${config.publicOrigin}/api/auth/update-user`, {
      method: 'POST',
      headers: {
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
  assert.equal(response.status, 401);
  const unchanged = repository.requireUserById('protected-user');
  assert.equal(unchanged.handle, 'protected');
  assert.equal(unchanged.status, 'disabled');
  assert.equal(unchanged.minecraftAccount, null);

  const clientResponse = await auth.handler(
    new Request(`${config.publicOrigin}/api/auth/oauth2/create-client`, {
      method: 'POST',
      headers: {
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

test('unexpected auth transport failures use the sanitized web error boundary', async () => {
  const errors: Array<{ readonly event: string; readonly fields: LogFields | undefined }> = [];
  const logger: DirtLogger = {
    child() {
      return this;
    },
    info() {},
    warning() {},
    error(event, _message, fields) {
      errors.push({ event, fields });
    },
  };
  const app = createWebApp({
    auth,
    config,
    repository,
    clientScript: '',
    isLoopback: () => true,
    logger,
    mcp: { fetch: async () => new Response(null, { status: 501 }), close: async () => {} },
  });
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.error(new Error('private aborted-body detail'));
    },
  });

  const response = await app.fetch(
    streamingRequest(`${config.publicOrigin}/api/auth/update-user`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Host: 'localhost:3000',
        Origin: config.publicOrigin,
      },
      body,
    }),
  );

  assert.equal(response.status, 500);
  assert.deepEqual(await response.json(), { error: 'An internal error occurred.' });
  assert.deepEqual(errors, [
    {
      event: 'web.request_failed',
      fields: { path: '/api/auth/update-user' },
    },
  ]);
  assert.doesNotMatch(JSON.stringify(errors), /private aborted-body detail/u);
});

test('invite, recovery, account status, and link transitions are atomic and bounded', () => {
  const now = new Date('2026-08-30T12:00:00.000Z');
  const invitation = repository.createInvitation(now);
  assert.equal(invitation.invitation.status, 'pending');
  assert.equal(repository.resolveInvitation(invitation.secret, now).recordId, invitation.invitation.id);
  assert.equal(repository.listInvitations(1, now).items[0]?.id, invitation.invitation.id);
  assert.equal(repository.revokeInvitation(invitation.invitation.id, now).status, 'revoked');
  assert.throws(() => repository.resolveInvitation(invitation.secret, now), AccessError);
  const corruptInvitation = repository.createInvitation(now);
  database
    .prepare('UPDATE invitation SET expiresAt = ? WHERE id = ?')
    .run('not-a-date', corruptInvitation.invitation.id);
  assert.throws(() => repository.resolveInvitation(corruptInvitation.secret, now), /invalid timestamp/u);
  database.prepare('DELETE FROM invitation WHERE id = ?').run(corruptInvitation.invitation.id);

  insertUser('user-one', 'builder', now);
  const expiredRecovery = repository.createRecovery('builder', new Date(now.getTime() - 16 * 60 * 1_000));
  assert.throws(() => repository.resolveRecovery(expiredRecovery.secret, now), AccessError);
  const recovery = repository.createRecovery('builder', now);
  assert.equal(new Date(recovery.expiresAt).getTime() - now.getTime(), 15 * 60 * 1_000);
  assert.equal(repository.resolveRecovery(recovery.secret, now).handle, 'builder');
  const expiredRecoveryCount = database
    .prepare<[number], { count: number }>('SELECT COUNT(*) AS count FROM credentialRecovery WHERE expiresAt <= ?')
    .get(now.getTime());
  assert.equal(expiredRecoveryCount?.count, 0);

  const first = repository.createMinecraftLinkChallenge('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Builder', now);
  assert.throws(
    () => repository.createMinecraftLinkChallenge('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Builder', now),
    (error) => error instanceof AccessError && error.code === 'conflict',
  );
  insertAuthorizationArtifacts('user-one', 'link', now);
  const linked = repository.consumeMinecraftLinkChallenge('user-one', first.code, now);
  assert.deepEqual(linked.minecraftAccount, {
    uuid: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    name: 'Builder',
  });
  assert.equal(repository.requireAuthorizationVersion('user-one'), 1);
  assert.equal(authorizationArtifactCount('user-one'), 1);
  assert.equal(
    database
      .prepare<[string], { count: number }>('SELECT COUNT(*) AS count FROM session WHERE userId = ?')
      .get('user-one')?.count,
    1,
  );
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
  assert.deepEqual(repository.findMcpUser('user-one', 1), {
    id: 'user-one',
    minecraftUuid: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  });

  insertAuthorizationArtifacts('user-one', 'disable', now);
  assert.equal(repository.requireAuthorizationVersion('user-one'), 1);
  assert.equal(repository.hasPasskey('user-one'), true);
  repository.disableUser('builder');
  assert.equal(repository.findMcpUser('user-one', 1), null);
  assert.equal(repository.requireAuthorizationVersion('user-one'), 2);
  assert.equal(authorizationArtifactCount('user-one'), 0);
  assert.equal(repository.hasPasskey('user-one'), true);
  repository.enableUser('builder');
  assert.equal(repository.requireAuthorizationVersion('user-one'), 3);
  assert.equal(repository.findMcpUser('user-one', 2), null);
  assert.deepEqual(repository.findMcpUser('user-one', 3), {
    id: 'user-one',
    minecraftUuid: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  });

  insertAuthorizationArtifacts('user-one', 'unlink', now);
  repository.unlinkUser('builder');
  assert.equal(repository.requireAuthorizationVersion('user-one'), 4);
  assert.equal(authorizationArtifactCount('user-one'), 0);
  assert.equal(repository.hasPasskey('user-one'), true);
  assert.equal(repository.findMcpUser('user-one', 3), null);
  database.prepare('UPDATE "user" SET status = ? WHERE id = ?').run('unexpected', 'user-one');
  assert.throws(() => repository.requireUserById('user-one'), /invalid user status/u);
  assert.equal(repository.findMcpUser('user-one', 4), null);
  database.prepare('UPDATE "user" SET status = ? WHERE id = ?').run('active', 'user-one');
});

test('internal routes enforce loopback, UUIDv4 calls, exact envelopes, and bounded JSON', async () => {
  const internalErrors: Array<{ readonly event: string; readonly fields: LogFields | undefined }> = [];
  const internalLogger: DirtLogger = {
    child() {
      return this;
    },
    info() {},
    warning() {},
    error(event, _message, fields) {
      internalErrors.push({ event, fields });
    },
  };
  const app = createWebApp({
    auth,
    config,
    repository,
    clientScript: '',
    isLoopback: () => true,
    logger: internalLogger,
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
  assert.match(String(created.inviteUrl), /^http:\/\/localhost:3000\/invite#token=/u);
  assert.equal(create.headers.get('Cache-Control'), 'no-store');

  const tooLarge = await app.request('/internal/v1/access/invitations', {
    method: 'POST',
    headers: { ...baseHeaders, 'Content-Length': '20000' },
    body: '{}',
  });
  assert.equal(tooLarge.status, 400);
  assert.equal((await tooLarge.json()).error.code, 'invalid_request');

  let internalBodyCanceled = false;
  const cancelableInternalBody = new ReadableStream<Uint8Array>({
    cancel() {
      internalBodyCanceled = true;
    },
  });
  const canceledTooLarge = await app.fetch(
    streamingRequest(`${config.publicOrigin}/internal/v1/access/invitations`, {
      method: 'POST',
      headers: { ...baseHeaders, 'Content-Length': '20000' },
      body: cancelableInternalBody,
    }),
  );
  assert.equal(canceledTooLarge.status, 400);
  assert.equal(internalBodyCanceled, true);

  let streamedInternalBodyCanceled = false;
  const streamedInternalBody = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new Uint8Array(16_384));
      controller.enqueue(new Uint8Array(1));
    },
    cancel() {
      streamedInternalBodyCanceled = true;
    },
  });
  const canceledInternalStream = await app.fetch(
    streamingRequest(`${config.publicOrigin}/internal/v1/access/invitations`, {
      method: 'POST',
      headers: baseHeaders,
      body: streamedInternalBody,
    }),
  );
  assert.equal(canceledInternalStream.status, 400);
  assert.equal(streamedInternalBodyCanceled, true);

  const missing = await app.request('/internal/v1/access/does-not-exist', { headers: baseHeaders });
  assert.equal(missing.status, 404);
  assert.deepEqual(await missing.json(), {
    callId: validCallId,
    error: { code: 'not_found', message: 'The requested access-control route does not exist.' },
  });

  const originalDisableUser = repository.disableUser;
  repository.disableUser = () => {
    throw new Error('secret database detail');
  };
  try {
    const failed = await app.request('/internal/v1/access/users/private-handle/disable', {
      method: 'POST',
      headers: baseHeaders,
      body: '{}',
    });
    assert.equal(failed.status, 500);
    assert.deepEqual(await failed.json(), {
      callId: validCallId,
      error: { code: 'internal_error', message: 'An internal error occurred.' },
    });
    assert.equal(internalErrors.length, 1);
    assert.equal(internalErrors[0]?.event, 'access_control.request_failed');
    assert.equal(internalErrors[0]?.fields?.route, '/internal/v1/access/users/:handle/disable');
    assert.equal(internalErrors[0]?.fields?.error_type, 'Error');
    assert.match(String(internalErrors[0]?.fields?.stack_locations), /web-runtime\.test/u);
    assert.doesNotMatch(JSON.stringify(internalErrors), /secret database detail/u);
    assert.doesNotMatch(JSON.stringify(internalErrors), /private-handle/u);
  } finally {
    repository.disableUser = originalDisableUser;
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
  const invite = await app.request('/invite', { headers: { Host: 'localhost:3000' } });
  assert.equal(invite.status, 200);
  assert.match(await invite.text(), /Create your Dirt account/u);
  assert.equal(invite.headers.get('Cache-Control'), 'no-store');

  const asset = await app.request('/assets/app.js', { headers: { Host: 'localhost:3000' } });
  assert.equal(asset.status, 200);
  assert.equal(asset.headers.get('Cache-Control'), 'no-cache');

  const jwks = await app.request('/api/auth/jwks', { headers: { Host: 'localhost:3000' } });
  assert.equal(jwks.status, 200);
  assert.equal(jwks.headers.get('Cache-Control'), 'no-store');

  const otherLoopbackAuth = await app.request('/api/auth/get-session', {
    headers: { Host: 'localhost:3000' },
  });
  assert.equal(otherLoopbackAuth.status, 200);

  let browserBodyCanceled = false;
  const cancelableBrowserBody = new ReadableStream<Uint8Array>({
    cancel() {
      browserBodyCanceled = true;
    },
  });
  const oversizedBrowserRequest = await app.fetch(
    streamingRequest(`${config.publicOrigin}/api/onboarding/exchange`, {
      method: 'POST',
      headers: {
        'Content-Length': '20000',
        'Content-Type': 'application/json',
        Host: 'localhost:3000',
        Origin: config.publicOrigin,
      },
      body: cancelableBrowserBody,
    }),
  );
  assert.equal(oversizedBrowserRequest.status, 400);
  assert.equal(browserBodyCanceled, true);

  let streamedBrowserBodyCanceled = false;
  const streamedBrowserBody = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new Uint8Array(16_384));
      controller.enqueue(new Uint8Array(1));
    },
    cancel() {
      streamedBrowserBodyCanceled = true;
    },
  });
  const oversizedBrowserStream = await app.fetch(
    streamingRequest(`${config.publicOrigin}/api/onboarding/exchange`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Host: 'localhost:3000',
        Origin: config.publicOrigin,
      },
      body: streamedBrowserBody,
    }),
  );
  assert.equal(oversizedBrowserStream.status, 400);
  assert.equal(streamedBrowserBodyCanceled, true);

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
      api: {
        async getSession() {
          return null;
        },
      },
    },
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

test('new OAuth grants require a fresh passkey session and a linked account', async () => {
  const now = new Date();
  insertUser('oauth-user', 'oauthuser', now);
  database
    .prepare('UPDATE "user" SET minecraftUuid = ?, minecraftName = ? WHERE id = ?')
    .run('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'OAuthPlayer', 'oauth-user');
  let sessionCreatedAt = new Date(Date.now() - 6 * 60 * 1_000);
  const app = createWebApp({
    auth: sessionAuth('oauth-user', () => sessionCreatedAt),
    config,
    repository,
    clientScript: '',
    isLoopback: () => true,
    logger: silentLogger,
    mcp: { fetch: async () => new Response(null, { status: 501 }), close: async () => {} },
  });
  const oauthQuery =
    '?client_id=https%3A%2F%2Fclient.example%2Fclient.json&redirect_uri=https%3A%2F%2Fclient.example%2Fcallback&response_type=code&scope=dirt%3Amcp&state=test';
  const browserHeaders = { Host: 'localhost:3000' };

  const staleConsentPage = await app.request(`/consent${oauthQuery}`, { headers: browserHeaders });
  assert.equal(staleConsentPage.status, 303);
  assert.equal(staleConsentPage.headers.get('Location'), `/sign-in${oauthQuery}`);
  const staleAuthorize = await app.request(`/api/auth/oauth2/authorize${oauthQuery}`, { headers: browserHeaders });
  assert.equal(staleAuthorize.status, 303);
  assert.equal(staleAuthorize.headers.get('Location'), `/sign-in${oauthQuery}`);
  const rejectedVariants = await Promise.all(
    [
      `/api/auth/oauth2/authorize/${oauthQuery}`,
      `/api/auth/oauth2/%61uthorize${oauthQuery}`,
      `/api/auth/oauth2//authorize${oauthQuery}`,
    ].map((path) => app.request(path, { headers: browserHeaders })),
  );
  for (const response of rejectedVariants) {
    assert.equal(response.status, 404);
  }
  const normalizedAuthorize = await app.request(`/api/auth/oauth2/ignored/../authorize${oauthQuery}`, {
    headers: browserHeaders,
  });
  assert.equal(normalizedAuthorize.status, 303);
  assert.equal(normalizedAuthorize.headers.get('Location'), `/sign-in${oauthQuery}`);
  const staleConsentPost = await app.request('/api/auth/oauth2/consent', {
    method: 'POST',
    headers: {
      ...browserHeaders,
      'Content-Type': 'application/json',
      Origin: config.publicOrigin,
    },
    body: JSON.stringify({ accept: true, oauth_query: oauthQuery.slice(1) }),
  });
  assert.equal(staleConsentPost.status, 403);
  assert.deepEqual(await staleConsentPost.json(), { error: 'Recent passkey authentication is required.' });

  sessionCreatedAt = new Date();
  const freshConsentPage = await app.request('/consent?client_id=%3Cscript%3E&scope=dirt%3Amcp', {
    headers: browserHeaders,
  });
  assert.equal(freshConsentPage.status, 200);
  assert.doesNotMatch(await freshConsentPage.text(), /<h1>Allow <script>/u);

  repository.unlinkUser('oauthuser');
  const unlinkedConsentPost = await app.request('/api/auth/oauth2/consent', {
    method: 'POST',
    headers: {
      ...browserHeaders,
      'Content-Type': 'application/json',
      Origin: config.publicOrigin,
    },
    body: JSON.stringify({ accept: true, oauth_query: oauthQuery.slice(1) }),
  });
  assert.equal(unlinkedConsentPost.status, 403);
  assert.deepEqual(await unlinkedConsentPost.json(), {
    error: 'Link a Minecraft account before authorizing MCP.',
  });
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
      'INSERT INTO "user" (id, name, email, emailVerified, image, createdAt, updatedAt, handle, status, minecraftUuid, minecraftName, authorizationVersion) VALUES (?, ?, ?, 0, NULL, ?, ?, ?, ?, NULL, NULL, 0)',
    )
    .run(id, handle, `${id}@dirt.placeholder.invalid`, now.getTime(), now.getTime(), handle, 'active');
}

function insertAuthorizationArtifacts(userId: string, suffix: string, now: Date): void {
  const clientId = `https://client-${suffix}.example/client.json`;
  const sessionId = `session-${suffix}`;
  const refreshId = `refresh-${suffix}`;
  database
    .prepare('INSERT INTO oauthClient (id, clientId, redirectUris) VALUES (?, ?, ?)')
    .run(`client-row-${suffix}`, clientId, JSON.stringify([`https://client-${suffix}.example/callback`]));
  database
    .prepare('INSERT INTO session (id, expiresAt, token, createdAt, updatedAt, userId) VALUES (?, ?, ?, ?, ?, ?)')
    .run(sessionId, now.getTime() + 60_000, `session-token-${suffix}`, now.getTime(), now.getTime(), userId);
  database
    .prepare(
      'INSERT INTO verification (id, identifier, value, expiresAt, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)',
    )
    .run(
      `verification-${suffix}`,
      `authorization-code-${suffix}`,
      JSON.stringify({ type: 'authorization_code', userId }),
      now.getTime() + 60_000,
      now.getTime(),
      now.getTime(),
    );
  database
    .prepare('INSERT INTO oauthConsent (id, clientId, userId, scopes, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)')
    .run(`consent-${suffix}`, clientId, userId, JSON.stringify(['dirt:mcp']), now.getTime(), now.getTime());
  database
    .prepare(
      'INSERT INTO oauthRefreshToken (id, token, clientId, sessionId, userId, expiresAt, createdAt, scopes) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
    )
    .run(
      refreshId,
      `refresh-token-${suffix}`,
      clientId,
      sessionId,
      userId,
      now.getTime() + 60_000,
      now.getTime(),
      JSON.stringify(['dirt:mcp']),
    );
  database
    .prepare(
      'INSERT INTO oauthAccessToken (id, token, clientId, sessionId, userId, refreshId, expiresAt, createdAt, scopes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
    )
    .run(
      `access-${suffix}`,
      `access-token-${suffix}`,
      clientId,
      sessionId,
      userId,
      refreshId,
      now.getTime() + 60_000,
      now.getTime(),
      JSON.stringify(['dirt:mcp']),
    );
  if (!repository.hasPasskey(userId)) {
    database
      .prepare(
        'INSERT INTO passkey (id, name, publicKey, userId, credentialID, counter, deviceType, backedUp, createdAt) VALUES (?, ?, ?, ?, ?, 0, ?, 0, ?)',
      )
      .run(
        'passkey-user-one',
        'Primary passkey',
        'public-key',
        userId,
        'credential-user-one',
        'singleDevice',
        now.getTime(),
      );
  }
}

function authorizationArtifactCount(userId: string): number {
  const queries = [
    'SELECT COUNT(*) AS count FROM session WHERE userId = ?',
    'SELECT COUNT(*) AS count FROM oauthConsent WHERE userId = ?',
    'SELECT COUNT(*) AS count FROM oauthAccessToken WHERE userId = ?',
    'SELECT COUNT(*) AS count FROM oauthRefreshToken WHERE userId = ?',
    `SELECT COUNT(*) AS count FROM verification
     WHERE json_valid(value)
       AND json_extract(value, '$.type') = 'authorization_code'
       AND json_extract(value, '$.userId') = ?`,
  ];
  return queries.reduce((total, query) => {
    const row = database.prepare<[string], { count: number }>(query).get(userId);
    return total + (row?.count ?? 0);
  }, 0);
}

function streamingRequest(
  input: string,
  init: Omit<RequestInit, 'body'> & { readonly body: ReadableStream<Uint8Array> },
): Request {
  const nodeRequestInit: RequestInit & { readonly duplex: 'half' } = { ...init, duplex: 'half' };
  return new Request(input, nodeRequestInit);
}

function sessionAuth(userId: string, createdAt: () => Date) {
  return {
    handler: auth.handler,
    api: {
      async getSession() {
        return {
          user: { id: userId },
          session: { createdAt: createdAt() },
        };
      },
    },
  };
}

const silentLogger: DirtLogger = {
  child() {
    return this;
  },
  info() {},
  warning() {},
  error() {},
};
