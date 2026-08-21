import assert from 'node:assert/strict';
import test from 'node:test';
import * as z from 'zod/v4';
import { BridgeClient } from '../dist/bridge/client.js';
import { BRIDGE_ERROR_CODES, BRIDGE_ROUTES, BridgeErrorResponseSchema } from '../dist/bridge/contract.js';
import { ToolFailure } from '../dist/bridge/errors.js';

const ResponseSchema = z.object({ value: z.string() }).strict();
const CALL_ID = '11111111-1111-4111-8111-111111111111';
const EDIT_ID = '22222222-2222-4222-8222-222222222222';
const OTHER_EDIT_ID = '33333333-3333-4333-8333-333333333333';
const INT32_MIN = -2_147_483_648;
const INT32_MAX = 2_147_483_647;

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
  const details = { reason: 'operation_in_progress', world: 'world' } as const;
  const structured = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () =>
    Response.json(
      { error: { code: 'world_busy', details, editId: EDIT_ID, message: 'World is busy' } },
      { status: 409 },
    ),
  );
  await assert.rejects(structured.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    if (!(error instanceof ToolFailure) || error.data.code !== 'world_busy') return false;
    assert.deepEqual(error.data.details, details);
    return error.editId === EDIT_ID && error.message === 'World is busy';
  });

  const unavailable = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () => {
    throw new TypeError('connection refused');
  });
  await assert.rejects(unavailable.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    if (!(error instanceof ToolFailure) || error.data.code !== 'bridge_unavailable') return false;
    assert.deepEqual(error.data.details, { reason: 'request_failed' });
    return error.message === 'Paper bridge request failed: connection refused';
  });
});

test('classifies fetch timeout failures without exposing transport objects', async () => {
  const client = new BridgeClient({ origin: 'http://127.0.0.1', token: 'token' }, async () => {
    throw new DOMException('timed out', 'TimeoutError');
  });

  await assert.rejects(client.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
    if (!(error instanceof ToolFailure) || error.data.code !== 'bridge_unavailable') return false;
    assert.deepEqual(error.data.details, { reason: 'timeout' });
    return true;
  });
});

test('classifies response-body transport failures for successful and failed HTTP responses', async () => {
  const cases = [
    { status: 200, error: new DOMException('timed out', 'TimeoutError'), reason: 'timeout' },
    { status: 503, error: new TypeError('socket closed'), reason: 'request_failed' },
  ] as const;

  await Promise.all(
    cases.map(async ({ status, error: streamError, reason: expectedReason }) => {
      const client = new BridgeClient(
        { origin: 'http://127.0.0.1', token: 'token' },
        async () =>
          new Response(
            new ReadableStream({
              start(controller) {
                controller.error(streamError);
              },
            }),
            { status },
          ),
      );

      await assert.rejects(client.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema), (error: unknown) => {
        if (!(error instanceof ToolFailure) || error.data.code !== 'bridge_unavailable') return false;
        assert.deepEqual(error.data.details, { reason: expectedReason });
        return true;
      });
    }),
  );
});

test('keeps malformed JSON classified by HTTP outcome', async () => {
  await Promise.all(
    [
      { status: 200, code: 'bridge_invalid_response' },
      { status: 503, code: 'bridge_http_error' },
    ].map(async ({ status, code }) => {
      const client = new BridgeClient(
        { origin: 'http://127.0.0.1', token: 'token' },
        async () => new Response('{', { status, headers: { 'Content-Type': 'application/json' } }),
      );
      await assert.rejects(
        client.request(BRIDGE_ROUTES.ping, CALL_ID, ResponseSchema),
        (error: unknown) => error instanceof ToolFailure && error.code === code,
      );
    }),
  );
});

test('enforces code-specific bridge error details and optional UUIDv4 edit IDs', () => {
  const worldBusy = {
    error: {
      code: 'world_busy',
      message: 'World is busy',
      details: { reason: 'operation_in_progress', world: 'world' },
    },
  };
  assert.equal(BridgeErrorResponseSchema.safeParse(worldBusy).success, true);
  assert.equal(BridgeErrorResponseSchema.safeParse({ error: { ...worldBusy.error, editId: EDIT_ID } }).success, true);
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: { ...worldBusy.error, editId: '22222222-2222-1222-8222-222222222222' },
    }).success,
    false,
  );
  assert.equal(BridgeErrorResponseSchema.safeParse({ error: { ...worldBusy.error, message: '' } }).success, false);
  assert.equal(
    BridgeErrorResponseSchema.safeParse({ error: { code: 'world_busy', message: 'World is busy' } }).success,
    false,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: { ...worldBusy.error, details: { reason: 'route_not_found' } },
    }).success,
    false,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: { code: 'internal_error', message: 'Internal failure', details: { reason: 'implementation' } },
    }).success,
    false,
  );
  assert.equal(
    BridgeErrorResponseSchema.safeParse({
      error: { ...worldBusy.error, details: { ...worldBusy.error.details, unexpected: true } },
    }).success,
    false,
  );
});

