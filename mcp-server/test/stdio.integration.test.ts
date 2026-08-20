import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer, type IncomingHttpHeaders } from 'node:http';
import type { AddressInfo } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { MCP_TOOL_NAMES, type McpToolConfiguration } from '../dist/tools/configuration.js';
import {
  collectLines,
  requestParams,
  completeResult,
  send,
  waitFor,
  waitForValue,
  type JsonRpcResponse,
} from './support/mcp-process.ts';

const packageDirectory = dirname(dirname(fileURLToPath(import.meta.url)));
const uuidV4Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const bridgeToken = '0123456789abcdef0123456789abcdef';

function toolConfiguration(enabled: boolean): McpToolConfiguration {
  return Object.fromEntries(MCP_TOOL_NAMES.map((name) => [name, enabled])) as McpToolConfiguration;
}

const allToolsEnabled = toolConfiguration(true);

function minimalServerStatus(tools: McpToolConfiguration = allToolsEnabled): Record<string, unknown> {
  return {
    builds: { minecraft: '26.2', paper: '26.2-test', dirtMcp: '0.1.0-test', fawe: '2.15.4-test' },
    performance: { tpsOneMinute: 20, averageTickTimeMillis: 1 },
    players: { online: 0, maximum: 20, entries: [] },
    worlds: [],
    limits: {
      maxRequestBytes: 1,
      maxRegionVolume: 1,
      maxTouchedChunks: 1,
      maxInspectionTouchedChunks: 1,
      maxBlockStatePatterns: 1,
      maxChangedBlocks: 1,
      maxInspectionVolume: 1,
      defaultInspectionResultLimit: 1,
      maxInspectionResultLimit: 1,
    },
    editHistory: { maxEntriesPerWorld: 1, maxEntriesTotal: 1, maxRetainedChangedBlocks: 1 },
    defaults: { regionBlocksIncludeAir: false, regionBlocksFormat: 'blocks', editDryRun: false },
    logging: { consoleLevel: 'info', detailFileMaxBytes: 1, detailFileRetainedFiles: 2 },
    tools,
  };
}

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
  readonly message: string;
  readonly service: string;
}

