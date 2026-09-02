import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';
import { getCurrentAdapter } from 'better-auth';
import { getMigrations } from 'better-auth/db/migration';
import { AccessError, AccessRepository } from '../dist/access/repository.js';
import { createAuth, createAuthOptions, createOnboardingTicket, onboardingCookieName } from '../dist/auth.js';
import { BridgeClient } from '../dist/bridge/client.js';
import { BRIDGE_OPERATION_IDS } from '../dist/bridge/contract.js';
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
};
const database = openDatabase(config.databasePath);
const repository = new AccessRepository(database);
const readyBridge = testBridge(BRIDGE_OPERATION_IDS);
const freshSchema = await getMigrations(createAuthOptions(config, database, repository));
await freshSchema.runMigrations();
repository.assertSchema();
const auth = createAuth(config, database, repository);
const authContext = await auth.$context;
const authAdapter = await getCurrentAdapter(authContext.adapter);

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
  assert.deepEqual(auth.options.disabledPaths, [
    '/oauth2/delete-consent',
    '/oauth2/get-consent',
    '/oauth2/get-consents',
    '/oauth2/update-consent',
    '/passkey/delete-passkey',
    '/token',
  ]);
  const userFields = auth.options.user?.additionalFields ?? {};
  for (const field of ['status', 'minecraftUuid', 'authorizationVersion']) {
    assert.equal(userFields[field]?.input, false);
  }
  assert.equal(userFields.handle, undefined);
  assert.equal(userFields.minecraftName, undefined);
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
  assert.equal(oauth.options?.loginPage, '/');
  assert.equal(oauth.options?.consentPage, '/consent');
  assert.deepEqual(oauth.options?.scopes, ['dirt:mcp', 'offline_access']);
  assert.deepEqual(oauth.options?.grantTypes, ['authorization_code', 'refresh_token']);
  assert.equal(oauth.options?.accessTokenExpiresIn, 300);
  assert.equal(oauth.options?.codeExpiresIn, 300);
  assert.equal(oauth.options?.refreshTokenExpiresIn, 2_592_000);
  assert.equal(oauth.options?.allowDynamicClientRegistration, false);
  assert.equal(oauth.options?.allowUnauthenticatedClientRegistration, false);
  assert.equal(oauth.options?.clientRegistrationRequirePKCE, true);
  assert.equal((oauth.options?.clientPrivileges as (() => unknown) | undefined)?.(), false);
  assert.equal((oauth.options?.resourcePrivileges as (() => unknown) | undefined)?.(), false);
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
    {
      kind: 'invitation',
      recordId: 'record',
      username: 'Builder',
      minecraftUuid: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    },
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
        user: { id: 'invite-other-record', name: 'Builder' },
      }),
    (error) => error instanceof Error && /does not match this link/u.test(error.message),
  );

  const publicAuth = createAuth({ ...config, publicOrigin: 'https://dirt.example' }, database, repository);
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
        user: { id: 'invite-record', name: 'Builder' },
      }),
    (error) => error instanceof Error && /valid invitation or recovery link/u.test(error.message),
  );
});

