import assert from 'node:assert/strict';
import test from 'node:test';
import * as z from 'zod/v4';
import { BridgeClient } from '../dist/bridge/client.js';
import {
  BRIDGE_ERROR_CODES,
  BRIDGE_ROUTES,
  BridgeCapabilitiesSchema,
  BridgeErrorResponseSchema,
} from '../dist/bridge/contract.js';
import { ToolFailure } from '../dist/bridge/errors.js';

const ResponseSchema = z.object({ value: z.string() }).strict();
const CALL_ID = '11111111-1111-4111-8111-111111111111';
const EDIT_ID = '22222222-2222-4222-8222-222222222222';

function client(fetchImplementation: typeof fetch): BridgeClient {
  return new BridgeClient({ origin: 'http://127.0.0.1:8765', token: 'test-token' }, fetchImplementation);
}

function invalidRequest(details: Record<string, unknown>) {
  return { error: { code: 'invalid_request', message: 'Invalid', details } };
}

test('defines the mandatory capability route and stable operation IDs', () => {
  assert.deepEqual(BRIDGE_ROUTES.capabilities, {
    method: 'GET',
    path: '/v1/capabilities',
    timeoutMilliseconds: 3_000,
  });
  assert.equal(BRIDGE_ROUTES.serverStatus.method, 'POST');
  assert.equal(BRIDGE_ROUTES.replaceRegionBlocks.timeoutMilliseconds, 300_000);
  assert.equal(BRIDGE_ROUTES.runMinecraftCommands.timeoutMilliseconds, 120_000);
  assert.equal(BridgeCapabilitiesSchema.safeParse({ operations: ['pingServer', 'getBlocks'] }).success, true);
  assert.equal(BridgeCapabilitiesSchema.safeParse({ operations: [] }).success, true);
  assert.equal(BridgeCapabilitiesSchema.safeParse({ operations: ['pingServer', 'pingServer'] }).success, false);
  assert.equal(BridgeCapabilitiesSchema.safeParse({ operations: ['getCapabilities'] }).success, false);
});

test('sends authenticated POST requests with exact JSON and a call ID', async () => {
  let url: URL | undefined;
  let init: RequestInit | undefined;
  const bridge = client(async (input, requestInit) => {
    url = input instanceof URL ? input : new URL(String(input));
    init = requestInit;
    return Response.json({ value: 'ok' });
  });

  assert.deepEqual(await bridge.request(BRIDGE_ROUTES.getBlocks, CALL_ID, ResponseSchema, { world: 'world' }), {
    value: 'ok',
  });
  assert.equal(url?.href, 'http://127.0.0.1:8765/v1/get-blocks');
  assert.equal(init?.method, 'POST');
  assert.equal(init?.redirect, 'error');
  assert.equal(init?.body, JSON.stringify({ world: 'world' }));
  assert.ok(init?.signal instanceof AbortSignal);
  const headers = new Headers(init?.headers);
  assert.equal(headers.get('Accept'), 'application/json');
  assert.equal(headers.get('Authorization'), 'Bearer test-token');
  assert.equal(headers.get('Content-Type'), 'application/json');
  assert.equal(headers.get('X-Dirt-Call-Id'), CALL_ID);
});

test('sends no body or content type for authenticated GET requests', async () => {
  let init: RequestInit | undefined;
  const bridge = client(async (_input, requestInit) => {
    init = requestInit;
    return Response.json({ value: 'ok' });
  });
  await bridge.request(BRIDGE_ROUTES.capabilities, CALL_ID, ResponseSchema);
  assert.equal(init?.body, undefined);
  assert.equal(new Headers(init?.headers).has('Content-Type'), false);
  assert.equal(new Headers(init?.headers).get('X-Dirt-Call-Id'), CALL_ID);
});