test('accepts one strict details variant for every bridge error code', () => {
  const errors: readonly Record<string, unknown>[] = [
    {
      code: 'bridge_busy',
      message: 'Busy',
      details: { maximumConcurrentRequests: 8 },
    },
    { code: 'change_limit_exceeded', message: 'Too many changes', details: { maximum: 100_000 } },
    {
      code: 'edit_not_found',
      message: 'Edit not found',
      details: { world: 'world', requestedEditId: EDIT_ID },
    },
    {
      code: 'edit_not_latest',
      message: 'Edit not latest',
      details: { world: 'world', requestedEditId: EDIT_ID, newestEditId: OTHER_EDIT_ID },
    },
    {
      code: 'history_capacity_exceeded',
      message: 'History full',
      details: { reason: 'retained_changed_blocks', maximum: 100_000 },
    },
    { code: 'internal_error', message: 'Internal failure' },
    {
      code: 'invalid_request',
      message: 'Invalid request',
      details: { reason: 'out_of_range', target: 'maxResults', value: 0, minimum: 1, maximum: 1_000 },
    },
    { code: 'method_not_allowed', message: 'Wrong method', details: { allowedMethod: 'POST' } },
    { code: 'not_found', message: 'Unknown route', details: { reason: 'route_not_found' } },
    { code: 'player_not_found', message: 'Player not found', details: { player: 'Builder' } },
    {
      code: 'player_unavailable',
      message: 'Player camera unavailable',
      details: { reason: 'spectating_entity', player: 'Builder' },
    },
    {
      code: 'region_too_large',
      message: 'Region too large',
      details: { reason: 'volume', dimensions: { x: 10, y: 20, z: 30 }, maximum: 5_000 },
    },
    {
      code: 'result_too_large',
      message: 'Result too large',
      details: { reason: 'structure_entries', minimumRequired: 1_001, maximum: 1_000 },
    },
    {
      code: 'server_unavailable',
      message: 'Inspections busy',
      details: { reason: 'inspection_busy', maximumConcurrentInspections: 2 },
    },
    { code: 'unauthorized', message: 'Unauthorized', details: { reason: 'authentication_failed' } },
    { code: 'unhealthy', message: 'Unhealthy', details: { reason: 'health_check_failed' } },
    {
      code: 'world_busy',
      message: 'Recovery required',
      details: { reason: 'recovery_required', world: 'world', newestEditId: EDIT_ID },
    },
    { code: 'world_not_found', message: 'World not found', details: { world: 'world' } },
    {
      code: 'world_unavailable',
      message: 'Chunk unavailable',
      details: { reason: 'chunk_load_failed', world: 'world', chunk: { x: -2, z: 3 } },
    },
  ];

  assert.deepEqual(
    errors.map((error) => error.code),
    [...BRIDGE_ERROR_CODES],
  );
  for (const error of errors) {
    assert.equal(BridgeErrorResponseSchema.safeParse({ error }).success, true, String(error.code));
  }
});