test('the generated schema matches the current database exactly', async () => {
  const second = await getMigrations(auth.options);
  assert.deepEqual(second.unsafeChanges, []);
  assert.deepEqual(second.toBeCreated, []);
  assert.deepEqual(second.toBeAdded, []);
  assert.deepEqual(second.toBeAddedIndexes, []);
  await second.runMigrations();
  repository.assertSchema();
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
    bridge: readyBridge,
    config,
    repository,
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

test('invite, recovery, account status, and link transitions are atomic and bounded', async () => {
  const now = new Date('2026-08-30T12:00:00.000Z');
  const invitation = repository.createInvitation('11111111-1111-4111-8111-111111111111', 'InvitedPlayer', now);
  assert.equal(invitation.invitation.status, 'pending');
  assert.deepEqual(invitation.invitation.minecraftAccount, {
    uuid: '11111111-1111-4111-8111-111111111111',
    name: 'InvitedPlayer',
  });
  assert.deepEqual(repository.resolveInvitation(invitation.secret, now), {
    kind: 'invitation',
    recordId: invitation.invitation.id,
    username: 'InvitedPlayer',
    minecraftUuid: '11111111-1111-4111-8111-111111111111',
  });
  assert.throws(
    () => repository.createInvitation('11111111-1111-4111-8111-111111111111', 'InvitedPlayer', now),
    (error) => error instanceof AccessError && error.code === 'conflict',
  );
  assert.deepEqual(
    database
      .prepare<[string], { createdAt: string; expiresAt: string }>(
        'SELECT typeof(createdAt) AS createdAt, typeof(expiresAt) AS expiresAt FROM invitation WHERE id = ?',
      )
      .get(invitation.invitation.id),
    { createdAt: 'text', expiresAt: 'text' },
  );
  const adaptedInvitation = await authAdapter.findOne<{ createdAt: Date; expiresAt: Date }>({
    model: 'invitation',
    where: [{ field: 'id', value: invitation.invitation.id }],
  });
  assert.equal(adaptedInvitation?.createdAt.toISOString(), now.toISOString());
  assert.equal(adaptedInvitation?.expiresAt.toISOString(), invitation.invitation.expiresAt);
  assert.ok(repository.listInvitations(1, now).items.some(({ id }) => id === invitation.invitation.id));
  assert.equal(repository.revokeInvitation(invitation.invitation.id, now).status, 'revoked');
  assert.throws(() => repository.resolveInvitation(invitation.secret, now), AccessError);
  const corruptInvitation = repository.createInvitation('22222222-2222-4222-8222-222222222222', 'OtherPlayer', now);
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
  const recoveryClaim = repository.resolveRecovery(recovery.secret, now);
  assert.equal(recoveryClaim.username, 'builder');
  const adaptedRecovery = await authAdapter.findOne<{ createdAt: Date; expiresAt: Date }>({
    model: 'credentialRecovery',
    where: [{ field: 'id', value: recoveryClaim.recordId }],
  });
  assert.equal(adaptedRecovery?.createdAt.toISOString(), now.toISOString());
  assert.equal(adaptedRecovery?.expiresAt.toISOString(), recovery.expiresAt);
  const expiredRecoveryCount = database
    .prepare<[string], { count: number }>('SELECT COUNT(*) AS count FROM credentialRecovery WHERE expiresAt <= ?')
    .get(now.toISOString());
  assert.equal(expiredRecoveryCount?.count, 0);

  const first = repository.createMinecraftLinkChallenge('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Builder', now);
  assert.throws(
    () => repository.createMinecraftLinkChallenge('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Builder', now),
    (error) => error instanceof AccessError && error.code === 'conflict',
  );
  insertAuthorizationArtifacts('user-one', 'link', now);
  const linked = repository.consumeMinecraftLinkChallenge('user-one', first.code, now);
  assert.equal(linked.minecraftUuid, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
  assert.equal(linked.username, 'Builder');
  assert.equal(repository.requireAuthorizationVersion('user-one'), 1);
  assert.equal(authorizationArtifactCount('user-one'), 1);
  assert.equal(
    database
      .prepare<[string], { count: number }>('SELECT COUNT(*) AS count FROM session WHERE userId = ?')
      .get('user-one')?.count,
    1,
  );
  assert.throws(() => repository.consumeMinecraftLinkChallenge('user-one', first.code, now), AccessError);

  const replacementTime = new Date(now.getTime() + 10 * 60 * 1_000);
  const replacement = repository.createMinecraftLinkChallenge(
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    'Builder',
    replacementTime,
  );
  assert.equal(replacement.code.length, 12);
  repository.consumeMinecraftLinkChallenge('user-one', replacement.code, replacementTime);
  assert.equal(repository.requireAuthorizationVersion('user-one'), 1);

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
  assert.equal(hasPasskey('user-one'), true);
  repository.disableUser('builder');
  assert.equal(repository.findMcpUser('user-one', 1), null);
  assert.equal(repository.requireAuthorizationVersion('user-one'), 2);
  assert.equal(authorizationArtifactCount('user-one'), 0);
  assert.equal(hasPasskey('user-one'), true);
  repository.disableUser('builder');
  assert.equal(repository.requireAuthorizationVersion('user-one'), 2);
  repository.enableUser('builder');
  assert.equal(repository.requireAuthorizationVersion('user-one'), 3);
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
  repository.unlinkUser('builder');
  assert.equal(repository.requireAuthorizationVersion('user-one'), 4);
  assert.equal(authorizationArtifactCount('user-one'), 0);
  assert.equal(hasPasskey('user-one'), true);
  assert.equal(repository.findMcpUser('user-one', 3), null);
  database.prepare('UPDATE "user" SET status = ? WHERE id = ?').run('unexpected', 'user-one');
  assert.throws(() => repository.requireUserById('user-one'), /invalid user status/u);
  assert.equal(repository.findMcpUser('user-one', 4), null);
  database.prepare('UPDATE "user" SET status = ? WHERE id = ?').run('active', 'user-one');
});

test('MCP client revocation removes only one user-client authorization', () => {
  const now = new Date('2026-08-30T13:00:00.000Z');
  insertUser('revocation-user', 'revoker', now);
  insertUser('other-revocation-user', 'otherrevoker', now);
  const selected = insertAuthorizationArtifacts('revocation-user', 'selected-client', now);
  const retained = insertAuthorizationArtifacts('revocation-user', 'retained-client', now);
  const otherUser = insertAuthorizationArtifacts(
    'other-revocation-user',
    'other-user-selected-client',
    now,
    selected.clientId,
  );

  assert.equal(repository.hasMcpClientConsent('revocation-user', selected.clientId), true);
  assert.throws(
    () => repository.revokeMcpClient('revocation-user', otherUser.consentId),
    (error) => error instanceof AccessError && error.code === 'not_found',
  );
  repository.revokeMcpClient('revocation-user', selected.consentId);

  assert.equal(repository.hasMcpClientConsent('revocation-user', selected.clientId), false);
  assert.equal(repository.hasMcpClientConsent('revocation-user', retained.clientId), true);
  assert.equal(repository.hasMcpClientConsent('other-revocation-user', selected.clientId), true);
  assert.equal(pairArtifactCount('revocation-user', selected.clientId), 0);
  assert.equal(pairArtifactCount('revocation-user', retained.clientId), 4);
  assert.equal(pairArtifactCount('other-revocation-user', selected.clientId), 4);
  assert.equal(
    database
      .prepare<[string], { count: number }>('SELECT COUNT(*) AS count FROM session WHERE userId = ?')
      .get('revocation-user')?.count,
    2,
  );
  assert.equal(hasPasskey('revocation-user'), true);
  assert.equal(
    database
      .prepare<[string], { count: number }>('SELECT COUNT(*) AS count FROM oauthClient WHERE clientId = ?')
      .get(selected.clientId)?.count,
    1,
  );
});

test('internal routes enforce loopback, UUIDv4 calls, exact envelopes, and bounded JSON', async () => {
  const internalErrors: Array<{ readonly event: string; readonly fields: LogFields | undefined }> = [];
  let loopback = true;
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
    bridge: readyBridge,
    config,
    repository,
    isLoopback: () => loopback,
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

  const uppercaseCall = await app.request('/internal/v1/access/users', {
    headers: { ...baseHeaders, 'X-Dirt-Call-Id': 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA' },
  });
  assert.equal(uppercaseCall.status, 400);
  assert.equal((await uppercaseCall.json()).callId, null);

  loopback = false;
  const nonLoopback = await app.request('/internal/v1/access/users', { headers: baseHeaders });
  loopback = true;
  assert.equal(nonLoopback.status, 403);
  assert.equal((await nonLoopback.json()).error.code, 'unauthorized');

  const unauthorized = await app.request('/internal/v1/access/users', {
    headers: { ...baseHeaders, Authorization: 'Bearer nope' },
  });
  assert.equal(unauthorized.status, 401);
  assert.equal((await unauthorized.json()).error.code, 'unauthorized');
  assert.equal(unauthorized.headers.get('WWW-Authenticate'), 'Bearer realm="dirt-mcp-control"');
  assert.equal(unauthorized.headers.get('Cache-Control'), 'no-store');

  const standardBearer = await app.request('/internal/v1/access/users?page=1', {
    headers: { ...baseHeaders, Authorization: `bEaReR  ${config.controlToken}` },
  });
  assert.equal(standardBearer.status, 200);

  const missingPage = await app.request('/internal/v1/access/users', { headers: baseHeaders });
  assert.equal(missingPage.status, 400);
  assert.equal((await missingPage.json()).error.code, 'invalid_request');

  const nonStandardBearer = await app.request('/internal/v1/access/users', {
    headers: { ...baseHeaders, Authorization: `Bearer\t${config.controlToken}` },
  });
  assert.equal(nonStandardBearer.status, 401);

  const create = await app.request('/internal/v1/access/invitations', {
    method: 'POST',
    headers: baseHeaders,
    body: JSON.stringify({
      minecraftUuid: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
      minecraftName: 'InvitedPlayer',
    }),
  });
  assert.equal(create.status, 200);
  const created = (await create.json()) as Record<string, unknown>;
  assert.equal(created.callId, validCallId);
  assert.match(String(created.inviteUrl), /^http:\/\/localhost:3000\/invite#token=/u);
  assert.equal(create.headers.get('Cache-Control'), 'no-store');

  const linkChallenge = await app.request('/internal/v1/access/minecraft-links/challenges', {
    method: 'POST',
    headers: baseHeaders,
    body: JSON.stringify({
      minecraftUuid: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      minecraftName: 'FieldPlayer',
    }),
  });
  assert.equal(linkChallenge.status, 200);
  assert.match(
    String(((await linkChallenge.json()) as Record<string, unknown>).linkUrl),
    /^http:\/\/localhost:3000\/dashboard#code=/u,
  );

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
  assert.equal((await canceledTooLarge.json()).error.code, 'invalid_request');
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
    const failed = await app.request('/internal/v1/access/users/private-selector/disable', {
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
    assert.equal(internalErrors[0]?.fields?.route, '/internal/v1/access/users/:selector/disable');
    assert.equal(internalErrors[0]?.fields?.error_type, 'Error');
    assert.doesNotMatch(JSON.stringify(internalErrors), /secret database detail/u);
    assert.doesNotMatch(JSON.stringify(internalErrors), /private-selector/u);
  } finally {
    repository.disableUser = originalDisableUser;
  }
});

test('web shell exposes a loopback health check and hardened consent copy', async () => {
  const app = testWebApp();
  const health = await app.request('/healthz');
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), { status: 'ok' });

  const disabledConsentRoutes = await Promise.all(
    (
      [
        ['POST', '/api/auth/oauth2/delete-consent'],
        ['GET', '/api/auth/oauth2/get-consent'],
        ['GET', '/api/auth/oauth2/get-consents'],
        ['POST', '/api/auth/oauth2/update-consent'],
      ] as const
    ).map(([method, path]) =>
      app.request(path, {
        method,
        headers: { Host: 'localhost:3000', Origin: config.publicOrigin },
      }),
    ),
  );
  assert.deepEqual(
    disabledConsentRoutes.map((response) => response.status),
    [404, 404, 404, 404],
  );

  const home = await app.request('/', { headers: { Host: 'localhost:3000' } });
  assert.equal(home.status, 200);
  const homeHtml = await home.text();
  assert.match(homeHtml, /^<!DOCTYPE html>/u);
  assert.match(homeHtml, /Sign in to Dirt/u);
  assert.match(homeHtml, /Sign in with a passkey/u);
  assert.match(homeHtml, /Ask an operator to invite you in Minecraft/u);
  assert.doesNotMatch(homeHtml, /Inspect and edit|Gateway|How Dirt works|Paper world/u);
  assert.doesNotMatch(homeHtml, /Paper and FAWE online/u);

  const signedOutDashboard = await app.request('/dashboard', { headers: { Host: 'localhost:3000' } });
  assert.equal(signedOutDashboard.status, 200);
  assert.match(await signedOutDashboard.text(), /Sign in to continue/u);

  const removedPages = await Promise.all(
    ['/sign-in', '/link'].map((path) => app.request(path, { headers: { Host: 'localhost:3000' } })),
  );
  for (const removed of removedPages) {
    assert.equal(removed.status, 404);
  }

  const invite = await app.request('/invite', { headers: { Host: 'localhost:3000' } });
  assert.equal(invite.status, 200);
  assert.match(await invite.text(), /Checking your link/u);
  assert.equal(invite.headers.get('Cache-Control'), 'no-store');
  assert.equal(invite.headers.get('Content-Security-Policy')?.includes("frame-ancestors 'none'"), true);
  assert.equal(
    invite.headers.get('Permissions-Policy'),
    'camera=(), geolocation=(), microphone=(), payment=(), publickey-credentials-get=(self), usb=()',
  );
  assert.equal(invite.headers.get('Referrer-Policy'), 'no-referrer');
  assert.equal(invite.headers.get('Strict-Transport-Security'), null);
  assert.equal(invite.headers.get('X-Content-Type-Options'), null);
  assert.equal(invite.headers.get('X-Frame-Options'), 'DENY');

  const malformedFailures = await Promise.all(
    [
      { body: { kind: 'invitation', token: 'short' }, expectedError: 'The request is invalid.' },
      {
        body: { kind: 'recovery', token: 'x'.repeat(20), username: 'ignored' },
        expectedError: 'The request is invalid.',
      },
      {
        body: { kind: 'invitation', token: 'x'.repeat(20) },
        expectedError: 'Invalid invitation.',
      },
    ].map(async ({ body, expectedError }) => {
      const response = await app.request('/api/onboarding/exchange', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Host: 'localhost:3000',
          Origin: config.publicOrigin,
        },
        body: JSON.stringify(body),
      });
      return { status: response.status, body: await response.json(), expectedError };
    }),
  );
  for (const failure of malformedFailures) {
    assert.equal(failure.status, 400);
    assert.deepEqual(failure.body, { error: failure.expectedError });
  }

  const asset = await app.request('/assets/app.js', { headers: { Host: 'localhost:3000' } });
  assert.equal(asset.status, 200);
  assert.equal(asset.headers.get('Cache-Control'), 'no-cache');
  assert.match(asset.headers.get('Content-Type') ?? '', /javascript/u);

  const stylesheet = await app.request('/assets/app.css', { headers: { Host: 'localhost:3000' } });
  assert.equal(stylesheet.status, 200);
  assert.equal(stylesheet.headers.get('Cache-Control'), 'no-cache');
  assert.match(stylesheet.headers.get('Content-Type') ?? '', /text\/css/u);
  const stylesheetBody = await stylesheet.text();
  const fontPath = /url\((\/assets\/[^)]+\.woff2)\)/u.exec(stylesheetBody)?.[1];
  assert.ok(fontPath !== undefined);
  const font = await app.request(fontPath, { headers: { Host: 'localhost:3000' } });
  assert.equal(font.status, 200);
  assert.equal(font.headers.get('Cache-Control'), 'public, max-age=31536000, immutable');
  assert.match(font.headers.get('Content-Type') ?? '', /font\/woff2/u);

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

  const publicConfig = { ...config, publicOrigin: 'https://dirt.example' };
  const publicApp = testWebApp(auth, publicConfig);
  const loopbackJwks = await publicApp.request('/api/auth/jwks', {
    headers: { Host: '127.0.0.1:3000' },
  });
  assert.equal(loopbackJwks.status, 200);
  assert.equal(loopbackJwks.headers.get('Strict-Transport-Security'), null);
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
    bridge: readyBridge,
    config: publicConfig,
    repository,
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

test('the public gateway preserves OAuth and Minecraft-link continuations', async () => {
  const now = new Date();
  insertUser('navigation-user', 'navigator', now);
  database
    .prepare('UPDATE "user" SET name = ?, minecraftUuid = ? WHERE id = ?')
    .run('Navigator', '12121212-1212-4121-8121-121212121212', 'navigation-user');
  let sessionCreatedAt = new Date();
  const app = testWebApp(sessionAuth('navigation-user', () => sessionCreatedAt));
  const headers = { Host: 'localhost:3000' };

  const signedInHome = await app.request('/', { headers });
  assert.equal(signedInHome.status, 303);
  assert.equal(signedInHome.headers.get('Location'), '/dashboard');

  const oauthQuery = '?client_id=https%3A%2F%2Ffield.example%2Fclient.json&scope=dirt%3Amcp';
  const freshContinuation = await app.request(`/${oauthQuery}`, { headers });
  assert.equal(freshContinuation.status, 303);
  assert.equal(freshContinuation.headers.get('Location'), `/api/auth/oauth2/authorize${oauthQuery}`);

  sessionCreatedAt = new Date(Date.now() - 6 * 60 * 1_000);
  const staleContinuation = await app.request(`/${oauthQuery}`, { headers });
  assert.equal(staleContinuation.status, 200);
  assert.match(await staleContinuation.text(), /Sign in with a passkey/u);
});

test('dashboard presents safe identity, readiness, client, and passkey summaries', async () => {
  const now = new Date('2026-08-31T12:00:00.000Z');
  insertUser('dashboard-user', 'fieldworker', now);
  database
    .prepare('UPDATE "user" SET name = ?, minecraftUuid = ? WHERE id = ?')
    .run('FieldWorker', '34343434-3434-4343-8343-343434343434', 'dashboard-user');
  database
    .prepare(
      'INSERT INTO passkey (id, name, publicKey, userId, credentialID, counter, deviceType, backedUp, createdAt) VALUES (?, ?, ?, ?, ?, 0, ?, 1, ?)',
    )
    .run(
      'dashboard-passkey',
      'Workshop key',
      'private-public-key-material',
      'dashboard-user',
      'private-credential-id',
      'multiDevice',
      now.toISOString(),
    );
  const clientId = 'https://client-dashboard.example/client.json';
  database
    .prepare('INSERT INTO oauthClient (id, clientId, name, redirectUris) VALUES (?, ?, ?, ?)')
    .run(
      'dashboard-client',
      clientId,
      '<script>Survey client</script>',
      JSON.stringify(['https://client-dashboard.example/callback']),
    );
  database
    .prepare('INSERT INTO oauthConsent (id, clientId, userId, scopes, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)')
    .run(
      'dashboard-consent',
      clientId,
      'dashboard-user',
      JSON.stringify(['dirt:mcp']),
      now.toISOString(),
      now.toISOString(),
    );

  const app = testWebApp(sessionAuth('dashboard-user', () => new Date()));
  const response = await app.request('/dashboard', { headers: { Host: 'localhost:3000' } });
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /Dirt is ready/u);
  assert.match(html, /Paper and FAWE online/u);
  assert.match(html, /bounded, undoable edits/u);
  assert.match(html, /12 of 12/u);
  assert.match(html, /FieldWorker/u);
  assert.match(html, /34343434-3434-4343-8343-343434343434/u);
  assert.match(html, /http:\/\/localhost:3000\/mcp/u);
  assert.match(html, /&lt;script&gt;Survey client&lt;\/script&gt;/u);
  assert.doesNotMatch(html, /<script>Survey client<\/script>/u);
  assert.match(html, /client-dashboard\.example/u);
  assert.match(html, /dirt:mcp/u);
  assert.match(html, /Full Dirt access/u);
  assert.match(html, /Disconnect/u);
  assert.match(html, /data-disconnect-client="dashboard-consent"/u);
  assert.match(html, /Workshop key/u);
  assert.doesNotMatch(html, /private-public-key-material/u);
  assert.doesNotMatch(html, /private-credential-id/u);

  const zeroTools = await testWebApp(
    sessionAuth('dashboard-user', () => new Date()),
    config,
    testBridge([]),
  ).request('/dashboard', { headers: { Host: 'localhost:3000' } });
  const zeroToolsHtml = await zeroTools.text();
  assert.match(zeroToolsHtml, /No tools are enabled/u);
  assert.match(zeroToolsHtml, /0 of 12/u);

  const degraded = await testWebApp(
    sessionAuth('dashboard-user', () => new Date()),
    config,
    testBridge(BRIDGE_OPERATION_IDS, false),
  ).request('/dashboard', { headers: { Host: 'localhost:3000' } });
  const degradedHtml = await degraded.text();
  assert.equal(degraded.status, 200);
  assert.match(degradedHtml, /Paper offline/u);
  assert.match(degradedHtml, /Account controls remain available/u);

  insertUser('unlinked-dashboard-user', 'unlinkeduser', now);
  const unlinked = await testWebApp(sessionAuth('unlinked-dashboard-user', () => new Date())).request('/dashboard', {
    headers: { Host: 'localhost:3000' },
  });
  const unlinkedHtml = await unlinked.text();
  assert.match(unlinkedHtml, /Link Minecraft again/u);
  assert.match(unlinkedHtml, /Link code/u);
  assert.match(unlinkedHtml, /No clients have been authorized/u);
});

test('onboarding exchanges invitation and recovery secrets, and fresh sessions link Minecraft', async () => {
  const now = new Date();
  const app = testWebApp();
  const headers = {
    'Content-Type': 'application/json',
    Host: 'localhost:3000',
    Origin: config.publicOrigin,
  };

  const invitation = repository.createInvitation('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'RoutePlayer', now);
  const invitationExchange = await app.request('/api/onboarding/exchange', {
    method: 'POST',
    headers,
    body: JSON.stringify({ kind: 'invitation', token: invitation.secret }),
  });
  assert.equal(invitationExchange.status, 200);
  assert.equal(invitationExchange.headers.get('Cache-Control'), 'no-store');
  assert.deepEqual(await invitationExchange.json(), { ok: true, username: 'RoutePlayer' });
  const invitationCookie = invitationExchange.headers.get('Set-Cookie')?.split(';', 1)[0];
  assert.ok(invitationCookie !== undefined);
  assert.match(
    invitationExchange.headers.get('Set-Cookie') ?? '',
    /^dirt-onboarding=[^;]+; Path=\/; HttpOnly; SameSite=Strict; Max-Age=600$/u,
  );
  const preparedInvitation = await app.request('/invite', {
    headers: { Cookie: invitationCookie, Host: 'localhost:3000' },
  });
  const preparedInvitationHtml = await preparedInvitation.text();
  assert.match(preparedInvitationHtml, /data-onboarding-ready/u);
  assert.match(preparedInvitationHtml, /Verified Minecraft account/u);
  assert.match(preparedInvitationHtml, /RoutePlayer/u);
  assert.doesNotMatch(preparedInvitationHtml, /name="username"/u);

  insertUser('route-user', 'routeuser', now);
  const recovery = repository.createRecovery('routeuser', now);
  const recoveryExchange = await app.request('/api/onboarding/exchange', {
    method: 'POST',
    headers,
    body: JSON.stringify({ kind: 'recovery', token: recovery.secret }),
  });
  assert.equal(recoveryExchange.status, 200);
  assert.deepEqual(await recoveryExchange.json(), { ok: true, username: 'routeuser' });

  const challenge = repository.createMinecraftLinkChallenge('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'RoutePlayer', now);
  let sessionCreatedAt = new Date(now.getTime() - 6 * 60 * 1_000);
  const linkApp = testWebApp(sessionAuth('route-user', () => sessionCreatedAt));
  const linkRequest = {
    method: 'POST',
    headers,
    body: JSON.stringify({ code: challenge.code }),
  } as const;
  assert.equal((await linkApp.request('/api/access/minecraft-link', linkRequest)).status, 403);

  sessionCreatedAt = new Date();
  const linked = await linkApp.request('/api/access/minecraft-link', linkRequest);
  assert.equal(linked.status, 200);
  const linkedUser = (await linked.json()).user;
  assert.equal(linkedUser.minecraftUuid, 'dddddddd-dddd-4ddd-8ddd-dddddddddddd');
  assert.equal(linkedUser.username, 'RoutePlayer');
});

test('signed-in users can disconnect an authorized MCP client', async () => {
  const now = new Date('2026-08-31T13:00:00.000Z');
  insertUser('route-revocation-user', 'routerevoker', now);
  insertUser('foreign-route-revocation-user', 'foreignrevoker', now);
  const selected = insertAuthorizationArtifacts('route-revocation-user', 'route-revocation', now);
  const foreign = insertAuthorizationArtifacts('foreign-route-revocation-user', 'foreign-route-revocation', now);
  const headers = {
    'Content-Type': 'application/json',
    Host: 'localhost:3000',
    Origin: config.publicOrigin,
  };
  const request = {
    method: 'POST',
    headers,
    body: JSON.stringify({ consentId: selected.consentId }),
  } as const;

  const signedOut = await testWebApp().request('/api/access/mcp-clients/revoke', request);
  assert.equal(signedOut.status, 401);
  assert.deepEqual(await signedOut.json(), { error: 'Sign in again before disconnecting this client.' });

  const crossOrigin = await testWebApp(sessionAuth('route-revocation-user', () => new Date())).request(
    '/api/access/mcp-clients/revoke',
    { ...request, headers: { ...headers, Origin: 'https://attacker.example' } },
  );
  assert.equal(crossOrigin.status, 403);

  const malformed = await testWebApp(sessionAuth('route-revocation-user', () => new Date())).request(
    '/api/access/mcp-clients/revoke',
    { ...request, body: JSON.stringify({ consentId: selected.consentId, unexpected: true }) },
  );
  assert.equal(malformed.status, 400);

  const app = testWebApp(sessionAuth('route-revocation-user', () => new Date(Date.now() - 6 * 60 * 1_000)));
  const foreignResponse = await app.request('/api/access/mcp-clients/revoke', {
    ...request,
    body: JSON.stringify({ consentId: foreign.consentId }),
  });
  assert.equal(foreignResponse.status, 404);
  const response = await app.request('/api/access/mcp-clients/revoke', request);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true });
  assert.equal(repository.hasMcpClientConsent('route-revocation-user', selected.clientId), false);

  const repeated = await app.request('/api/access/mcp-clients/revoke', request);
  assert.equal(repeated.status, 404);
  assert.deepEqual(await repeated.json(), { error: 'Authorized MCP client not found.' });
});

test('OAuth grants require a fresh passkey session and a linked account', async () => {
  const now = new Date();
  insertUser('oauth-user', 'oauthuser', now);
  database
    .prepare('UPDATE "user" SET name = ?, minecraftUuid = ? WHERE id = ?')
    .run('OAuthPlayer', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'oauth-user');
  database
    .prepare('INSERT INTO oauthClient (id, clientId, name, redirectUris) VALUES (?, ?, ?, ?)')
    .run(
      'oauth-page-client',
      'https://client.example/client.json',
      'Map Room',
      JSON.stringify(['https://client.example/callback']),
    );
  let sessionCreatedAt = new Date(Date.now() - 6 * 60 * 1_000);
  const app = testWebApp(sessionAuth('oauth-user', () => sessionCreatedAt));
  const oauthQuery =
    '?client_id=https%3A%2F%2Fclient.example%2Fclient.json&redirect_uri=https%3A%2F%2Fclient.example%2Fcallback&response_type=code&scope=dirt%3Amcp&state=test';
  const browserHeaders = { Host: 'localhost:3000' };

  const staleConsentPage = await app.request(`/consent${oauthQuery}`, { headers: browserHeaders });
  assert.equal(staleConsentPage.status, 303);
  assert.equal(staleConsentPage.headers.get('Location'), `/${oauthQuery}`);
  const staleAuthorize = await app.request(`/api/auth/oauth2/authorize${oauthQuery}`, { headers: browserHeaders });
  assert.equal(staleAuthorize.status, 303);
  assert.equal(staleAuthorize.headers.get('Location'), `/${oauthQuery}`);
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
  assert.equal(normalizedAuthorize.headers.get('Location'), `/${oauthQuery}`);
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

  const knownConsentPage = await app.request(`/consent${oauthQuery}`, { headers: browserHeaders });
  assert.equal(knownConsentPage.status, 200);
  const knownConsentHtml = await knownConsentPage.text();
  assert.match(knownConsentHtml, /Allow Map Room/u);
  assert.match(knownConsentHtml, /client\.example/u);
  assert.match(knownConsentHtml, /run_minecraft_commands/u);
  assert.match(knownConsentHtml, /console-equivalent authority/u);

  const localhostConsent = await app.request(
    '/consent?client_id=https%3A%2F%2Fclient.example%2Fclient.json&redirect_uri=http%3A%2F%2Flocalhost%3A3456%2Fcallback&scope=dirt%3Amcp',
    { headers: browserHeaders },
  );
  assert.equal(localhostConsent.status, 200);
  assert.match(await localhostConsent.text(), /returns to an app on your device/u);

  repository.unlinkUser('OAuthPlayer');
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

test('access-token parsing accepts Bearer and DPoP token68 credentials', () => {
  assert.equal(extractAccessToken('Bearer access.token'), 'access.token');
  assert.equal(extractAccessToken('bearer access.token'), 'access.token');
  assert.equal(extractAccessToken('DPoP access.token'), 'access.token');
  assert.equal(extractAccessToken('dpop  abc_123+/=='), 'abc_123+/==');
  for (const invalid of [
    null,
    '',
    'Basic access.token',
    'Bearer',
    'Bearer\taccess.token',
    'Bearer one two',
    'Bearer access:token',
    'DPoP access=token',
  ]) {
    assert.equal(extractAccessToken(invalid), null);
  }
});

function insertUser(id: string, username: string, now: Date): void {
  database
    .prepare(
      'INSERT INTO "user" (id, name, email, emailVerified, image, createdAt, updatedAt, status, minecraftUuid, authorizationVersion) VALUES (?, ?, ?, 0, NULL, ?, ?, ?, NULL, 0)',
    )
    .run(id, username, `${id}@dirt.placeholder.invalid`, now.toISOString(), now.toISOString(), 'active');
}

function insertAuthorizationArtifacts(
  userId: string,
  suffix: string,
  now: Date,
  clientId = `https://client-${suffix}.example/client.json`,
): { readonly clientId: string; readonly consentId: string } {
  const sessionId = `session-${suffix}`;
  const refreshId = `refresh-${suffix}`;
  const consentId = `consent-${suffix}`;
  const createdAt = now.toISOString();
  const expiresAt = new Date(now.getTime() + 60_000).toISOString();
  database
    .prepare('INSERT OR IGNORE INTO oauthClient (id, clientId, redirectUris) VALUES (?, ?, ?)')
    .run(`client-row-${suffix}`, clientId, JSON.stringify([`https://client-${suffix}.example/callback`]));
  database
    .prepare('INSERT INTO session (id, expiresAt, token, createdAt, updatedAt, userId) VALUES (?, ?, ?, ?, ?, ?)')
    .run(sessionId, expiresAt, `session-token-${suffix}`, createdAt, createdAt, userId);
  database
    .prepare(
      'INSERT INTO verification (id, identifier, value, expiresAt, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)',
    )
    .run(
      `verification-${suffix}`,
      `authorization-code-${suffix}`,
      JSON.stringify({ type: 'authorization_code', userId, query: { client_id: clientId } }),
      expiresAt,
      createdAt,
      createdAt,
    );
  database
    .prepare('INSERT INTO oauthConsent (id, clientId, userId, scopes, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)')
    .run(consentId, clientId, userId, JSON.stringify(['dirt:mcp']), createdAt, createdAt);
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
      expiresAt,
      createdAt,
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
      expiresAt,
      createdAt,
      JSON.stringify(['dirt:mcp']),
    );
  if (!hasPasskey(userId)) {
    database
      .prepare(
        'INSERT INTO passkey (id, name, publicKey, userId, credentialID, counter, deviceType, backedUp, createdAt) VALUES (?, ?, ?, ?, ?, 0, ?, 0, ?)',
      )
      .run(
        `passkey-${suffix}`,
        'Primary passkey',
        'public-key',
        userId,
        `credential-${suffix}`,
        'singleDevice',
        createdAt,
      );
  }
  return { clientId, consentId };
}

function hasPasskey(userId: string): boolean {
  return (
    database
      .prepare<[string], { found: number }>('SELECT 1 AS found FROM passkey WHERE userId = ? LIMIT 1')
      .get(userId) !== undefined
  );
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

function pairArtifactCount(userId: string, clientId: string): number {
  const queries = [
    'SELECT COUNT(*) AS count FROM oauthConsent WHERE userId = ? AND clientId = ?',
    'SELECT COUNT(*) AS count FROM oauthAccessToken WHERE userId = ? AND clientId = ?',
    'SELECT COUNT(*) AS count FROM oauthRefreshToken WHERE userId = ? AND clientId = ?',
    `SELECT COUNT(*) AS count FROM verification
     WHERE json_valid(value)
       AND json_extract(value, '$.type') = 'authorization_code'
       AND json_extract(value, '$.userId') = ?
       AND json_extract(value, '$.query.client_id') = ?`,
  ];
  return queries.reduce((total, query) => {
    const row = database.prepare<[string, string], { count: number }>(query).get(userId, clientId);
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

function testWebApp(
  appAuth: Parameters<typeof createWebApp>[0]['auth'] = auth,
  appConfig: RuntimeConfig = config,
  appBridge: BridgeClient = readyBridge,
) {
  return createWebApp({
    auth: appAuth,
    bridge: appBridge,
    config: appConfig,
    repository,
    isLoopback: () => true,
    logger: silentLogger,
    mcp: { fetch: async () => new Response(null, { status: 501 }), close: async () => {} },
  });
}

function testBridge(operations: readonly string[], pingAvailable = true): BridgeClient {
  return new BridgeClient(config.bridge, async (request) => {
    const input = request instanceof Request ? request.url : request.toString();
    const path = new URL(input).pathname;
    if (path === '/v1/ping' && !pingAvailable) return Response.json({ unavailable: true }, { status: 503 });
    return Response.json(path === '/v1/ping' ? { status: 'ok' } : { operations });
  });
}

const silentLogger: DirtLogger = {
  child() {
    return this;
  },
  info() {},
  warning() {},
  error() {},
};