test('preserves only contract-shaped bridge errors', async () => {
  const failure = {
    code: 'world_unavailable',
    message: 'World stopped during mutation.',
    details: { reason: 'world_unloaded', world: 'world' },
    editId: EDIT_ID,
  } as const;
  const bridge = client(async () => Response.json({ error: failure }, { status: 503 }));
  await assert.rejects(bridge.request(BRIDGE_ROUTES.setBlocks, CALL_ID, ResponseSchema, {}), (error: unknown) => {
    if (!(error instanceof ToolFailure) || error.code !== 'world_unavailable') return false;
    assert.deepEqual(error.data, failure);
    return error.editId === EDIT_ID;
  });

  const malformed = client(async () =>
    Response.json({ error: { code: 'invented', message: 'secret', editId: EDIT_ID } }, { status: 500 }),
  );
  await assert.rejects(malformed.request(BRIDGE_ROUTES.setBlocks, CALL_ID, ResponseSchema, {}), (error: unknown) => {
    return error instanceof ToolFailure && error.code === 'bridge_http_error' && error.editId === undefined;
  });

  const wrongStatus = client(async () => Response.json({ error: failure }, { status: 409 }));
  await assert.rejects(wrongStatus.request(BRIDGE_ROUTES.setBlocks, CALL_ID, ResponseSchema, {}), (error: unknown) => {
    return error instanceof ToolFailure && error.code === 'bridge_http_error' && error.editId === undefined;
  });
});

test('maps authorization and unstructured HTTP failures to sanitized local failures', async () => {
  const unauthorized = client(
    async () => new Response('token value', { status: 401, headers: { 'Content-Type': 'text/plain' } }),
  );
  await assert.rejects(unauthorized.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    if (!(error instanceof ToolFailure) || error.data.code !== 'bridge_unauthorized') return false;
    assert.deepEqual(error.data.details, { reason: 'authentication_failed' });
    return !error.message.includes('token value');
  });

  const unstructured = client(async () => Response.json({ unexpected: true }, { status: 429 }));
  await assert.rejects(unstructured.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    if (!(error instanceof ToolFailure) || error.data.code !== 'bridge_http_error') return false;
    assert.deepEqual(error.data.details, { status: 429 });
    return true;
  });
});

test('requires exact HTTP 200 and an application/json response media type', async () => {
  const unexpectedSuccess = client(async () => Response.json({ value: 'ok' }, { status: 201 }));
  await assert.rejects(unexpectedSuccess.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    return error instanceof ToolFailure && error.data.code === 'bridge_http_error' && error.data.details.status === 201;
  });

  const wrongSuccessMediaType = client(
    async () => new Response('{"value":"ok"}', { headers: { 'Content-Type': 'text/plain' } }),
  );
  await assert.rejects(
    wrongSuccessMediaType.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema),
    (error: unknown) => error instanceof ToolFailure && error.code === 'bridge_invalid_response',
  );

  const parameterizedMediaType = client(
    async () => new Response('{"value":"ok"}', { headers: { 'Content-Type': 'Application/JSON; Charset=UTF-8' } }),
  );
  assert.deepEqual(await parameterizedMediaType.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), { value: 'ok' });

  const wrongErrorMediaType = client(
    async () => new Response('{"error":{}}', { status: 503, headers: { 'Content-Type': 'text/plain' } }),
  );
  await assert.rejects(wrongErrorMediaType.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    return error instanceof ToolFailure && error.data.code === 'bridge_http_error' && error.data.details.status === 503;
  });
});

test('cancels unread response bodies on errors that do not inspect them', async () => {
  await Promise.all(
    [
      { status: 401, contentType: 'application/json', expectedCode: 'bridge_unauthorized' },
      { status: 503, contentType: 'text/plain', expectedCode: 'bridge_http_error' },
      { status: 200, contentType: 'text/plain', expectedCode: 'bridge_invalid_response' },
    ].map(async ({ status, contentType, expectedCode }) => {
      let cancelled = false;
      const bridge = client(
        async () =>
          new Response(
            new ReadableStream({
              cancel() {
                cancelled = true;
              },
            }),
            { status, headers: { 'Content-Type': contentType } },
          ),
      );

      await assert.rejects(bridge.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
        return error instanceof ToolFailure && error.code === expectedCode;
      });
      assert.equal(cancelled, true, `expected the HTTP ${status} ${contentType} body to be cancelled`);
    }),
  );
});

