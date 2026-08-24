import assert from 'node:assert/strict';
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { once } from 'node:events';
import { createServer, type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test, { type TestContext } from 'node:test';
import { fromJsonSchema, type JsonSchemaType } from '@modelcontextprotocol/server';
import { MCP_TOOL_NAMES } from '../dist/tools/configuration.js';
import {
  collectLines,
  requestParams,
  send,
  waitFor,
  waitForValue,
  type JsonRpcResponse,
} from './support/mcp-process.ts';

const packageDirectory = dirname(dirname(fileURLToPath(import.meta.url)));
const bridgeToken = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
const uuidV4Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;

interface BridgeRequestRecord {
  readonly body: unknown;
  readonly headers: IncomingHttpHeaders;
  readonly method: string | undefined;
  readonly path: string | undefined;
}

interface McpLogRecord extends Record<string, unknown> {
  readonly component: string;
  readonly event: string;
  readonly level: string;
}

interface RunningMcp {
  readonly child: ChildProcessWithoutNullStreams;
  readonly logs: ReturnType<typeof collectLines<McpLogRecord>>;
  readonly messages: ReturnType<typeof collectLines<JsonRpcResponse>>;
}

function isJsonObject(value: unknown): value is Readonly<Record<string, unknown>> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function jsonObject(value: unknown): Readonly<Record<string, unknown>> {
  assert.ok(isJsonObject(value));
  return value;
}

function parseLogRecord(line: string): McpLogRecord {
  return JSON.parse(line) as McpLogRecord;
}

function port(server: Server): number {
  return (server.address() as AddressInfo).port;
}

function runMcp(context: TestContext, bridge: Server): RunningMcp {
  const child = spawn(process.execPath, [join(packageDirectory, 'dist/index.js')], {
    env: {
      ...process.env,
      DIRT_MCP_BRIDGE_TOKEN: bridgeToken,
      DIRT_MCP_BRIDGE_URL: `http://127.0.0.1:${port(bridge)}`,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  context.after(() => {
    if (child.exitCode === null) child.kill();
  });
  return {
    child,
    messages: collectLines(child.stdout, (line) => JSON.parse(line) as JsonRpcResponse),
    logs: collectLines(child.stderr, parseLogRecord),
  };
}

async function closeMcp(child: ChildProcessWithoutNullStreams): Promise<void> {
  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
}

async function listen(server: Server, context: TestContext): Promise<void> {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  context.after(
    () =>
      new Promise<void>((resolve, reject) => {
        server.close((error) => (error === undefined ? resolve() : reject(error)));
      }),
  );
}

function callTool(
  running: RunningMcp,
  id: number,
  name: string,
  arguments_: Record<string, unknown>,
): Promise<JsonRpcResponse> {
  send(running.child, {
    jsonrpc: '2.0',
    id,
    method: 'tools/call',
    params: requestParams({ name, arguments: arguments_ }),
  });
  return waitFor(running.messages, id);
}

test('capability-driven stdio server forwards all twelve tools with MCP-owned defaults', async (context) => {
  const requests: BridgeRequestRecord[] = [];
  const edit = {
    editId: '11111111-1111-4111-8111-111111111111',
    callId: '22222222-2222-4222-8222-222222222222',
    label: 'Place stone',
    operation: 'set_blocks',
    world: 'world',
    worldId: '33333333-3333-4333-8333-333333333333',
    bounds: { min: { x: 0, y: 64, z: 0 }, max: { x: 0, y: 64, z: 0 } },
    changedBlockCount: 1,
    completedAt: '2026-08-23T12:00:00Z',
    status: 'committed',
  } as const;
  let commandCall = 0;
  let undoCall = 0;

  const responses: Record<string, unknown> = {
    '/v1/ping': { status: 'ok' },
    '/v1/server-status': {
      builds: { minecraft: '26.2', paper: '26.2-116', dirtPlugin: '0.1.0', fawe: '2.15.4' },
      performance: { tpsOneMinute: 20, averageTickTimeMillis: 4.2 },
      players: null,
      worlds: [],
      configuration: null,
    },
    '/v1/count-region-block-states': {
      world: 'world',
      bounds: { min: { x: 0, y: 64, z: 0 }, max: { x: 0, y: 64, z: 0 } },
      dimensions: { x: 1, y: 1, z: 1 },
      volume: 1,
      blockStateCounts: { 'minecraft:stone': 1 },
    },
    '/v1/get-blocks': {
      world: 'world',
      origin: { x: 0, y: 64, z: 0 },
      palettes: [[{ blockState: 'minecraft:stone' }]],
      placements: [[0, 0, 0, 0]],
      runs: [],
    },
    '/v1/scan-orthographic-view': {
      world: 'world',
      origin: { x: 0, y: 64, z: -1 },
      palettes: [],
      placements: [],
      runs: [],
    },
    '/v1/get-player-context': {
      capturedAt: '2026-08-23T12:00:00Z',
      player: { name: 'Builder', uuid: '44444444-4444-4444-8444-444444444444' },
      world: 'world',
      worldId: '33333333-3333-4333-8333-333333333333',
      gameMode: 'creative',
      feetPosition: { x: 0.5, y: 64, z: 0.5 },
      blockPosition: { x: 0, y: 64, z: 0 },
      eyePosition: { x: 0.5, y: 65.62, z: 0.5 },
      rotation: { yaw: 0, pitch: 0 },
      lookDirection: { x: 0, y: 0, z: 1 },
      pose: 'standing',
      onGround: true,
      equipment: {
        selectedHotbarSlot: 0,
        mainHand: null,
        offHand: null,
        helmet: null,
        chestplate: null,
        leggings: null,
        boots: null,
      },
      inventory: null,
      enderChest: null,
      vitals: null,
      movement: null,
      client: null,
      effects: null,
    },
    '/v1/get-perspective-view': {
      capturedAt: '2026-08-23T12:00:00Z',
      source: { type: 'player', player: { name: 'Builder', uuid: '44444444-4444-4444-8444-444444444444' } },
      world: 'world',
      worldId: '33333333-3333-4333-8333-333333333333',
      cameraPosition: { x: 0.5, y: 65.62, z: 0.5 },
      rotation: { yaw: 0, pitch: 0 },
      lookDirection: { x: 0, y: 0, z: 1 },
      basis: {
        forward: { x: 0, y: 0, z: 1 },
        right: { x: -1, y: 0, z: 0 },
        up: { x: 0, y: 1, z: 0 },
      },
      viewport: {
        width: 21,
        height: 13,
        verticalFieldOfViewDegrees: 70,
        horizontalFieldOfViewDegrees: 95,
        maxDistance: 32,
        fluidCollision: 'never',
        ignorePassableBlocks: false,
      },
      checkedChunkCount: 1,
      blockStatePalette: [],
      hits: [],
      crosshairHitIndex: null,
    },
  };

  const bridge = createServer(async (request, response) => {
    let rawBody = '';
    for await (const chunk of request) rawBody += chunk;
    const record: BridgeRequestRecord = {
      method: request.method,
      path: request.url,
      headers: request.headers,
      body: rawBody === '' ? undefined : JSON.parse(rawBody),
    };
    requests.push(record);
    response.setHeader('Content-Type', 'application/json');

    if (request.url === '/v1/capabilities') {
      response.end(
        JSON.stringify({
          operations: [
            'pingServer',
            'getServerStatus',
            'countRegionBlockStates',
            'getBlocks',
            'scanOrthographicView',
            'getPlayerContext',
            'getPerspectiveView',
            'replaceRegionBlocks',
            'setBlocks',
            'getEditHistory',
            'undoEdits',
            'runMinecraftCommands',
          ],
        }),
      );
      return;
    }
    if (request.url === '/v1/replace-region-blocks') {
      const body = record.body as {
        world: string;
        label: string;
        min: { x: number; y: number; z: number };
        max: { x: number; y: number; z: number };
        seed: number;
        dryRun: boolean;
      };
      const bounds = {
        min: {
          x: Math.min(body.min.x, body.max.x),
          y: Math.min(body.min.y, body.max.y),
          z: Math.min(body.min.z, body.max.z),
        },
        max: {
          x: Math.max(body.min.x, body.max.x),
          y: Math.max(body.min.y, body.max.y),
          z: Math.max(body.min.z, body.max.z),
        },
      };
      response.end(
        JSON.stringify({
          world: body.world,
          bounds,
          seed: body.seed,
          outcome: body.dryRun ? 'preview' : 'committed',
          edit: body.dryRun
            ? null
            : {
                ...edit,
                callId: request.headers['x-dirt-call-id'],
                label: body.label,
                operation: 'replace_region_blocks',
                world: body.world,
                bounds,
              },
          matchedBlockCount: 1,
          changedBlockCount: 1,
        }),
      );
      return;
    }
    if (request.url === '/v1/set-blocks') {
      const body = record.body as { world: string; label: string; seed: number; dryRun: boolean };
      response.end(
        JSON.stringify({
          world: body.world,
          bounds: edit.bounds,
          seed: body.seed,
          outcome: body.dryRun ? 'preview' : 'committed',
          edit: body.dryRun
            ? null
            : {
                ...edit,
                callId: request.headers['x-dirt-call-id'],
                label: body.label,
                world: body.world,
              },
          blockCount: 1,
          changedBlockCount: 1,
          unchangedBlockCount: 0,
        }),
      );
      return;
    }
    if (request.url === '/v1/get-edit-history') {
      const body = record.body as { world: string };
      response.end(JSON.stringify({ world: body.world, edits: [{ ...edit, world: body.world }] }));
      return;
    }
    if (request.url === '/v1/undo-edits') {
      const body = record.body as { world: string; editIds: readonly string[] };
      undoCall++;
      if (undoCall === 1) {
        response.end(
          JSON.stringify({
            outcome: 'completed',
            world: body.world,
            undoneEdits: body.editIds.map((editId) => ({ ...edit, editId, world: body.world })),
            undoCallId: request.headers['x-dirt-call-id'],
            undoneAt: '2026-08-23T12:01:00Z',
          }),
        );
      } else {
        response.end(
          JSON.stringify({
            outcome: 'partial',
            world: body.world,
            undoCallId: request.headers['x-dirt-call-id'],
            undoneEdits: [],
            failure: { code: 'internal_error', message: 'Undo stopped safely.', editId: body.editIds[0] },
          }),
        );
      }
      return;
    }
    if (request.url === '/v1/run-minecraft-commands') {
      commandCall++;
      const commandResponses = [
        {
          sender: { name: 'Dirt MCP', isOperator: true, isPlayer: false },
          feedbackTruncated: false,
          results: [{ command: 'say hello', outcome: 'dispatched', feedback: [], message: null, rawMessage: null }],
        },
        {
          sender: { name: 'Dirt MCP', isOperator: true, isPlayer: false },
          feedbackTruncated: false,
          results: [
            {
              command: 'missing',
              outcome: 'not_found',
              feedback: [],
              message: 'Not found.',
              rawMessage: null,
            },
          ],
        },
        {
          sender: { name: 'Dirt MCP', isOperator: true, isPlayer: false },
          feedbackTruncated: true,
          results: [
            { command: 'say ready', outcome: 'dispatched', feedback: [], message: null, rawMessage: null },
            {
              command: 'bad',
              outcome: 'dispatch_failed',
              feedback: ['Usage'],
              message: 'Failed.',
              rawMessage: 'Command exception.',
            },
          ],
        },
      ];
      response.end(JSON.stringify(commandResponses[commandCall - 1]));
      return;
    }
    const routeResponse = responses[request.url ?? ''];
    if (routeResponse !== undefined) {
      response.end(JSON.stringify(routeResponse));
      return;
    }
    response.statusCode = 404;
    response.end('{}');
  });
  await listen(bridge, context);
  const running = runMcp(context, bridge);

  send(running.child, { jsonrpc: '2.0', id: 1, method: 'tools/list', params: requestParams({}) });
  const catalog = await waitFor(running.messages, 1);
  assert.deepEqual(
    catalog.result?.tools?.map((tool) => tool.name),
    [...MCP_TOOL_NAMES],
  );
  const toolInput = (name: string): Readonly<Record<string, unknown>> => {
    const tool = catalog.result?.tools?.find((candidate) => candidate.name === name);
    assert.ok(tool, `Missing ${name} from the advertised catalog.`);
    return jsonObject(tool.inputSchema);
  };
  const setProperties = jsonObject(toolInput('set_blocks').properties);
  const placementTuple = jsonObject(jsonObject(setProperties.placements).items);
  const runTuple = jsonObject(jsonObject(setProperties.runs).items);
  assert.deepEqual(
    { minItems: placementTuple.minItems, maxItems: placementTuple.maxItems, items: placementTuple.items },
    { minItems: 4, maxItems: 4, items: false },
  );
  assert.deepEqual(
    { minItems: runTuple.minItems, maxItems: runTuple.maxItems, items: runTuple.items },
    { minItems: 7, maxItems: 7, items: false },
  );
  assert.equal(jsonObject(setProperties.world).pattern, '.*\\S.*');
  const perspectiveProperties = jsonObject(toolInput('get_perspective_view').properties);
  assert.deepEqual(jsonObject(perspectiveProperties.width).not, { multipleOf: 2 });
  assert.deepEqual(jsonObject(perspectiveProperties.height).not, { multipleOf: 2 });
  const capabilityRequest = requests[0];
  assert.equal(capabilityRequest?.path, '/v1/capabilities');
  assert.equal(capabilityRequest?.method, 'GET');
  assert.match(String(capabilityRequest?.headers['x-dirt-call-id']), uuidV4Pattern);
  assert.equal(capabilityRequest?.headers.authorization, `Bearer ${bridgeToken}`);
  const catalogLog = await waitForValue(running.logs, (entry) => entry.event === 'catalog.loaded', 'catalog log');
  assert.equal(catalogLog.enabled_tool_count, 12);

  const advertisedFailure = {
    callId: '55555555-5555-4555-8555-555555555555',
    error: { code: 'internal_error', message: 'Failure' },
  };
  await Promise.all(
    (catalog.result?.tools ?? []).map(async (tool) => {
      assert.ok(tool.outputSchema);
      const result = await fromJsonSchema(tool.outputSchema as JsonSchemaType)['~standard'].validate(advertisedFailure);
      assert.ok(result.issues, `${String(tool.name)} advertises a failure union`);
    }),
  );

  send(running.child, { jsonrpc: '2.0', id: 2, method: 'server/discover', params: requestParams({}) });
  const discovery = await waitFor(running.messages, 2);
  assert.match(String(discovery.result?.instructions), /live Paper worlds/u);
  assert.match(String(discovery.result?.instructions), /callId/u);

  const calls: readonly [string, Record<string, unknown>][] = [
    ['ping_server', {}],
    ['get_server_status', {}],
    ['count_region_block_states', { world: 'world', min: { x: 0, y: 64, z: 0 }, max: { x: 0, y: 64, z: 0 } }],
    ['get_blocks', { world: 'world', min: { x: 0, y: 64, z: 0 }, max: { x: 0, y: 64, z: 0 } }],
    [
      'scan_orthographic_view',
      {
        world: 'world',
        origin: { x: 0, y: 64, z: 0 },
        direction: 'north',
        horizontalRadius: 0,
        verticalRadius: 0,
        maxDistance: 16,
      },
    ],
    ['get_player_context', { player: 'Builder' }],
    ['get_perspective_view', { source: { type: 'player', player: 'Builder' } }],
    [
      'replace_region_blocks',
      {
        world: 'world',
        min: { x: 0, y: 64, z: 0 },
        max: { x: 0, y: 64, z: 0 },
        sourceBlockStatePatterns: ['minecraft:stone'],
        destinationPalette: [{ blockState: 'minecraft:dirt' }],
        label: 'Replace stone',
        dryRun: true,
      },
    ],
    [
      'set_blocks',
      {
        world: 'world',
        origin: { x: 0, y: 64, z: 0 },
        palettes: [[{ blockState: 'minecraft:stone' }]],
        placements: [[0, 0, 0, 0]],
        runs: [],
        label: 'Place stone',
      },
    ],
    ['get_edit_history', { world: 'world' }],
    ['undo_edits', { world: 'world', editIds: [edit.editId] }],
    ['run_minecraft_commands', { commands: ['  /say hello  '] }],
  ];
  await Promise.all(
    calls.map(async ([name, arguments_], index) => {
      const result = await callTool(running, 10 + index, name, arguments_);
      assert.equal(result.result?.isError, undefined, `${name} failed: ${JSON.stringify(result)}`);
    }),
  );

  const statusRequest = requests.find((request) => request.path === '/v1/server-status');
  assert.equal(statusRequest?.method, 'POST');
  assert.deepEqual(statusRequest?.body, {
    includePlayers: false,
    includeWorlds: true,
    includeConfiguration: false,
  });
  assert.deepEqual(requests.find((request) => request.path === '/v1/get-blocks')?.body, {
    world: 'world',
    min: { x: 0, y: 64, z: 0 },
    max: { x: 0, y: 64, z: 0 },
    includeBlockStatePatterns: [],
    excludeBlockStatePatterns: [],
    includeAir: false,
    maxResults: 1_024,
  });
  const scanBody = requests.find((request) => request.path === '/v1/scan-orthographic-view')?.body as Record<
    string,
    unknown
  >;
  assert.equal(scanBody.depth, 0);
  assert.equal(scanBody.maxResults, 1_024);
  const replaceBody = requests.find((request) => request.path === '/v1/replace-region-blocks')?.body as Record<
    string,
    unknown
  >;
  assert.equal(replaceBody.dryRun, true);
  assert.equal(replaceBody.maxChangedBlocks, null);
  assert.equal(Number.isInteger(replaceBody.seed), true);
  const setBody = requests.find((request) => request.path === '/v1/set-blocks')?.body as Record<string, unknown>;
  assert.equal(setBody.dryRun, false);
  assert.equal(setBody.maxChangedBlocks, null);
  assert.equal(Number.isInteger(setBody.seed), true);
  assert.deepEqual(requests.find((request) => request.path === '/v1/run-minecraft-commands')?.body, {
    commands: ['  /say hello  '],
  });
  for (const request of requests) {
    assert.match(String(request.headers['x-dirt-call-id']), uuidV4Pattern);
    assert.equal(request.headers.authorization, `Bearer ${bridgeToken}`);
  }

  const notFound = await callTool(running, 30, 'run_minecraft_commands', { commands: ['missing', 'later'] });
  assert.equal(notFound.result?.isError, true);
  const commandResults = notFound.result?.structuredContent?.results;
  assert.ok(Array.isArray(commandResults));
  assert.equal(commandResults.length, 1);
  const dispatchFailed = await callTool(running, 31, 'run_minecraft_commands', {
    commands: ['say ready', 'bad'],
  });
  assert.equal(dispatchFailed.result?.isError, true);

  const partial = await callTool(running, 32, 'undo_edits', { world: 'world', editIds: [edit.editId] });
  assert.equal(partial.result?.isError, true);
  assert.equal(partial.result?.structuredContent?.error?.code, 'internal_error');
  assert.deepEqual(partial.result?.structuredContent?.undoneEdits, []);
  const partialRequest = requests.findLast((request) => request.path === '/v1/undo-edits');
  assert.equal(partial.result?.structuredContent?.callId, partialRequest?.headers['x-dirt-call-id']);

  await closeMcp(running.child);
});

test('zero bridge operations is a valid empty MCP catalog', async (context) => {
  const requests: BridgeRequestRecord[] = [];
  const bridge = createServer(async (request, response) => {
    requests.push({ method: request.method, path: request.url, headers: request.headers, body: undefined });
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ operations: [] }));
  });
  await listen(bridge, context);
  const running = runMcp(context, bridge);

  send(running.child, { jsonrpc: '2.0', id: 1, method: 'tools/list', params: requestParams({}) });
  const catalog = await waitFor(running.messages, 1);
  assert.deepEqual(catalog.result?.tools, []);
  send(running.child, { jsonrpc: '2.0', id: 2, method: 'server/discover', params: requestParams({}) });
  const discovery = await waitFor(running.messages, 2);
  assert.equal(discovery.result?.instructions, 'No Dirt MCP tools are enabled for this server.');
  assert.equal(requests.length, 1);
  await closeMcp(running.child);
});

test('capability discovery failure fails startup before serving stdio', async (context) => {
  const bridge = createServer((_request, response) => {
    response.statusCode = 503;
    response.setHeader('Content-Type', 'application/json');
    response.end(
      JSON.stringify({
        error: {
          code: 'server_unavailable',
          message: 'Paper is unavailable.',
          details: { reason: 'paper_unavailable' },
        },
      }),
    );
  });
  await listen(bridge, context);
  const running = runMcp(context, bridge);
  const exited = once(running.child, 'exit');
  const failed = await waitForValue(
    running.logs,
    (record) => record.event === 'runtime.start_failed',
    'startup failure log',
  );
  const [exitCode] = await exited;
  assert.equal(exitCode, 1);
  assert.equal(failed.component, 'catalog');
  assert.equal(failed.error_code, 'server_unavailable');
  assert.equal(running.messages.values.length, 0);
});
