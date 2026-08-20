import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer, type IncomingHttpHeaders } from 'node:http';
import type { AddressInfo } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import {
  collectLines,
  modernParams,
  modernResult,
  send,
  waitFor,
  waitForValue,
  type JsonRpcResponse,
} from './support/mcp-process.ts';

const packageDirectory = dirname(dirname(fileURLToPath(import.meta.url)));

interface BridgeRequestRecord {
  readonly body: unknown;
  readonly headers: IncomingHttpHeaders;
  readonly method: string | undefined;
  readonly path: string | undefined;
}

function serverAddressPort(address: string | AddressInfo | null): number {
  assert.notEqual(address, null);
  assert.notEqual(typeof address, 'string');
  return (address as AddressInfo).port;
}

function requestAt(requests: readonly BridgeRequestRecord[], index: number): BridgeRequestRecord {
  const request = requests[index];
  assert.ok(request);
  return request;
}

function readAnnotations(): Record<string, boolean> {
  return { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true };
}

function mutationAnnotations(idempotentHint: boolean): Record<string, boolean> {
  return { readOnlyHint: false, destructiveHint: true, idempotentHint, openWorldHint: true };
}

test('forwards MCP tools to the authenticated bridge and preserves contract errors', async (context) => {
  const requests: BridgeRequestRecord[] = [];
  const ping = { status: 'ok' };
  const serverStatus = {
    builds: {
      minecraft: '26.2',
      paper: '26.2-112-main',
      dirtMcp: '0.1.0-test',
      fawe: '2.15.4-test',
    },
    performance: { tpsOneMinute: 19.98, averageTickTimeMillis: 4.25 },
    players: {
      online: 1,
      maximum: 20,
      entries: [
        {
          name: 'Builder',
          world: 'world',
          gameMode: 'creative',
          facing: 'west',
          blockPosition: { x: 12, y: 70, z: -4 },
        },
      ],
    },
    worlds: [
      {
        name: 'world',
        environment: 'normal',
        minY: -64,
        maxY: 319,
        spawn: { x: 0, y: 64, z: 0 },
        timeOfDay: 6000,
        storm: false,
        thundering: false,
        playerCount: 1,
      },
    ],
    limits: {
      maxRequestBytes: 262_144,
      maxRegionVolume: 1_000_000,
      maxTouchedChunks: 256,
      maxInspectionTouchedChunks: 32,
      maxBlockStatePatterns: 64,
      maxChangedBlocks: 250_000,
      maxInspectionVolume: 32_768,
      defaultInspectionResultLimit: 512,
      maxInspectionResultLimit: 2_048,
      maxCommandsPerRequest: 20,
      maxCommandFeedbackCharacters: 32_768,
      undoHistoryPerWorld: 20,
    },
    defaults: {
      regionBlocksIncludeAir: false,
      regionBlocksFormat: 'blocks',
      editDryRun: false,
    },
  };
  const regionBlocks = {
    world: 'world',
    bounds: { min: { x: 1, y: 2, z: 3 }, max: { x: 2, y: 2, z: 3 } },
    volume: 2,
    matchedBlockCount: 1,
    format: 'blocks',
    blocks: [{ position: { x: 1, y: 2, z: 3 }, blockState: 'minecraft:stone' }],
  };
  const blockStateCount = {
    world: 'world',
    bounds: { min: { x: 1, y: 2, z: 3 }, max: { x: 2, y: 2, z: 3 } },
    dimensions: { x: 2, y: 1, z: 1 },
    volume: 2,
    blockStateCounts: { 'minecraft:stone': 1, 'minecraft:air': 1 },
  };
  const replacement = {
    world: 'world',
    bounds: blockStateCount.bounds,
    sourceBlockStatePatterns: ['minecraft:stone'],
    destinationPalette: [{ blockState: 'minecraft:dirt', weight: 100 }],
    seed: 123,
    dryRun: true,
    matchedBlockCount: 1,
    changedBlockCount: 1,
  };
  const undone = { world: 'world', changedBlockCount: 1 };
  const view = {
    world: 'world',
    origin: { x: 1, y: 2, z: 4 },
    direction: 'north',
    format: 'blocks',
    basis: {
      forward: { x: 0, y: 0, z: -1 },
      horizontal: { x: 1, y: 0, z: 0 },
      vertical: { x: 0, y: 1, z: 0 },
    },
    viewport: { horizontalRadius: 1, verticalRadius: 1, maxDistance: 3 },
    bounds: { min: { x: 0, y: 1, z: 1 }, max: { x: 2, y: 3, z: 3 } },
    scannedVolume: 27,
    visibleBlockCount: 3,
    blocks: [
      {
        position: { x: 0, y: 3, z: 2 },
        offset: { horizontal: -1, vertical: 1, distance: 2 },
        blockState: 'minecraft:stone',
      },
      {
        position: { x: 2, y: 3, z: 3 },
        offset: { horizontal: 1, vertical: 1, distance: 1 },
        blockState: 'minecraft:oak_stairs[facing=north]',
      },
      {
        position: { x: 1, y: 2, z: 1 },
        offset: { horizontal: 0, vertical: 0, distance: 3 },
        blockState: 'minecraft:gold_block',
      },
    ],
  };
  const gridView = {
    world: view.world,
    origin: view.origin,
    direction: view.direction,
    basis: view.basis,
    viewport: view.viewport,
    bounds: view.bounds,
    scannedVolume: view.scannedVolume,
    visibleBlockCount: view.visibleBlockCount,
    format: 'grid',
    blockStatePalette: ['minecraft:stone', 'minecraft:oak_stairs[facing=north]', 'minecraft:gold_block'],
    blockStateIndexRows: [
      [1, 0, 2],
      [0, 3, 0],
      [0, 0, 0],
    ],
    distanceRows: [
      [2, 0, 1],
      [0, 3, 0],
      [0, 0, 0],
    ],
  };
  const commandRun = {
    sender: {
      name: 'FeedbackForwardingSender',
      isOperator: true,
      isPlayer: false,
    },
    feedbackTruncated: false,
    results: [
      {
        command: 'say hello',
        outcome: 'dispatched',
        feedback: ['[FeedbackForwardingSender] hello'],
        message: null,
        rawMessage: null,
      },
      {
        command: 'missing',
        outcome: 'not_found',
        feedback: [],
        message: 'Paper found no target for this command',
        rawMessage: null,
      },
      {
        command: 'time query daytime',
        outcome: 'dispatch_failed',
        feedback: [],
        message: 'Incorrect argument at position 11',
        rawMessage: "Unhandled exception executing 'time query daytime'",
      },
    ],
  };
  const setBlocks = {
    world: 'world',
    dryRun: false,
    blockCount: 2,
    changedBlockCount: 1,
    unchangedBlockCount: 1,
  };
  const bridge = createServer(async (request, response) => {
    let rawBody = '';
    for await (const chunk of request) {
      rawBody += chunk;
    }
    requests.push({
      method: request.method,
      path: request.url,
      headers: request.headers,
      body: rawBody.length === 0 ? undefined : JSON.parse(rawBody),
    });

    response.setHeader('Content-Type', 'application/json');
    if (request.url === '/v1/ping') {
      response.end(JSON.stringify(ping));
    } else if (request.url === '/v1/server-status') {
      response.end(JSON.stringify(serverStatus));
    } else if (request.url === '/v1/count-region-block-states') {
      response.end(JSON.stringify(blockStateCount));
    } else if (request.url === '/v1/get-region-blocks') {
      response.end(JSON.stringify(regionBlocks));
    } else if (request.url === '/v1/scan-orthographic-view') {
      response.end(JSON.stringify(view));
    } else if (request.url === '/v1/fill-region') {
      response.statusCode = 413;
      response.end(
        JSON.stringify({
          error: { code: 'change_limit_exceeded', message: 'Too many changes' },
        }),
      );
    } else if (request.url === '/v1/replace-region-blocks') {
      response.end(JSON.stringify(replacement));
    } else if (request.url === '/v1/set-blocks') {
      response.end(JSON.stringify(setBlocks));
    } else if (request.url === '/v1/undo-last-dirt-edit') {
      response.end(JSON.stringify(undone));
    } else if (request.url === '/v1/run-minecraft-commands') {
      response.end(JSON.stringify(commandRun));
    } else {
      response.statusCode = 404;
      response.end('{}');
    }
  });
  bridge.listen(0, '127.0.0.1');
  await once(bridge, 'listening');
  context.after(
    () =>
      new Promise<void>((resolve, reject) => {
        bridge.close((error) => (error === undefined ? resolve() : reject(error)));
      }),
  );

  const address = bridge.address();
  const child = spawn(process.execPath, [join(packageDirectory, 'dist/index.js')], {
    env: {
      ...process.env,
      DIRT_MCP_BRIDGE_TOKEN: 'bridge-test-token',
      DIRT_MCP_BRIDGE_URL: `http://127.0.0.1:${serverAddressPort(address)}`,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  context.after(() => {
    if (child.exitCode === null) {
      child.kill();
    }
  });
  const messages = collectLines(child.stdout, (line) => JSON.parse(line) as JsonRpcResponse);
  const errors = collectLines(child.stderr, (line) => line);

  send(child, {
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/list',
    params: modernParams({}),
  });
  const catalog = await waitFor(messages, 1);
  const listedTools = catalog.result.tools;
  assert.ok(listedTools);
  assert.deepEqual(
    listedTools.map((tool) => tool.name),
    [
      'ping_server',
      'get_server_status',
      'count_region_block_states',
      'get_region_blocks',
      'scan_orthographic_view',
      'replace_region_blocks',
      'fill_region',
      'set_blocks',
      'undo_last_dirt_edit',
      'run_minecraft_commands',
    ],
  );
  assert.deepEqual(
    listedTools.map((tool) => ({ name: tool.name, annotations: tool.annotations })),
    [
      { name: 'ping_server', annotations: readAnnotations() },
      { name: 'get_server_status', annotations: readAnnotations() },
      { name: 'count_region_block_states', annotations: readAnnotations() },
      { name: 'get_region_blocks', annotations: readAnnotations() },
      { name: 'scan_orthographic_view', annotations: readAnnotations() },
      { name: 'replace_region_blocks', annotations: mutationAnnotations(false) },
      { name: 'fill_region', annotations: mutationAnnotations(false) },
      { name: 'set_blocks', annotations: mutationAnnotations(true) },
      { name: 'undo_last_dirt_edit', annotations: mutationAnnotations(false) },
      { name: 'run_minecraft_commands', annotations: mutationAnnotations(false) },
    ],
  );

  send(child, {
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/call',
    params: modernParams({ name: 'ping_server', arguments: {} }),
  });
  const pinged = await waitFor(messages, 2);
  assert.deepEqual(
    pinged.result,
    modernResult({
      content: [{ type: 'text', text: 'ok' }],
      structuredContent: ping,
    }),
  );

  const region = {
    world: 'world',
    min: { x: 1, y: 2, z: 3 },
    max: { x: 2, y: 2, z: 3 },
  };
  send(child, {
    jsonrpc: '2.0',
    id: 3,
    method: 'tools/call',
    params: modernParams({ name: 'get_region_blocks', arguments: region }),
  });
  const inspected = await waitFor(messages, 3);
  assert.deepEqual(
    inspected.result,
    modernResult({
      content: [{ type: 'text', text: 'Matching blocks: 1; block entries: 1; world: world.' }],
      structuredContent: regionBlocks,
    }),
  );

  const viewInput = {
    world: 'world',
    origin: { x: 1, y: 2, z: 4 },
    direction: 'north',
    horizontalRadius: 1,
    verticalRadius: 1,
    maxDistance: 3,
  };
  send(child, {
    jsonrpc: '2.0',
    id: 4,
    method: 'tools/call',
    params: modernParams({ name: 'scan_orthographic_view', arguments: viewInput }),
  });
  const viewed = await waitFor(messages, 4);
  assert.deepEqual(
    viewed.result,
    modernResult({
      content: [{ type: 'text', text: 'Scanned view in world: 3 visible blocks returned explicitly.' }],
      structuredContent: view,
    }),
  );

  send(child, {
    jsonrpc: '2.0',
    id: 5,
    method: 'tools/call',
    params: modernParams({
      name: 'scan_orthographic_view',
      arguments: { ...viewInput, format: 'grid' },
    }),
  });
  const gridViewed = await waitFor(messages, 5);
  assert.deepEqual(
    gridViewed.result,
    modernResult({
      content: [
        {
          type: 'text',
          text: 'Scanned 3x3 view: 3 visible blocks using 3 block states.',
        },
      ],
      structuredContent: gridView,
    }),
  );

  send(child, {
    jsonrpc: '2.0',
    id: 6,
    method: 'tools/call',
    params: modernParams({
      name: 'fill_region',
      arguments: {
        ...region,
        destinationPalette: [{ blockState: 'minecraft:dirt' }],
      },
    }),
  });
  const failedFill = await waitFor(messages, 6);
  assert.deepEqual(
    failedFill.result,
    modernResult({
      isError: true,
      content: [
        {
          type: 'text',
          text: 'Could not fill the region: Too many changes',
        },
      ],
      structuredContent: {
        error: { code: 'change_limit_exceeded', message: 'Too many changes' },
      },
    }),
  );
  await waitForValue(errors, (line) => line.includes('tool=fill_region'));

  send(child, {
    jsonrpc: '2.0',
    id: 7,
    method: 'tools/call',
    params: modernParams({ name: 'get_server_status', arguments: {} }),
  });
  const status = await waitFor(messages, 7);
  assert.deepEqual(
    status.result,
    modernResult({
      content: [
        {
          type: 'text',
          text: 'Paper 26.2-112-main; players 1/20; loaded worlds: world.',
        },
      ],
      structuredContent: serverStatus,
    }),
  );

  const setBlocksInput = {
    world: 'world',
    origin: { x: 1, y: 2, z: 3 },
    palette: ['minecraft:stone', 'minecraft:glass'],
    placements: [
      { paletteIndex: 0, offsets: [[0, 0, 0]] },
      { paletteIndex: 1, offsets: [[4, 0, 0]] },
    ],
  };
  send(child, {
    jsonrpc: '2.0',
    id: 8,
    method: 'tools/call',
    params: modernParams({
      name: 'set_blocks',
      arguments: setBlocksInput,
    }),
  });
  const blocksSet = await waitFor(messages, 8);
  assert.deepEqual(
    blocksSet.result,
    modernResult({
      content: [
        {
          type: 'text',
          text: 'Changed 1 of 2 requested blocks in world.',
        },
      ],
      structuredContent: setBlocks,
    }),
  );

  send(child, {
    jsonrpc: '2.0',
    id: 9,
    method: 'tools/call',
    params: modernParams({
      name: 'run_minecraft_commands',
      arguments: { commands: ['/say hello', 'missing', 'time query daytime'] },
    }),
  });
  const commands = await waitFor(messages, 9);
  assert.deepEqual(
    commands.result,
    modernResult({
      isError: true,
      content: [
        {
          type: 'text',
          text: 'Dispatched 1 of 3 command(s); see per-command outcomes.',
        },
      ],
      structuredContent: commandRun,
    }),
  );

  send(child, {
    jsonrpc: '2.0',
    id: 10,
    method: 'tools/call',
    params: modernParams({ name: 'count_region_block_states', arguments: region }),
  });
  const counted = await waitFor(messages, 10);
  assert.deepEqual(
    counted.result,
    modernResult({
      content: [{ type: 'text', text: 'Counted 2 blocks across 2 block states in world.' }],
      structuredContent: blockStateCount,
    }),
  );

  send(child, {
    jsonrpc: '2.0',
    id: 11,
    method: 'tools/call',
    params: modernParams({
      name: 'replace_region_blocks',
      arguments: {
        ...region,
        sourceBlockStatePatterns: ['minecraft:stone'],
        destinationPalette: [{ blockState: 'minecraft:dirt', weight: 100 }],
        seed: 123,
        dryRun: true,
      },
    }),
  });
  const replaced = await waitFor(messages, 11);
  assert.deepEqual(
    replaced.result,
    modernResult({
      content: [
        {
          type: 'text',
          text: 'Would change 1 of 1 matching blocks in world using seed 123.',
        },
      ],
      structuredContent: replacement,
    }),
  );

  send(child, {
    jsonrpc: '2.0',
    id: 12,
    method: 'tools/call',
    params: modernParams({ name: 'undo_last_dirt_edit', arguments: { world: 'world' } }),
  });
  const undo = await waitFor(messages, 12);
  assert.deepEqual(
    undo.result,
    modernResult({
      content: [{ type: 'text', text: 'Undid the last Dirt edit in world, restoring 1 blocks.' }],
      structuredContent: undone,
    }),
  );

  assert.deepEqual(
    requests.map(({ method, path }) => ({ method, path })),
    [
      { method: 'GET', path: '/v1/ping' },
      { method: 'POST', path: '/v1/get-region-blocks' },
      { method: 'POST', path: '/v1/scan-orthographic-view' },
      { method: 'POST', path: '/v1/scan-orthographic-view' },
      { method: 'POST', path: '/v1/fill-region' },
      { method: 'GET', path: '/v1/server-status' },
      { method: 'POST', path: '/v1/set-blocks' },
      { method: 'POST', path: '/v1/run-minecraft-commands' },
      { method: 'POST', path: '/v1/count-region-block-states' },
      { method: 'POST', path: '/v1/replace-region-blocks' },
      { method: 'POST', path: '/v1/undo-last-dirt-edit' },
    ],
  );
  for (const request of requests) {
    assert.equal(request.headers.authorization, 'Bearer bridge-test-token');
    assert.equal(request.headers.accept, 'application/json');
    const callId = request.headers['x-dirt-call-id'];
    assert.equal(typeof callId, 'string');
    assert.match(callId as string, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  }
  const callIds = requests.map((request) => {
    const callId = request.headers['x-dirt-call-id'];
    assert.equal(typeof callId, 'string');
    return callId;
  });
  assert.equal(new Set(callIds).size, 11);
  assert.deepEqual(requestAt(requests, 1).body, {
    ...region,
    includeBlockStatePatterns: [],
    excludeBlockStatePatterns: [],
  });
  assert.equal(requestAt(requests, 1).headers['content-type'], 'application/json');
  assert.deepEqual(requestAt(requests, 2).body, viewInput);
  assert.equal(requestAt(requests, 2).headers['content-type'], 'application/json');
  assert.deepEqual(requestAt(requests, 3).body, viewInput);
  assert.equal(requestAt(requests, 3).headers['content-type'], 'application/json');
  assert.deepEqual(requestAt(requests, 4).body, {
    ...region,
    destinationPalette: [{ blockState: 'minecraft:dirt' }],
  });
  assert.equal(requestAt(requests, 4).headers['content-type'], 'application/json');
  assert.deepEqual(requestAt(requests, 6).body, setBlocksInput);
  assert.equal(requestAt(requests, 6).headers['content-type'], 'application/json');
  assert.deepEqual(requestAt(requests, 7).body, {
    commands: ['/say hello', 'missing', 'time query daytime'],
  });
  assert.equal(requestAt(requests, 7).headers['content-type'], 'application/json');
  assert.deepEqual(requestAt(requests, 8).body, region);
  assert.deepEqual(requestAt(requests, 9).body, {
    ...region,
    sourceBlockStatePatterns: ['minecraft:stone'],
    destinationPalette: [{ blockState: 'minecraft:dirt', weight: 100 }],
    seed: 123,
    dryRun: true,
  });
  assert.deepEqual(requestAt(requests, 10).body, { world: 'world' });

  const auditLines = errors.values.filter((line) => line.startsWith('Dirt MCP tool_call '));
  const expectedAudits: readonly (readonly [string, number, boolean, string])[] = [
    ['ping_server', 2, false, 'ok'],
    ['get_region_blocks', 3, true, 'ok'],
    ['scan_orthographic_view', 4, true, 'ok'],
    ['scan_orthographic_view', 5, true, 'ok'],
    ['fill_region', 6, true, 'error'],
    ['get_server_status', 7, false, 'ok'],
    ['set_blocks', 8, true, 'ok'],
    ['run_minecraft_commands', 9, false, 'error'],
    ['count_region_block_states', 10, true, 'ok'],
    ['replace_region_blocks', 11, true, 'ok'],
    ['undo_last_dirt_edit', 12, true, 'ok'],
  ];
  assert.equal(auditLines.length, expectedAudits.length);
  expectedAudits.forEach(([tool, requestId, includesWorld, outcome], index) => {
    const world = includesWorld ? ' world="world"' : '';
    const auditLine = auditLines[index];
    const callId = callIds[index];
    assert.ok(auditLine);
    assert.ok(callId);
    assert.match(
      auditLine,
      new RegExp(
        `^Dirt MCP tool_call tool=${tool} call=${callId} request=${requestId} ` +
          `client="bridge-test/1"${world} outcome=${outcome} duration_ms=\\d+$`,
      ),
    );
  });

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});

test('returns stable structured codes for MCP-local bridge failures', async (context) => {
  let behavior = 'unauthorized';
  const bridge = createServer((_request, response) => {
    if (behavior === 'unauthorized') {
      response.statusCode = 401;
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ error: { code: 'unauthorized', message: 'bad token' } }));
    } else if (behavior === 'invalid') {
      response.setHeader('Content-Type', 'application/json');
      response.end('{}');
    } else if (behavior === 'malformed') {
      response.setHeader('Content-Type', 'application/json');
      response.end('{');
    } else {
      response.statusCode = 502;
      response.setHeader('Content-Type', 'text/plain');
      response.end('not json');
    }
  });
  bridge.listen(0, '127.0.0.1');
  await once(bridge, 'listening');

  const address = bridge.address();
  const child = spawn(process.execPath, [join(packageDirectory, 'dist/index.js')], {
    env: {
      ...process.env,
      DIRT_MCP_BRIDGE_TOKEN: 'bridge-test-token',
      DIRT_MCP_BRIDGE_URL: `http://127.0.0.1:${serverAddressPort(address)}`,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  context.after(async () => {
    if (child.exitCode === null) child.kill();
    if (bridge.listening) {
      await new Promise<void>((resolve, reject) => {
        bridge.close((error) => (error === undefined ? resolve() : reject(error)));
      });
    }
  });
  const messages = collectLines(child.stdout, (line) => JSON.parse(line) as JsonRpcResponse);

  async function callPing(id: number): Promise<JsonRpcResponse> {
    send(child, {
      jsonrpc: '2.0',
      id,
      method: 'tools/call',
      params: modernParams({ name: 'ping_server', arguments: {} }),
    });
    return waitFor(messages, id);
  }

  const unauthorized = await callPing(10);
  assert.equal(unauthorized.result.isError, true);
  assert.deepEqual(unauthorized.result.structuredContent, {
    error: {
      code: 'bridge_unauthorized',
      message: 'Paper bridge rejected DIRT_MCP_BRIDGE_TOKEN.',
    },
  });

  behavior = 'invalid';
  const invalid = await callPing(11);
  assert.equal(invalid.result.isError, true);
  assert.deepEqual(invalid.result.structuredContent, {
    error: {
      code: 'bridge_invalid_response',
      message: 'Paper bridge response did not match the documented schema.',
    },
  });

  behavior = 'malformed';
  const malformed = await callPing(12);
  assert.equal(malformed.result.isError, true);
  assert.deepEqual(malformed.result.structuredContent, {
    error: {
      code: 'bridge_invalid_response',
      message: 'Paper bridge returned invalid JSON.',
    },
  });

  behavior = 'unstructured';
  const unstructured = await callPing(13);
  assert.equal(unstructured.result.isError, true);
  assert.deepEqual(unstructured.result.structuredContent, {
    error: {
      code: 'bridge_http_error',
      message: 'Paper bridge returned unstructured HTTP 502.',
    },
  });

  await new Promise<void>((resolve, reject) => {
    bridge.close((error) => (error === undefined ? resolve() : reject(error)));
  });
  const unavailable = await callPing(14);
  assert.equal(unavailable.result.isError, true);
  assert.equal(unavailable.result.structuredContent?.error?.code, 'bridge_unavailable');

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});