test('bounds JSON responses and cancels the remaining body without exposing its content', async () => {
  let declaredBodyCancelled = false;
  const declaredOversize = client(
    async () =>
      new Response(
        new ReadableStream({
          cancel() {
            declaredBodyCancelled = true;
          },
        }),
        {
          headers: {
            'Content-Length': String(8 * 1_024 * 1_024 + 1),
            'Content-Type': 'application/json',
          },
        },
      ),
  );
  await assert.rejects(declaredOversize.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    return (
      error instanceof ToolFailure && error.code === 'bridge_invalid_response' && !error.message.includes('secret')
    );
  });
  assert.equal(declaredBodyCancelled, true);

  let streamedBodyCancelled = false;
  const streamedOversize = client(
    async () =>
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(new TextEncoder().encode(`{"error":{"code":"internal_error","message":"secret"}}`));
            controller.enqueue(new Uint8Array(8 * 1_024 * 1_024));
          },
          cancel() {
            streamedBodyCancelled = true;
          },
        }),
        { status: 500, headers: { 'Content-Type': 'application/json' } },
      ),
  );
  await assert.rejects(streamedOversize.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    return error instanceof ToolFailure && error.code === 'bridge_http_error' && !error.message.includes('secret');
  });
  assert.equal(streamedBodyCancelled, true);
});

test('rejects malformed JSON, invalid UTF-8, and structurally invalid successful responses without edit salvage', async () => {
  const malformedJson = client(async () => new Response('{', { headers: { 'Content-Type': 'application/json' } }));
  await assert.rejects(
    malformedJson.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema),
    (error: unknown) => error instanceof ToolFailure && error.code === 'bridge_invalid_response',
  );

  const invalidUtf8 = client(
    async () =>
      new Response(new Uint8Array([123, 34, 118, 97, 108, 117, 101, 34, 58, 34, 255, 34, 125]), {
        headers: { 'Content-Type': 'application/json' },
      }),
  );
  await assert.rejects(
    invalidUtf8.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema),
    (error: unknown) => error instanceof ToolFailure && error.code === 'bridge_invalid_response',
  );

  const malformedShape = client(async () => Response.json({ edit: { editId: EDIT_ID } }));
  await assert.rejects(
    malformedShape.request(BRIDGE_ROUTES.setBlocks, CALL_ID, ResponseSchema, {}),
    (error: unknown) =>
      error instanceof ToolFailure && error.code === 'bridge_invalid_response' && error.editId === undefined,
  );

  const malformedFailure = client(
    async () => new Response('{', { status: 500, headers: { 'Content-Type': 'application/json' } }),
  );
  await assert.rejects(
    malformedFailure.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema),
    (error: unknown) => error instanceof ToolFailure && error.code === 'bridge_http_error',
  );
});

test('classifies and sanitizes transport failure, timeout, and caller cancellation', async () => {
  const requestFailure = client(async () => {
    throw new TypeError('ECONNREFUSED with sensitive socket text');
  });
  await assert.rejects(requestFailure.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    if (!(error instanceof ToolFailure) || error.data.code !== 'bridge_unavailable') return false;
    assert.deepEqual(error.data.details, { reason: 'request_failed' });
    return !error.message.includes('ECONNREFUSED');
  });

  const timedOut = client(async () => {
    throw new DOMException('internal timeout detail', 'TimeoutError');
  });
  await assert.rejects(timedOut.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    return (
      error instanceof ToolFailure &&
      error.data.code === 'bridge_unavailable' &&
      error.data.details.reason === 'timeout'
    );
  });

  const cancellation = new AbortController();
  cancellation.abort();
  const cancelled = client(async () => {
    throw new DOMException('caller detail', 'AbortError');
  });
  await assert.rejects(
    cancelled.request(BRIDGE_ROUTES.setBlocks, CALL_ID, ResponseSchema, {}, cancellation.signal),
    (error: unknown) =>
      error instanceof ToolFailure &&
      error.data.code === 'bridge_unavailable' &&
      error.data.details.reason === 'cancelled',
  );
});

test('classifies response-body transport failures', async () => {
  const bridge = client(
    async () =>
      new Response(
        new ReadableStream({
          start(controller) {
            controller.error(new TypeError('socket closed'));
          },
        }),
        { headers: { 'Content-Type': 'application/json' } },
      ),
  );
  await assert.rejects(bridge.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    return (
      error instanceof ToolFailure &&
      error.data.code === 'bridge_unavailable' &&
      error.data.details.reason === 'request_failed'
    );
  });
});