function parseLogRecord(line: string): McpLogRecord {
  return JSON.parse(line) as McpLogRecord;
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
    },
    editHistory: {
      maxEntriesPerWorld: 20,
      maxEntriesTotal: 100,
      maxRetainedChangedBlocks: 1_000_000,
    },
    defaults: {
      regionBlocksIncludeAir: false,
      regionBlocksFormat: 'blocks',
      editDryRun: false,
    },
    logging: {
      consoleLevel: 'info',
      detailFileMaxBytes: 10_485_760,
      detailFileRetainedFiles: 5,
    },
    tools: allToolsEnabled,
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
    outcome: 'preview',
    edit: null,
    matchedBlockCount: 1,
    changedBlockCount: 1,
  };
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
    viewport: { horizontalRadius: 1, verticalRadius: 1, maxDistance: 3, depth: 1 },
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
  const setBlocks = {
    world: 'world',
    bounds: { min: { x: 1, y: 2, z: 3 }, max: { x: 5, y: 2, z: 3 } },
    palettes: [
      [
        { blockState: 'minecraft:stone', weight: 75 },
        { blockState: 'minecraft:glass', weight: 25 },
      ],
    ],
    seed: 123,
    outcome: 'committed',
    edit: {
      editId: '11111111-1111-4111-8111-111111111111',
      callId: '33333333-3333-4333-8333-333333333333',
      operation: 'set_blocks',
      world: 'world',
      worldId: '22222222-2222-4222-8222-222222222222',
      bounds: { min: { x: 1, y: 2, z: 3 }, max: { x: 5, y: 2, z: 3 } },
      changedBlockCount: 1,
      completedAt: '2026-08-19T12:34:56Z',
      status: 'committed',
    },
    blockCount: 2,
    changedBlockCount: 1,
    unchangedBlockCount: 1,
  };
  const editHistory = { world: 'world', edits: [setBlocks.edit] };
  const undone = {
    edit: setBlocks.edit,
    undoCallId: '44444444-4444-4444-8444-444444444444',
    undoneAt: '2026-08-19T12:35:30Z',
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
      assert.equal(typeof request.headers['x-dirt-call-id'], 'string');
      setBlocks.edit.callId = request.headers['x-dirt-call-id'] as string;
      response.end(JSON.stringify(setBlocks));
    } else if (request.url === '/v1/get-edit-history') {
      response.end(JSON.stringify(editHistory));
    } else if (request.url === '/v1/undo-edit') {
      assert.equal(typeof request.headers['x-dirt-call-id'], 'string');
      undone.undoCallId = request.headers['x-dirt-call-id'] as string;
      response.end(JSON.stringify(undone));
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
      DIRT_MCP_BRIDGE_TOKEN: bridgeToken,
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
  const logs = collectLines(child.stderr, parseLogRecord);

  send(child, {
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/list',
    params: requestParams({}),
  });
  const catalog = await waitFor(messages, 1);
  assert.ok(catalog.result);
  const catalogLog = await waitForValue(logs, (record) => record.event === 'catalog.loaded', 'catalog.loaded log');
  assert.equal(catalogLog.level, 'info');
  assert.equal(catalogLog.component, 'catalog');
  assert.equal(catalogLog.call_id, requestAt(requests, 0).headers['x-dirt-call-id']);
  assert.equal(catalogLog.enabled_tool_count, MCP_TOOL_NAMES.length);
  assert.equal(catalogLog.enabled_tools, MCP_TOOL_NAMES.join(','));
  const listedTools = catalog.result.tools;
  assert.ok(listedTools);
  assert.deepEqual(
    listedTools.map((tool) => tool.name),
    [...MCP_TOOL_NAMES],
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
      { name: 'set_blocks', annotations: mutationAnnotations(false) },
      { name: 'get_edit_history', annotations: readAnnotations() },
      { name: 'undo_edit', annotations: mutationAnnotations(false) },
    ],
  );
  const listedView = listedTools.find((tool) => tool.name === 'scan_orthographic_view');
  assert.ok(listedView);
  const viewInputSchema = listedView.inputSchema as {
    readonly properties: { readonly depth: unknown };
  };
  assert.deepEqual(viewInputSchema.properties.depth, {
    default: 0,
    description: 'Zero-based non-air hit to return per sightline: 0 is first, 1 is second, and so on.',
    type: 'integer',
    minimum: 0,
    maximum: 2_147_483_647,
  });
  const listedSetBlocks = listedTools.find((tool) => tool.name === 'set_blocks');
  assert.ok(listedSetBlocks);
  const setBlocksInputSchema = listedSetBlocks.inputSchema as {
    readonly properties: {
      readonly placements: { readonly items: unknown };
    };
  };
  assert.deepEqual(setBlocksInputSchema.properties.placements.items, {
    type: 'array',
    items: {
      type: 'integer',
      minimum: -2_147_483_648,
      maximum: 2_147_483_647,
    },
    minItems: 4,
    maxItems: 4,
    description: 'Exact [paletteIndex, x, y, z] tuple; x, y, and z are signed offsets from the origin.',
  });

  send(child, {
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/call',
    params: requestParams({ name: 'ping_server', arguments: {} }),
  });
  const pinged = await waitFor(messages, 2);
  assert.deepEqual(
    pinged.result,
    completeResult({
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
    params: requestParams({ name: 'get_region_blocks', arguments: region }),
  });
  const inspected = await waitFor(messages, 3);
  assert.deepEqual(
    inspected.result,
    completeResult({
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
    depth: 1,
  };
  send(child, {
    jsonrpc: '2.0',
    id: 4,
    method: 'tools/call',
    params: requestParams({ name: 'scan_orthographic_view', arguments: viewInput }),
  });
  const viewed = await waitFor(messages, 4);
  assert.deepEqual(
    viewed.result,
    completeResult({
      content: [{ type: 'text', text: 'Scanned view in world: 3 visible blocks returned explicitly.' }],
      structuredContent: view,
    }),
  );

  send(child, {
    jsonrpc: '2.0',
    id: 5,
    method: 'tools/call',
    params: requestParams({
      name: 'scan_orthographic_view',
      arguments: { ...viewInput, format: 'grid' },
    }),
  });
  const gridViewed = await waitFor(messages, 5);
  assert.deepEqual(
    gridViewed.result,
    completeResult({
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
    params: requestParams({
      name: 'fill_region',
      arguments: {
        ...region,
        destinationPalette: [{ blockState: 'minecraft:dirt' }],
      },
    }),
  });
  const failedFill = await waitFor(messages, 6);
  assert.ok(failedFill.result);
  const failedFillCallId = failedFill.result.structuredContent?.error?.callId;
  assert.ok(typeof failedFillCallId === 'string');
  assert.match(failedFillCallId, uuidV4Pattern);
  assert.deepEqual(
    failedFill.result,
    completeResult({
      isError: true,
      content: [
        {
          type: 'text',
          text: 'Could not fill the region: Too many changes',
        },
      ],
      structuredContent: {
        error: { code: 'change_limit_exceeded', message: 'Too many changes', callId: failedFillCallId },
      },
    }),
  );
  await waitForValue(
    logs,
    (record) => record.event === 'tool.completed' && record.operation === 'fill_region',
    'fill_region tool.completed log',
  );

  send(child, {
    jsonrpc: '2.0',
    id: 7,
    method: 'tools/call',
    params: requestParams({ name: 'get_server_status', arguments: {} }),
  });
  const status = await waitFor(messages, 7);
  assert.deepEqual(
    status.result,
    completeResult({
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
    palettes: [
      [
        { blockState: 'minecraft:stone', weight: 75 },
        { blockState: 'minecraft:glass', weight: 25 },
      ],
    ],
    placements: [
      [0, 0, 0, 0],
      [0, 4, 0, 0],
    ],
    seed: 123,
  };
  send(child, {
    jsonrpc: '2.0',
    id: 8,
    method: 'tools/call',
    params: requestParams({
      name: 'set_blocks',
      arguments: setBlocksInput,
    }),
  });
  const blocksSet = await waitFor(messages, 8);
  assert.deepEqual(
    blocksSet.result,
    completeResult({
      content: [
        {
          type: 'text',
          text: 'Changed 1 of 2 requested blocks in world using seed 123. Edit ID: 11111111-1111-4111-8111-111111111111.',
        },
      ],
      structuredContent: setBlocks,
    }),
  );

  const requestCountBeforeInvalidUndo = requests.length;
  send(child, {
    jsonrpc: '2.0',
    id: 9,
    method: 'tools/call',
    params: requestParams({ name: 'undo_edit', arguments: { world: 'world', editId: 'not-a-uuid' } }),
  });
  const invalidUndo = await waitFor(messages, 9);
  assert.ok(invalidUndo.result);
  assert.equal(invalidUndo.result.isError, true);
  assert.equal(invalidUndo.result.structuredContent, undefined);
  assert.match(
    invalidUndo.result.content?.[0]?.text ?? '',
    /Input validation error: Invalid arguments for tool undo_edit/,
  );
  assert.equal(requests.length, requestCountBeforeInvalidUndo);

  send(child, {
    jsonrpc: '2.0',
    id: 10,
    method: 'tools/call',
    params: requestParams({ name: 'count_region_block_states', arguments: region }),
  });
  const counted = await waitFor(messages, 10);
  assert.deepEqual(
    counted.result,
    completeResult({
      content: [{ type: 'text', text: 'Counted 2 blocks across 2 block states in world.' }],
      structuredContent: blockStateCount,
    }),
  );

  send(child, {
    jsonrpc: '2.0',
    id: 11,
    method: 'tools/call',
    params: requestParams({
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
    completeResult({
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
    params: requestParams({ name: 'get_edit_history', arguments: { world: 'world' } }),
  });
  const history = await waitFor(messages, 12);
  assert.deepEqual(
    history.result,
    completeResult({
      content: [{ type: 'text', text: 'Found 1 retained undoable edit in world.' }],
      structuredContent: editHistory,
    }),
  );

  send(child, {
    jsonrpc: '2.0',
    id: 13,
    method: 'tools/call',
    params: requestParams({
      name: 'undo_edit',
      arguments: { world: 'world', editId: setBlocks.edit.editId },
    }),
  });
  const undo = await waitFor(messages, 13);
  assert.deepEqual(
    undo.result,
    completeResult({
      content: [
        {
          type: 'text',
          text: 'Undid edit 11111111-1111-4111-8111-111111111111 in world, restoring 1 blocks.',
        },
      ],
      structuredContent: undone,
    }),
  );

  assert.deepEqual(
    requests.map(({ method, path }) => ({ method, path })),
    [
      { method: 'GET', path: '/v1/server-status' },
      { method: 'GET', path: '/v1/ping' },
      { method: 'POST', path: '/v1/get-region-blocks' },
      { method: 'POST', path: '/v1/scan-orthographic-view' },
      { method: 'POST', path: '/v1/scan-orthographic-view' },
      { method: 'POST', path: '/v1/fill-region' },
      { method: 'GET', path: '/v1/server-status' },
      { method: 'POST', path: '/v1/set-blocks' },
      { method: 'POST', path: '/v1/count-region-block-states' },
      { method: 'POST', path: '/v1/replace-region-blocks' },
      { method: 'POST', path: '/v1/get-edit-history' },
      { method: 'POST', path: '/v1/undo-edit' },
    ],
  );
  for (const request of requests) {
    assert.equal(request.headers.authorization, `Bearer ${bridgeToken}`);
    assert.equal(request.headers.accept, 'application/json');
    const callId = request.headers['x-dirt-call-id'];
    assert.equal(typeof callId, 'string');
    assert.match(callId as string, uuidV4Pattern);
  }
  const callIds = requests.map((request) => {
    const callId = request.headers['x-dirt-call-id'];
    assert.equal(typeof callId, 'string');
    return callId;
  });
  assert.equal(new Set(callIds).size, 12);
  const toolCallIds = callIds.slice(1);
  assert.equal(failedFillCallId, toolCallIds[4]);
  assert.equal(setBlocks.edit.callId, toolCallIds[6]);
  assert.equal(undone.undoCallId, toolCallIds[10]);
  assert.deepEqual(requestAt(requests, 2).body, {
    ...region,
    includeBlockStatePatterns: [],
    excludeBlockStatePatterns: [],
  });
  assert.equal(requestAt(requests, 2).headers['content-type'], 'application/json');
  assert.deepEqual(requestAt(requests, 3).body, viewInput);
  assert.equal(requestAt(requests, 3).headers['content-type'], 'application/json');
  assert.deepEqual(requestAt(requests, 4).body, viewInput);
  assert.equal(requestAt(requests, 4).headers['content-type'], 'application/json');
  assert.deepEqual(requestAt(requests, 5).body, {
    ...region,
    destinationPalette: [{ blockState: 'minecraft:dirt' }],
  });
  assert.equal(requestAt(requests, 5).headers['content-type'], 'application/json');
  assert.deepEqual(requestAt(requests, 7).body, setBlocksInput);
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
  assert.deepEqual(requestAt(requests, 11).body, {
    world: 'world',
    editId: '11111111-1111-4111-8111-111111111111',
  });

  const auditRecords = logs.values.filter((record) => record.event === 'tool.completed');
  const expectedAudits: readonly (readonly [string, number, boolean, boolean])[] = [
    ['ping_server', 2, false, true],
    ['get_region_blocks', 3, true, true],
    ['scan_orthographic_view', 4, true, true],
    ['scan_orthographic_view', 5, true, true],
    ['fill_region', 6, true, false],
    ['get_server_status', 7, false, true],
    ['set_blocks', 8, true, true],
    ['count_region_block_states', 10, true, true],
    ['replace_region_blocks', 11, true, true],
    ['get_edit_history', 12, true, true],
    ['undo_edit', 13, true, true],
  ];
  assert.equal(auditRecords.length, expectedAudits.length);
  expectedAudits.forEach(([operation, requestId, includesWorld, success], index) => {
    const auditRecord = auditRecords[index];
    const callId = toolCallIds[index];
    assert.ok(auditRecord);
    assert.ok(callId);
    assert.equal(auditRecord.level, 'info');
    assert.equal(auditRecord.service, 'dirt-mcp-stdio');
    assert.equal(auditRecord.component, 'tool');
    assert.equal(auditRecord.message, 'Tool call completed.');
    assert.equal(auditRecord.operation, operation);
    assert.equal(auditRecord.call_id, callId);
    assert.equal(auditRecord.request_id, requestId);
    assert.equal(auditRecord.client, 'bridge-test/1');
    assert.equal(auditRecord.world, includesWorld ? 'world' : undefined);
    assert.equal(auditRecord.success, success);
    assert.equal(typeof auditRecord.duration_ms, 'number');
  });
  const setBlocksAudit = auditRecords.find((record) => record.operation === 'set_blocks');
  assert.equal(setBlocksAudit?.edit_id, setBlocks.edit.editId);
  assert.equal(setBlocksAudit?.outcome, 'committed');
  assert.equal(setBlocksAudit?.changed_block_count, 1);
  const historyAudit = auditRecords.find((record) => record.operation === 'get_edit_history');
  assert.equal(historyAudit?.result_count, 1);
  const undoAudit = auditRecords.find((record) => record.operation === 'undo_edit');
  assert.equal(undoAudit?.edit_id, setBlocks.edit.editId);
  assert.equal(undoAudit?.outcome, 'undone');
  assert.equal(undoAudit?.changed_block_count, 1);
  assert.equal(JSON.stringify(logs.values).includes(bridgeToken), false);

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});

test('exposes only the configured tool snapshot and rejects disabled calls before bridge dispatch', async (context) => {
  const configuredTools = toolConfiguration(false);
  configuredTools.get_region_blocks = true;
  configuredTools.set_blocks = true;
  configuredTools.undo_edit = true;
  const requests: BridgeRequestRecord[] = [];
  const bridge = createServer(async (request, response) => {
    let rawBody = '';
    for await (const chunk of request) rawBody += chunk;
    requests.push({
      method: request.method,
      path: request.url,
      headers: request.headers,
      body: rawBody.length === 0 ? undefined : JSON.parse(rawBody),
    });
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify(minimalServerStatus(configuredTools)));
  });
  bridge.listen(0, '127.0.0.1');
  await once(bridge, 'listening');
  context.after(
    () =>
      new Promise<void>((resolve, reject) => {
        bridge.close((error) => (error === undefined ? resolve() : reject(error)));
      }),
  );

  const child = spawn(process.execPath, [join(packageDirectory, 'dist/index.js')], {
    env: {
      ...process.env,
      DIRT_MCP_BRIDGE_TOKEN: bridgeToken,
      DIRT_MCP_BRIDGE_URL: `http://127.0.0.1:${serverAddressPort(bridge.address())}`,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  context.after(() => {
    if (child.exitCode === null) child.kill();
  });
  const messages = collectLines(child.stdout, (line) => JSON.parse(line) as JsonRpcResponse);

  send(child, { jsonrpc: '2.0', id: 20, method: 'server/discover', params: requestParams({}) });
  const discovered = await waitFor(messages, 20);
  assert.ok(discovered.result);
  const instructions = discovered.result.instructions;
  assert.equal(typeof instructions, 'string');
  assert.match(instructions as string, /get_region_blocks, set_blocks, undo_edit/);
  for (const disabledName of MCP_TOOL_NAMES.filter((name) => !configuredTools[name])) {
    assert.doesNotMatch(instructions as string, new RegExp(`\\b${disabledName}\\b`));
  }

  send(child, { jsonrpc: '2.0', id: 21, method: 'tools/list', params: requestParams({}) });
  const catalog = await waitFor(messages, 21);
  assert.ok(catalog.result);
  assert.deepEqual(
    catalog.result.tools?.map((tool) => tool.name),
    ['get_region_blocks', 'set_blocks', 'undo_edit'],
  );
  const listedMetadata = JSON.stringify(catalog.result.tools);
  assert.doesNotMatch(listedMetadata, /get_server_status|get_edit_history/);

  const requestCountBeforeDisabledCall = requests.length;
  send(child, {
    jsonrpc: '2.0',
    id: 22,
    method: 'tools/call',
    params: requestParams({ name: 'ping_server', arguments: {} }),
  });
  const disabled = await waitFor(messages, 22);
  assert.equal(disabled.error?.code, -32_602);
  assert.match(disabled.error?.message ?? '', /Tool ping_server disabled/);
  assert.equal(requests.length, requestCountBeforeDisabledCall);
  assert.equal(requests.length, 1);
  assert.equal(requestAt(requests, 0).path, '/v1/server-status');

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});

test('reports startup configuration failures as structured stderr without using stdout', async () => {
  const child = spawn(process.execPath, [join(packageDirectory, 'dist/index.js')], {
    env: { ...process.env, DIRT_MCP_BRIDGE_TOKEN: '' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const messages = collectLines(child.stdout, (line) => line);
  const logs = collectLines(child.stderr, parseLogRecord);

  const failure = await waitForValue(
    logs,
    (record) => record.event === 'runtime.start_failed',
    'runtime.start_failed log',
  );
  assert.equal(failure.level, 'error');
  assert.equal(failure.service, 'dirt-mcp-stdio');
  assert.equal(failure.component, 'runtime');
  assert.equal(failure.error_code, 'bridge_token_required');
  assert.equal(JSON.stringify(failure).includes('DIRT_MCP_BRIDGE_TOKEN'), false);

  const [exitCode] = await once(child, 'exit');
  assert.equal(exitCode, 1);
  assert.deepEqual(messages.values, []);
});

for (const bootstrapFailure of ['unauthorized', 'malformed', 'unavailable', 'domain-error'] as const) {
  test(`fails closed when tool-configuration bootstrap is ${bootstrapFailure}`, async (context) => {
    const expectedFailureCode = {
      malformed: 'bridge_invalid_response',
      unauthorized: 'bridge_unauthorized',
      unavailable: 'bridge_unavailable',
      'domain-error': 'world_not_found',
    }[bootstrapFailure];
    let bridgeRequestCount = 0;
    const bridge = createServer((_request, response) => {
      bridgeRequestCount++;
      if (bootstrapFailure === 'unauthorized') {
        response.statusCode = 401;
        response.setHeader('Content-Type', 'application/json');
        response.end(JSON.stringify({ error: { code: 'unauthorized', message: 'bad token' } }));
      } else if (bootstrapFailure === 'domain-error') {
        response.statusCode = 404;
        response.setHeader('Content-Type', 'application/json');
        response.end(JSON.stringify({ error: { code: 'world_not_found', message: 'unexpected route result' } }));
      } else {
        response.setHeader('Content-Type', 'application/json');
        response.end('{}');
      }
    });
    bridge.listen(0, '127.0.0.1');
    await once(bridge, 'listening');
    const bridgeUrl = `http://127.0.0.1:${serverAddressPort(bridge.address())}`;
    if (bootstrapFailure === 'unavailable') {
      await new Promise<void>((resolve, reject) => {
        bridge.close((error) => (error === undefined ? resolve() : reject(error)));
      });
    } else {
      context.after(
        () =>
          new Promise<void>((resolve, reject) => {
            bridge.close((error) => (error === undefined ? resolve() : reject(error)));
          }),
      );
    }

    const child = spawn(process.execPath, [join(packageDirectory, 'dist/index.js')], {
      env: {
        ...process.env,
        DIRT_MCP_BRIDGE_TOKEN: bridgeToken,
        DIRT_MCP_BRIDGE_URL: bridgeUrl,
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    context.after(() => {
      if (child.exitCode === null) child.kill();
    });
    const messages = collectLines(child.stdout, (line) => JSON.parse(line) as JsonRpcResponse);
    const logs = collectLines(child.stderr, parseLogRecord);

    send(child, { jsonrpc: '2.0', id: 30, method: 'tools/list', params: requestParams({}) });
    const failure = await waitFor(messages, 30);
    assert.equal(failure.error?.code, -32_603);
    assert.equal(failure.error?.message, 'Internal server error');
    assert.equal(failure.result, undefined);
    assert.equal(bridgeRequestCount, bootstrapFailure === 'unavailable' ? 0 : 1);
    const catalogFailure = await waitForValue(
      logs,
      (record) => record.event === 'catalog.load_failed' && record.error_code === expectedFailureCode,
      `catalog.load_failed log with ${expectedFailureCode}`,
    );
    assert.equal(catalogFailure.operation, 'get_server_status');
    assert.equal(catalogFailure.level, bootstrapFailure === 'malformed' ? 'error' : 'warning');

    const exited = once(child, 'exit');
    child.stdin.end();
    const [exitCode] = await exited;
    assert.equal(exitCode, 0);
  });
}

test('retries a failed bootstrap and fixes the first successful snapshot for the process', async (context) => {
  let bootstrapAttempts = 0;
  const configuredTools = toolConfiguration(false);
  configuredTools.ping_server = true;
  const bridge = createServer((_request, response) => {
    bootstrapAttempts++;
    response.setHeader('Content-Type', 'application/json');
    if (bootstrapAttempts === 1) {
      response.end('{}');
      return;
    }
    response.end(JSON.stringify(minimalServerStatus(configuredTools)));
  });
  bridge.listen(0, '127.0.0.1');
  await once(bridge, 'listening');
  context.after(
    () =>
      new Promise<void>((resolve, reject) => {
        bridge.close((error) => (error === undefined ? resolve() : reject(error)));
      }),
  );

  const child = spawn(process.execPath, [join(packageDirectory, 'dist/index.js')], {
    env: {
      ...process.env,
      DIRT_MCP_BRIDGE_TOKEN: bridgeToken,
      DIRT_MCP_BRIDGE_URL: `http://127.0.0.1:${serverAddressPort(bridge.address())}`,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  context.after(() => {
    if (child.exitCode === null) child.kill();
  });
  const messages = collectLines(child.stdout, (line) => JSON.parse(line) as JsonRpcResponse);

  send(child, { jsonrpc: '2.0', id: 40, method: 'tools/list', params: requestParams({}) });
  const failed = await waitFor(messages, 40);
  assert.equal(failed.error?.code, -32_603);
  assert.equal(failed.result, undefined);
  assert.equal(bootstrapAttempts, 1);

  send(child, { jsonrpc: '2.0', id: 41, method: 'tools/list', params: requestParams({}) });
  const recovered = await waitFor(messages, 41);
  assert.ok(recovered.result);
  assert.deepEqual(
    recovered.result.tools?.map((tool) => tool.name),
    ['ping_server'],
  );
  assert.equal(bootstrapAttempts, 2);

  configuredTools.get_server_status = true;
  send(child, { jsonrpc: '2.0', id: 42, method: 'tools/list', params: requestParams({}) });
  const fixedSnapshot = await waitFor(messages, 42);
  assert.ok(fixedSnapshot.result);
  assert.deepEqual(
    fixedSnapshot.result.tools?.map((tool) => tool.name),
    ['ping_server'],
  );
  assert.equal(bootstrapAttempts, 2);

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});

test('returns stable structured codes for MCP-local bridge failures', async (context) => {
  let behavior = 'unauthorized';
  const bridge = createServer((request, response) => {
    if (request.url === '/v1/server-status') {
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify(minimalServerStatus()));
      return;
    }
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
      DIRT_MCP_BRIDGE_TOKEN: bridgeToken,
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
      params: requestParams({ name: 'ping_server', arguments: {} }),
    });
    return waitFor(messages, id);
  }

  const unauthorized = await callPing(10);
  assert.ok(unauthorized.result);
  assert.equal(unauthorized.result.isError, true);
  const unauthorizedCallId = unauthorized.result.structuredContent?.error?.callId;
  assert.ok(typeof unauthorizedCallId === 'string');
  assert.match(unauthorizedCallId, uuidV4Pattern);
  assert.deepEqual(unauthorized.result.structuredContent, {
    error: {
      code: 'bridge_unauthorized',
      message: 'Paper bridge rejected DIRT_MCP_BRIDGE_TOKEN.',
      callId: unauthorizedCallId,
    },
  });

  behavior = 'invalid';
  const invalid = await callPing(11);
  assert.ok(invalid.result);
  assert.equal(invalid.result.isError, true);
  const invalidCallId = invalid.result.structuredContent?.error?.callId;
  assert.ok(typeof invalidCallId === 'string');
  assert.match(invalidCallId, uuidV4Pattern);
  assert.deepEqual(invalid.result.structuredContent, {
    error: {
      code: 'bridge_invalid_response',
      message: 'Paper bridge response did not match the documented schema.',
      callId: invalidCallId,
    },
  });

  behavior = 'malformed';
  const malformed = await callPing(12);
  assert.ok(malformed.result);
  assert.equal(malformed.result.isError, true);
  const malformedCallId = malformed.result.structuredContent?.error?.callId;
  assert.ok(typeof malformedCallId === 'string');
  assert.match(malformedCallId, uuidV4Pattern);
  assert.deepEqual(malformed.result.structuredContent, {
    error: {
      code: 'bridge_invalid_response',
      message: 'Paper bridge returned invalid JSON.',
      callId: malformedCallId,
    },
  });

  behavior = 'unstructured';
  const unstructured = await callPing(13);
  assert.ok(unstructured.result);
  assert.equal(unstructured.result.isError, true);
  const unstructuredCallId = unstructured.result.structuredContent?.error?.callId;
  assert.ok(typeof unstructuredCallId === 'string');
  assert.match(unstructuredCallId, uuidV4Pattern);
  assert.deepEqual(unstructured.result.structuredContent, {
    error: {
      code: 'bridge_http_error',
      message: 'Paper bridge returned unstructured HTTP 502.',
      callId: unstructuredCallId,
    },
  });

  await new Promise<void>((resolve, reject) => {
    bridge.close((error) => (error === undefined ? resolve() : reject(error)));
  });
  const unavailable = await callPing(14);
  assert.ok(unavailable.result);
  assert.equal(unavailable.result.isError, true);
  assert.equal(unavailable.result.structuredContent?.error?.code, 'bridge_unavailable');

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});