test('accepts every reason-discriminated bridge detail variant', () => {
  const variants: Readonly<Record<string, readonly Readonly<Record<string, unknown>>[]>> = {
    invalid_request: [
      { reason: 'unsupported_media_type', expected: 'application/json' },
      { reason: 'body_too_large', maximumBytes: 1_024 },
      { reason: 'malformed_json' },
      { reason: 'missing', field: 'world' },
      { reason: 'invalid_value', field: 'world' },
      { reason: 'unsupported_value', target: 'mode', allowedValues: ['replace', 'keep'] },
      { reason: 'duplicate', field: 'positions' },
      { reason: 'unknown_fields', field: 'legacyOption' },
      { reason: 'out_of_range', target: 'maxResults', value: 0, minimum: 1, maximum: 1_000 },
      { reason: 'too_many_items', fields: ['positions'], maximum: 10_000 },
      { reason: 'palette_weights_mixed', field: 'palette' },
      { reason: 'palette_weight_total', field: 'palette', requested: 99, required: 100 },
    ],
    player_unavailable: [
      { reason: 'spectating_entity', player: 'Builder' },
      { reason: 'non_finite_state', player: 'Builder', field: 'feetPosition.x' },
      { reason: 'non_finite_state', player: 'Builder', field: 'movement.velocity.x' },
      { reason: 'position_out_of_range', player: 'Builder', field: 'perspectiveEndpoint.z' },
    ],
    region_too_large: [
      { reason: 'volume', dimensions: { x: 10, y: 20, z: 30 }, maximum: 5_000 },
      { reason: 'touched_chunks', minimumRequired: 101, maximum: 100 },
      { reason: 'perspective_chunks', requested: 101, maximum: 100 },
      { reason: 'block_count', minimumRequired: 101, maximum: 100 },
    ],
    result_too_large: [
      { reason: 'structure_entries', minimumRequired: 101, maximum: 100 },
      { reason: 'palettes', minimumRequired: 101, maximum: 100 },
      { reason: 'visible_blocks', minimumRequired: 101, maximum: 100 },
      { reason: 'perspective_rays', minimumRequired: 101, maximum: 100 },
      { reason: 'perspective_ray_distance', minimumRequired: 101, maximum: 100 },
    ],
    history_capacity_exceeded: [
      { reason: 'entries_per_world', maximum: 100 },
      { reason: 'entries_total', maximum: 1_000 },
      { reason: 'retained_changed_blocks', maximum: 100_000 },
    ],
    server_unavailable: [
      { reason: 'dependency_unavailable' },
      { reason: 'paper_unavailable' },
      { reason: 'inspection_busy', maximumConcurrentInspections: 2 },
    ],
    unhealthy: [
      { reason: 'plugin_disabled' },
      { reason: 'dependency_unavailable' },
      { reason: 'no_loaded_worlds' },
      { reason: 'health_check_failed' },
      { reason: 'paper_unavailable' },
    ],
    world_busy: [
      { reason: 'operation_in_progress', world: 'world' },
      { reason: 'recovery_required', world: 'world', newestEditId: EDIT_ID },
    ],
    world_unavailable: [
      { reason: 'stopping' },
      { reason: 'interrupted' },
      { reason: 'paper_unavailable' },
      { reason: 'operation_failed' },
      { reason: 'rollback_failed' },
      { reason: 'rolled_back' },
      { reason: 'world_unloaded', world: 'world' },
      { reason: 'chunk_unloaded', world: 'world', chunk: { x: -2, z: 3 } },
      { reason: 'chunk_load_failed', world: 'world', chunk: { x: -2, z: 3 } },
    ],
  };

  for (const [code, detailsVariants] of Object.entries(variants)) {
    for (const details of detailsVariants) {
      assert.equal(
        BridgeErrorResponseSchema.safeParse({ error: { code, message: 'Failure', details } }).success,
        true,
        `${code}/${String(details.reason)}`,
      );
    }
  }
});

