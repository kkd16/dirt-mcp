import assert from 'node:assert/strict';
import test from 'node:test';
import * as z from 'zod/v4';
import { BridgeClient } from '../dist/bridge/client.js';
import { BRIDGE_ROUTES, BridgeErrorResponseSchema } from '../dist/bridge/contract.js';
import { ToolFailure } from '../dist/bridge/errors.js';

const ResponseSchema = z.object({ value: z.string() }).strict();
const CALL_ID = '11111111-1111-4111-8111-111111111111';
const EDIT_ID = '22222222-2222-4222-8222-222222222222';

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
});
