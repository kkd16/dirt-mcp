import assert from 'node:assert/strict';
import test from 'node:test';
import * as z from 'zod/v4';
import { BridgeClient } from '../dist/bridge/client.js';
import { BRIDGE_ROUTES, BridgeErrorResponseSchema } from '../dist/bridge/contract.js';
import { ToolFailure } from '../dist/bridge/errors.js';

const ResponseSchema = z.object({ value: z.string() }).strict();
const CALL_ID = '11111111-1111-4111-8111-111111111111';
const EDIT_ID = '22222222-2222-4222-8222-222222222222';
const OTHER_EDIT_ID = '33333333-3333-4333-8333-333333333333';

test('sends an authenticated exact bridge request through the injected fetch function', async () => {
  let requestedUrl: URL | undefined;
  let requestedInit: RequestInit | undefined;
  const fetchImplementation: typeof fetch = async (input, init) => {
    requestedUrl = input instanceof URL ? input : new URL(String(input));
    requestedInit = init;
    return Response.json({ value: 'ok' });
  };
  const client = new BridgeClient({ origin: 'http://127.0.0.1:8765', token: 'test-token' }, fetchImplementation);

  const result = await client.request(BRIDGE_ROUTES.countRegionBlockStates, CALL_ID, ResponseSchema, {
    world: 'world',
  });

  assert.deepEqual(result, { value: 'ok' });
  assert.equal(requestedUrl?.href, 'http://127.0.0.1:8765/v1/count-region-block-states');
  assert.equal(requestedInit?.method, 'POST');
  assert.equal(requestedInit?.redirect, 'error');
  assert.equal(requestedInit?.body, JSON.stringify({ world: 'world' }));
  assert.ok(requestedInit?.signal instanceof AbortSignal);
  const headers = new Headers(requestedInit?.headers);
  assert.equal(headers.get('Accept'), 'application/json');
  assert.equal(headers.get('Authorization'), 'Bearer test-token');
  assert.equal(headers.get('Content-Type'), 'application/json');
  assert.equal(headers.get('X-Dirt-Call-Id'), CALL_ID);
});

test('does not send a body or content type for GET requests', async () => {
  let requestedInit: RequestInit | undefined;
  const client = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async (_input, init) => {
    requestedInit = init;
    return Response.json({ value: 'ok' });
  });

  await client.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema);

  assert.equal(requestedInit?.body, undefined);
  assert.equal(new Headers(requestedInit?.headers).has('Content-Type'), false);
});

test('preserves structured bridge failures and classifies network failures', async () => {
  const structured = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () =>
    Response.json({ error: { code: 'world_busy', editId: EDIT_ID, message: 'World is busy' } }, { status: 409 }),
  );
  await assert.rejects(
    structured.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema),
    (error: unknown) =>
      error instanceof ToolFailure &&
      error.code === 'world_busy' &&
      error.editId === EDIT_ID &&
      error.message === 'World is busy',
  );

  const unavailable = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () => {
    throw new TypeError('connection refused');
  });
  await assert.rejects(
    unavailable.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema),
    (error: unknown) =>
      error instanceof ToolFailure &&
      error.code === 'bridge_unavailable' &&
      error.message === 'Paper bridge request failed: connection refused',
  );
});

test('accepts only optional UUIDv4 edit IDs in structured bridge failures', () => {
  assert.equal(
    BridgeErrorResponseSchema.safeParse({ error: { code: 'world_busy', message: 'World is busy' } }).success,
    true,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: { code: 'world_busy', editId: EDIT_ID, message: 'World is busy' },
    }).success,
    true,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: { code: 'world_busy', editId: '22222222-2222-1222-8222-222222222222', message: 'World is busy' },
    }).success,
    false,
  );
  assert.equal(BridgeErrorResponseSchema.safeParse({ error: { code: 'world_busy', message: '' } }).success, false);
});