test('accepts one strict shape for every bridge error code', () => {
  const errors: readonly Record<string, unknown>[] = [
    { code: 'bridge_busy', message: 'Busy', details: { maximumConcurrentRequests: 8 } },
    { code: 'change_limit_exceeded', message: 'Limit', details: { maximum: 10 } },
    { code: 'edit_not_found', message: 'Missing', details: { world: 'world', requestedEditId: EDIT_ID } },
    {
      code: 'edit_not_latest',
      message: 'Not latest',
      details: { world: 'world', requestedEditId: EDIT_ID, newestEditId: CALL_ID },
    },
    { code: 'history_capacity_exceeded', message: 'Full', details: { reason: 'entries_total', maximum: 10 } },
    { code: 'internal_error', message: 'Internal' },
    { code: 'invalid_request', message: 'Invalid', details: { reason: 'missing', field: 'world' } },
    { code: 'method_not_allowed', message: 'Method', details: { allowedMethod: 'POST' } },
    { code: 'operation_disabled', message: 'Disabled', details: { operationId: 'getBlocks' } },
    { code: 'player_not_found', message: 'Missing', details: { player: 'Builder' } },
    {
      code: 'player_unavailable',
      message: 'Unavailable',
      details: { reason: 'spectating_entity', player: 'Builder' },
    },
    {
      code: 'region_too_large',
      message: 'Large',
      details: { reason: 'volume', dimensions: { x: 2, y: 3, z: 4 }, maximum: 10 },
    },
    {
      code: 'result_too_large',
      message: 'Large',
      details: { reason: 'structure_entries', minimumRequired: 11, maximum: 10 },
    },
    { code: 'route_not_found', message: 'Missing', details: { reason: 'route_not_found' } },
    { code: 'server_unavailable', message: 'Unavailable', details: { reason: 'paper_unavailable' } },
    { code: 'unauthorized', message: 'Unauthorized', details: { reason: 'authentication_failed' } },
    { code: 'unhealthy', message: 'Unhealthy', details: { reason: 'plugin_disabled' } },
    { code: 'world_busy', message: 'Busy', details: { reason: 'operation_in_progress', world: 'world' } },
    { code: 'world_not_found', message: 'Missing', details: { world: 'world' } },
    { code: 'world_unavailable', message: 'Stopped', details: { reason: 'stopping' }, editId: EDIT_ID },
  ];
  assert.deepEqual(
    errors.map((error) => error.code),
    [...BRIDGE_ERROR_CODES],
  );
  for (const error of errors) assert.equal(BridgeErrorResponseSchema.safeParse({ error }).success, true);
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: {
        code: 'world_busy',
        message: 'Busy',
        details: { reason: 'operation_in_progress', world: 'world' },
        editId: EDIT_ID,
      },
    }).success,
    false,
  );
});

test('enforces invalid-request detail invariants and mutation-only failure reasons', () => {
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: { code: 'player_not_found', message: 'Missing', details: { player: '🧱'.repeat(36) } },
    }).success,
    true,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: { code: 'player_not_found', message: 'Missing', details: { player: '🧱'.repeat(37) } },
    }).success,
    false,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse(
      invalidRequest({ reason: 'unsupported_value', target: 'mode', allowedValues: ['a', 'a'] }),
    ).success,
    false,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse(
      invalidRequest({ reason: 'too_many_items', fields: ['placements', 'placements'], maximum: 10 }),
    ).success,
    false,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse(
      invalidRequest({ reason: 'palette_weight_total', field: 'destinationPalette', requested: 100, required: 100 }),
    ).success,
    false,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse(
      invalidRequest({ reason: 'out_of_range', target: 'depth', value: 5, minimum: 0, maximum: 10 }),
    ).success,
    false,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: {
        code: 'region_too_large',
        message: 'Too large',
        details: { reason: 'volume', dimensions: { x: 2, y: 2, z: 2 }, maximum: 8 },
      },
    }).success,
    false,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: {
        code: 'result_too_large',
        message: 'Too large',
        details: { reason: 'structure_entries', minimumRequired: 10, maximum: 10 },
      },
    }).success,
    false,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: {
        code: 'edit_not_latest',
        message: 'Not latest',
        details: { world: 'world', requestedEditId: EDIT_ID, newestEditId: EDIT_ID.toUpperCase() },
      },
    }).success,
    false,
  );

  const operationFailure = {
    code: 'world_unavailable',
    message: 'Mutation failed.',
    details: { reason: 'operation_failed' },
  };
  assert.equal(BridgeErrorResponseSchema.safeParse({ error: operationFailure }).success, false);
  assert.equal(BridgeErrorResponseSchema.safeParse({ error: { ...operationFailure, editId: EDIT_ID } }).success, true);
});