test('enforces Java-aligned numeric ranges and cross-field invariants', () => {
  const valid = [
    { code: 'bridge_busy', details: { maximumConcurrentRequests: INT32_MAX } },
    {
      code: 'invalid_request',
      details: {
        reason: 'out_of_range',
        target: 'coordinate',
        value: Number.MIN_SAFE_INTEGER,
        minimum: Number.MIN_SAFE_INTEGER + 1,
        maximum: Number.MAX_SAFE_INTEGER,
      },
    },
    {
      code: 'invalid_request',
      details: {
        reason: 'out_of_range',
        target: 'coordinate',
        value: Number.MAX_SAFE_INTEGER,
        minimum: Number.MIN_SAFE_INTEGER,
        maximum: Number.MAX_SAFE_INTEGER - 1,
      },
    },
    {
      code: 'invalid_request',
      details: { reason: 'too_many_items', fields: ['include', 'exclude'], maximum: INT32_MAX },
    },
    {
      code: 'invalid_request',
      details: {
        reason: 'palette_weight_total',
        field: 'palette',
        requested: Number.MAX_SAFE_INTEGER,
        required: 100,
      },
    },
    {
      code: 'history_capacity_exceeded',
      details: { reason: 'retained_changed_blocks', maximum: Number.MAX_SAFE_INTEGER },
    },
    {
      code: 'region_too_large',
      details: {
        reason: 'volume',
        dimensions: { x: Number.MAX_SAFE_INTEGER, y: 1, z: 1 },
        maximum: INT32_MAX,
      },
    },
    {
      code: 'region_too_large',
      details: { reason: 'volume', dimensions: { x: 1_001, y: 1, z: 1 }, maximum: 1_000 },
    },
    {
      code: 'region_too_large',
      details: { reason: 'touched_chunks', minimumRequired: INT32_MAX + 1, maximum: INT32_MAX },
    },
    {
      code: 'region_too_large',
      details: { reason: 'perspective_chunks', requested: INT32_MAX, maximum: INT32_MAX - 1 },
    },
    {
      code: 'region_too_large',
      details: { reason: 'block_count', minimumRequired: INT32_MAX, maximum: INT32_MAX - 1 },
    },
    {
      code: 'result_too_large',
      details: { reason: 'structure_entries', minimumRequired: INT32_MAX + 1, maximum: INT32_MAX },
    },
    {
      code: 'world_unavailable',
      details: { reason: 'chunk_unloaded', world: 'world', chunk: { x: INT32_MIN, z: INT32_MAX } },
    },
  ];
  const invalid = [
    { code: 'bridge_busy', details: { maximumConcurrentRequests: INT32_MAX + 1 } },
    {
      code: 'invalid_request',
      details: {
        reason: 'out_of_range',
        target: 'coordinate',
        value: Number.MIN_SAFE_INTEGER,
        minimum: Number.MIN_SAFE_INTEGER - 1,
        maximum: Number.MIN_SAFE_INTEGER - 1,
      },
    },
    {
      code: 'invalid_request',
      details: {
        reason: 'out_of_range',
        target: 'coordinate',
        value: Number.MAX_SAFE_INTEGER + 1,
        minimum: 0,
        maximum: 1,
      },
    },
    {
      code: 'history_capacity_exceeded',
      details: { reason: 'entries_total', maximum: Number.MAX_SAFE_INTEGER + 1 },
    },
    {
      code: 'region_too_large',
      details: {
        reason: 'volume',
        dimensions: { x: Number.MAX_SAFE_INTEGER + 1, y: 1, z: 1 },
        maximum: INT32_MAX,
      },
    },
    {
      code: 'region_too_large',
      details: { reason: 'volume', dimensions: { x: 10, y: 10, z: 10 }, maximum: 1_000 },
    },
    {
      code: 'world_unavailable',
      details: { reason: 'chunk_unloaded', world: 'world', chunk: { x: INT32_MIN - 1, z: 0 } },
    },
    {
      code: 'player_unavailable',
      details: { reason: 'non_finite_state', player: 'Builder', field: 'vitals.unknown' },
    },
    {
      code: 'player_unavailable',
      details: { reason: 'position_out_of_range', player: 'Builder', field: 'rotation.yaw' },
    },
    {
      code: 'player_unavailable',
      details: { reason: 'spectating_entity', player: 'Builder', field: 'vitals.health' },
    },
    {
      code: 'player_unavailable',
      details: { reason: 'spectating_entity', player: 'x'.repeat(37) },
    },
    {
      code: 'invalid_request',
      details: { reason: 'out_of_range', target: 'limit', value: 0, minimum: 2, maximum: 1 },
    },
    {
      code: 'invalid_request',
      details: { reason: 'out_of_range', target: 'limit', value: 2, minimum: 1, maximum: 3 },
    },
    {
      code: 'invalid_request',
      details: { reason: 'too_many_items', fields: [], maximum: 10 },
    },
    {
      code: 'invalid_request',
      details: { reason: 'too_many_items', fields: ['positions', 'positions'], maximum: 10 },
    },
    {
      code: 'invalid_request',
      details: { reason: 'too_many_items', fields: ['  '], maximum: 10 },
    },
    {
      code: 'invalid_request',
      details: { reason: 'too_many_items', field: 'positions', maximum: 10 },
    },
    {
      code: 'invalid_request',
      details: { reason: 'too_many_items', fields: ['positions'], maximum: INT32_MAX + 1 },
    },
    {
      code: 'invalid_request',
      details: { reason: 'unsupported_value', target: 'mode', allowedValues: [] },
    },
    {
      code: 'invalid_request',
      details: { reason: 'unsupported_value', target: 'mode', allowedValues: ['replace', 'replace'] },
    },
    {
      code: 'invalid_request',
      details: { reason: 'palette_weight_total', field: 'palette', requested: 100, required: 100 },
    },
    {
      code: 'invalid_request',
      details: { reason: 'palette_weight_total', field: 'palette', requested: 0, required: 100 },
    },
    {
      code: 'invalid_request',
      details: {
        reason: 'palette_weight_total',
        field: 'palette',
        requested: Number.MAX_SAFE_INTEGER + 1,
        required: 100,
      },
    },
    {
      code: 'edit_not_latest',
      details: { world: 'world', requestedEditId: EDIT_ID, newestEditId: EDIT_ID },
    },
    {
      code: 'region_too_large',
      details: { reason: 'touched_chunks', minimumRequired: 100, maximum: 100 },
    },
    {
      code: 'region_too_large',
      details: { reason: 'perspective_chunks', requested: INT32_MAX + 1, maximum: INT32_MAX },
    },
    {
      code: 'region_too_large',
      details: { reason: 'perspective_chunks', requested: 100, maximum: 100 },
    },
    {
      code: 'region_too_large',
      details: { reason: 'block_count', minimumRequired: 100, maximum: 100 },
    },
    {
      code: 'result_too_large',
      details: { reason: 'palettes', minimumRequired: 100, maximum: 100 },
    },
  ];

  for (const error of valid) {
    assert.equal(BridgeErrorResponseSchema.safeParse({ error: { ...error, message: 'Failure' } }).success, true);
  }
  for (const error of invalid) {
    assert.equal(BridgeErrorResponseSchema.safeParse({ error: { ...error, message: 'Failure' } }).success, false);
  }
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