test('salvages a valid edit ID from a malformed successful mutation response', async () => {
  const malformed = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () =>
    Response.json({ edit: { editId: EDIT_ID }, unexpected: true }),
  );

  await Promise.all(
    [BRIDGE_ROUTES.setBlocks, BRIDGE_ROUTES.undoEdit].map((route) =>
      assert.rejects(
        malformed.request(route, CALL_ID, ResponseSchema, { world: 'world' }),
        (error: unknown) =>
          error instanceof ToolFailure && error.code === 'bridge_invalid_response' && error.editId === EDIT_ID,
      ),
    ),
  );

  await assert.rejects(
    malformed.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema),
    (error: unknown) =>
      error instanceof ToolFailure && error.code === 'bridge_invalid_response' && error.editId === undefined,
  );
});

test('salvages a valid edit ID from a malformed mutation error response', async () => {
  const malformed = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () =>
    Response.json({ error: { editId: EDIT_ID, message: 'Malformed failure' } }, { status: 500 }),
  );

  await Promise.all(
    [BRIDGE_ROUTES.replaceRegionBlocks, BRIDGE_ROUTES.fillRegion, BRIDGE_ROUTES.setBlocks, BRIDGE_ROUTES.undoEdit].map(
      (route) =>
        assert.rejects(
          malformed.request(route, CALL_ID, ResponseSchema, { world: 'world' }),
          (error: unknown) =>
            error instanceof ToolFailure && error.code === 'bridge_http_error' && error.editId === EDIT_ID,
        ),
    ),
  );

  await assert.rejects(
    malformed.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema),
    (error: unknown) =>
      error instanceof ToolFailure && error.code === 'bridge_http_error' && error.editId === undefined,
  );

  const unauthorized = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () =>
    Response.json({ error: { editId: EDIT_ID } }, { status: 401 }),
  );
  await assert.rejects(
    unauthorized.request(BRIDGE_ROUTES.undoEdit, CALL_ID, ResponseSchema, { world: 'world' }),
    (error: unknown) =>
      error instanceof ToolFailure && error.code === 'bridge_unauthorized' && error.editId === EDIT_ID,
  );

  const invalidId = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () =>
    Response.json({ error: { editId: 'not-a-uuid' } }, { status: 500 }),
  );
  await assert.rejects(
    invalidId.request(BRIDGE_ROUTES.setBlocks, CALL_ID, ResponseSchema, { world: 'world' }),
    (error: unknown) =>
      error instanceof ToolFailure && error.code === 'bridge_http_error' && error.editId === undefined,
  );
});

test('salvages edit IDs from mutation envelopes with the wrong HTTP shape', async () => {
  const successWithError = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () =>
    Response.json({ error: { editId: EDIT_ID } }),
  );
  await assert.rejects(
    successWithError.request(BRIDGE_ROUTES.setBlocks, CALL_ID, ResponseSchema, { world: 'world' }),
    (error: unknown) =>
      error instanceof ToolFailure && error.code === 'bridge_invalid_response' && error.editId === EDIT_ID,
  );

  const failureWithEdit = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () =>
    Response.json({ edit: { editId: EDIT_ID } }, { status: 500 }),
  );
  await assert.rejects(
    failureWithEdit.request(BRIDGE_ROUTES.fillRegion, CALL_ID, ResponseSchema, { world: 'world' }),
    (error: unknown) => error instanceof ToolFailure && error.code === 'bridge_http_error' && error.editId === EDIT_ID,
  );
});

test('does not salvage an ambiguous edit ID from conflicting mutation envelopes', async () => {
  const conflicting = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () =>
    Response.json({ edit: { editId: EDIT_ID }, error: { editId: OTHER_EDIT_ID } }),
  );

  await assert.rejects(
    conflicting.request(BRIDGE_ROUTES.undoEdit, CALL_ID, ResponseSchema, { world: 'world' }),
    (error: unknown) =>
      error instanceof ToolFailure && error.code === 'bridge_invalid_response' && error.editId === undefined,
  );
});
