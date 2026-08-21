import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer, type IncomingHttpHeaders } from 'node:http';
import type { AddressInfo } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { fromJsonSchema, type JsonSchemaType } from '@modelcontextprotocol/server';
import packageMetadata from '../package.json' with { type: 'json' };
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
      maxCommandsPerRequest: 1,
      maxCommandFeedbackCharacters: 1,
    },
    editHistory: { maxEntriesPerWorld: 1, maxEntriesTotal: 1, maxRetainedChangedBlocks: 1 },
    defaults: { getBlocksIncludeAir: false, editDryRun: false },
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
      maxCommandsPerRequest: 10,
      maxCommandFeedbackCharacters: 8_192,
    },
    editHistory: {
      maxEntriesPerWorld: 20,
      maxEntriesTotal: 100,
      maxRetainedChangedBlocks: 1_000_000,
    },
    defaults: {
      getBlocksIncludeAir: false,
      editDryRun: false,
    },
    logging: {
      consoleLevel: 'info',
      detailFileMaxBytes: 10_485_760,
      detailFileRetainedFiles: 5,
    },
    tools: allToolsEnabled,
  };
  const exactBlocks = {
    world: 'world',
    origin: { x: 1, y: 2, z: 3 },
    palettes: [[{ blockState: 'minecraft:stone' }]],
    placements: [[0, 0, 0, 0]],
    runs: [],
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
  const perspectiveHorizontalFov = (2 * Math.atan(Math.tan((70 * Math.PI) / 360) * (21 / 13)) * 180) / Math.PI;
  const playerContext = {
    capturedAt: '2026-08-20T20:15:30Z',
    player: { name: 'Builder', uuid: '55555555-5555-4555-8555-555555555555' },
    world: 'world',
    worldId: '22222222-2222-4222-8222-222222222222',
    gameMode: 'creative',
    feetPosition: { x: 12.25, y: 70, z: -3.5 },
    blockPosition: { x: 12, y: 70, z: -4 },
    eyePosition: { x: 12.25, y: 71.62, z: -3.5 },
    rotation: { yaw: 0, pitch: 0 },
    lookDirection: { x: 0, y: 0, z: 1 },
    pose: 'standing',
    onGround: true,
    equipment: {
      selectedHotbarSlot: 0,
      mainHand: {
        type: 'minecraft:diamond_pickaxe',
        amount: 1,
        maxStackSize: 1,
        damage: 12,
        maxDamage: 1_561,
        unbreakable: false,
        enchantments: [{ type: 'minecraft:efficiency', level: 5 }],
      },
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
  };
  const perspectiveView = {
    capturedAt: playerContext.capturedAt,
    source: { type: 'player', player: playerContext.player },
    world: playerContext.world,
    worldId: playerContext.worldId,
    cameraPosition: playerContext.eyePosition,
    rotation: playerContext.rotation,
    lookDirection: playerContext.lookDirection,
    basis: {
      forward: { x: 0, y: 0, z: 1 },
      right: { x: -1, y: 0, z: 0 },
      up: { x: 0, y: 1, z: 0 },
    },
    viewport: {
      width: 21,
      height: 13,
      verticalFieldOfViewDegrees: 70,
      horizontalFieldOfViewDegrees: perspectiveHorizontalFov,
      maxDistance: 32,
      fluidCollision: 'never',
      ignorePassableBlocks: false,
    },
    checkedChunkCount: 4,
    blockStatePalette: ['minecraft:stone'],
    hits: [
      {
        row: 6,
        column: 10,
        blockStateIndex: 1,
        blockPosition: { x: 12, y: 71, z: 5 },
        hitPosition: { x: 12.25, y: 71.62, z: 5 },
        face: 'north',
        distance: 8.5,
      },
    ],
    crosshairHitIndex: 0,
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
    } else if (request.url === '/v1/get-blocks') {
      response.end(JSON.stringify(exactBlocks));
    } else if (request.url === '/v1/scan-orthographic-view') {
      response.end(JSON.stringify(view));
    } else if (request.url === '/v1/get-player-context') {
      response.end(JSON.stringify(playerContext));
    } else if (request.url === '/v1/get-perspective-view') {
      response.end(JSON.stringify(perspectiveView));
    } else if (request.url === '/v1/fill-region') {
      response.statusCode = 413;
      response.end(
        JSON.stringify({
          error: { code: 'change_limit_exceeded', message: 'Too many changes', details: { maximum: 100_000 } },
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
      { name: 'get_blocks', annotations: readAnnotations() },
      { name: 'scan_orthographic_view', annotations: readAnnotations() },
      { name: 'get_player_context', annotations: readAnnotations() },
      { name: 'get_perspective_view', annotations: readAnnotations() },
      { name: 'replace_region_blocks', annotations: mutationAnnotations(false) },
      { name: 'fill_region', annotations: mutationAnnotations(false) },
      { name: 'set_blocks', annotations: mutationAnnotations(false) },
      { name: 'get_edit_history', annotations: readAnnotations() },
      { name: 'undo_edit', annotations: mutationAnnotations(false) },
      { name: 'run_minecraft_commands', annotations: mutationAnnotations(false) },
    ],
  );
  const listedStatus = listedTools.find((tool) => tool.name === 'get_server_status');
  assert.ok(listedStatus);
  const statusInputSchema = listedStatus.inputSchema as {
    readonly required?: readonly string[];
    readonly properties: {
      readonly include: {
        readonly default: unknown;
        readonly required?: readonly string[];
        readonly properties: Readonly<Record<string, { readonly default: unknown }>>;
      };
    };
  };
  const statusInclude = statusInputSchema.properties.include;
  const statusIncludeDefaults = { players: false, worlds: true, configuration: false };
  assert.equal(statusInputSchema.required?.length ?? 0, 0);
  assert.equal(statusInclude.required?.length ?? 0, 0);
  assert.deepEqual(statusInclude.default, statusIncludeDefaults);
  assert.deepEqual(
    Object.fromEntries(Object.entries(statusInclude.properties).map(([name, schema]) => [name, schema.default])),
    statusIncludeDefaults,
  );
  const advertisedFailure = {
    callId: '11111111-1111-4111-8111-111111111111',
    error: {
      code: 'world_busy',
      message: 'World is busy',
      details: { reason: 'operation_in_progress', world: 'world' },
    },
  };
  const advertisedSchemas = listedTools.map((tool) => {
    assert.ok(tool.outputSchema, `${String(tool.name)} is missing outputSchema`);
    const serialized = JSON.stringify(tool.outputSchema);
    assert.ok(serialized);
    return {
      name: String(tool.name),
      schema: tool.outputSchema as JsonSchemaType,
      bytes: Buffer.byteLength(serialized),
    };
  });
  const outputSchemaBytes = advertisedSchemas.reduce((total, advertised) => total + advertised.bytes, 0);
  assert.ok(outputSchemaBytes < 120_000, `Tool output catalog grew to ${outputSchemaBytes} bytes`);
  await Promise.all(
    advertisedSchemas.map(async (advertised) => {
      const schema = fromJsonSchema(advertised.schema);
      const validFailure = await schema['~standard'].validate(advertisedFailure);
      assert.equal(validFailure.issues, undefined, `${advertised.name} rejects an advertised failure`);
      const missingDetails = await schema['~standard'].validate({
        ...advertisedFailure,
        error: { code: 'world_busy', message: 'World is busy' },
      });
      assert.ok(missingDetails.issues, `${advertised.name} allows a correctable failure without details`);
    }),
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
    readonly required?: readonly string[];
    readonly properties: {
      readonly placements: { readonly items: unknown };
      readonly runs: { readonly items: unknown };
    };
  };
  assert.deepEqual(setBlocksInputSchema.required, ['world', 'origin', 'palettes', 'placements', 'runs']);
  const paletteIndexSchema = { type: 'integer', minimum: 0, maximum: 2_147_483_647 };
  const offsetSchema = { type: 'integer', minimum: -2_147_483_648, maximum: 2_147_483_647 };
  assert.deepEqual(setBlocksInputSchema.properties.placements.items, {
    type: 'array',
    prefixItems: [paletteIndexSchema, offsetSchema, offsetSchema, offsetSchema],
    items: { not: {} },
    description: 'Exact [paletteIndex, x, y, z] tuple with origin-relative coordinates.',
  });
  assert.deepEqual(setBlocksInputSchema.properties.runs.items, {
    type: 'array',
    prefixItems: [
      paletteIndexSchema,
      offsetSchema,
      offsetSchema,
      offsetSchema,
      offsetSchema,
      offsetSchema,
      offsetSchema,
    ],
    items: { not: {} },
    description: 'Exact [paletteIndex, x, y, z, toX, toY, toZ] inclusive origin-relative cuboid tuple.',
  });
  const listedPlayerContext = listedTools.find((tool) => tool.name === 'get_player_context');
  assert.ok(listedPlayerContext);
  assert.match(String(listedPlayerContext.description ?? ''), /case-insensitively/);
  const listedPerspective = listedTools.find((tool) => tool.name === 'get_perspective_view');
  assert.ok(listedPerspective);
  assert.match(String(listedPerspective.description ?? ''), /arbitrary loaded-world camera/);

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
    params: requestParams({ name: 'get_blocks', arguments: region }),
  });
  const inspected = await waitFor(messages, 3);
  assert.deepEqual(
    inspected.result,
    completeResult({
      content: [{ type: 'text', text: 'Matching blocks: 1; structure entries: 1; palettes: 1; world: world.' }],
      structuredContent: exactBlocks,
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
  const failedFillCallId = failedFill.result.structuredContent?.callId;
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
        callId: failedFillCallId,
        error: {
          code: 'change_limit_exceeded',
          message: 'Too many changes',
          details: { maximum: 100_000 },
        },
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
          text: 'Paper 26.2-112-main; loaded worlds: world.',
        },
      ],
      structuredContent: {
        builds: serverStatus.builds,
        performance: serverStatus.performance,
        players: null,
        worlds: serverStatus.worlds,
        configuration: null,
      },
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
    placements: [[0, 0, 0, 0]],
    runs: [[0, 4, 0, 0, 4, 0, 0]],
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

  send(child, {
    jsonrpc: '2.0',
    id: 14,
    method: 'tools/call',
    params: requestParams({ name: 'get_player_context', arguments: { player: 'Builder' } }),
  });
  const player = await waitFor(messages, 14);
  assert.deepEqual(
    player.result,
    completeResult({
      content: [
        {
          type: 'text',
          text: 'Builder in world at 12.25, 70, -3.5.',
        },
      ],
      structuredContent: playerContext,
    }),
  );

  send(child, {
    jsonrpc: '2.0',
    id: 15,
    method: 'tools/call',
    params: requestParams({
      name: 'get_perspective_view',
      arguments: { source: { type: 'player', player: 'builder' } },
    }),
  });
  const perspective = await waitFor(messages, 15);
  assert.deepEqual(
    perspective.result,
    completeResult({
      content: [
        {
          type: 'text',
          text: 'Builder in world at 12.25, 71.62, -3.5; 1/273 rays hit blocks.',
        },
      ],
      structuredContent: perspectiveView,
    }),
  );

  assert.deepEqual(
    requests.map(({ method, path }) => ({ method, path })),
    [
      { method: 'GET', path: '/v1/server-status' },
      { method: 'GET', path: '/v1/ping' },
      { method: 'POST', path: '/v1/get-blocks' },
      { method: 'POST', path: '/v1/scan-orthographic-view' },
      { method: 'POST', path: '/v1/scan-orthographic-view' },
      { method: 'POST', path: '/v1/fill-region' },
      { method: 'GET', path: '/v1/server-status' },
      { method: 'POST', path: '/v1/set-blocks' },
      { method: 'POST', path: '/v1/count-region-block-states' },
      { method: 'POST', path: '/v1/replace-region-blocks' },
      { method: 'POST', path: '/v1/get-edit-history' },
      { method: 'POST', path: '/v1/undo-edit' },
      { method: 'POST', path: '/v1/get-player-context' },
      { method: 'POST', path: '/v1/get-perspective-view' },
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
  assert.equal(new Set(callIds).size, 14);
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
  assert.deepEqual(requestAt(requests, 12).body, {
    player: 'Builder',
    include: {
      equipment: true,
      inventory: false,
      enderChest: false,
      vitals: false,
      movement: false,
      client: false,
      effects: false,
    },
  });
  assert.deepEqual(requestAt(requests, 13).body, {
    source: { type: 'player', player: 'builder' },
    width: 21,
    height: 13,
    verticalFieldOfViewDegrees: 70,
    maxDistance: 32,
    fluidCollision: 'never',
    ignorePassableBlocks: false,
  });

  const auditRecords = logs.values.filter((record) => record.event === 'tool.completed');
  const expectedAudits: readonly (readonly [string, number, boolean, boolean])[] = [
    ['ping_server', 2, false, true],
    ['get_blocks', 3, true, true],
    ['scan_orthographic_view', 4, true, true],
    ['scan_orthographic_view', 5, true, true],
    ['fill_region', 6, true, false],
    ['get_server_status', 7, false, true],
    ['set_blocks', 8, true, true],
    ['count_region_block_states', 10, true, true],
    ['replace_region_blocks', 11, true, true],
    ['get_edit_history', 12, true, true],
    ['undo_edit', 13, true, true],
    ['get_player_context', 14, false, true],
    ['get_perspective_view', 15, false, true],
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

test('runs fail-fast Minecraft command batches with in-band failures and strict response correlation', async (context) => {
  const configuredTools = toolConfiguration(false);
  configuredTools.run_minecraft_commands = true;
  const requests: BridgeRequestRecord[] = [];
  let commandRequestCount = 0;
  const secretCommand = 'say SUPER_SECRET_COMMAND_PAYLOAD_73';
  const secretFeedback = 'SUPER_SECRET_FEEDBACK_91';
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
    if (request.url === '/v1/server-status') {
      response.end(JSON.stringify(minimalServerStatus(configuredTools)));
      return;
    }

    assert.equal(request.url, '/v1/run-minecraft-commands');
    commandRequestCount++;
    if (commandRequestCount === 1) {
      response.end(
        JSON.stringify({
          sender: { name: 'FeedbackForwardingSender', isOperator: true, isPlayer: false },
          feedbackTruncated: false,
          results: [
            {
              command: secretCommand,
              outcome: 'dispatched',
              feedback: [secretFeedback],
              message: null,
              rawMessage: null,
            },
          ],
        }),
      );
    } else if (commandRequestCount === 2) {
      response.end(
        JSON.stringify({
          sender: { name: 'FeedbackForwardingSender', isOperator: true, isPlayer: false },
          feedbackTruncated: true,
          results: [
            {
              command: 'say ready',
              outcome: 'dispatched',
              feedback: [],
              message: null,
              rawMessage: null,
            },
            {
              command: 'bad syntax',
              outcome: 'dispatch_failed',
              feedback: ['Usage: /bad'],
              message: 'Incorrect argument',
              rawMessage: 'Unhandled exception executing command',
            },
          ],
        }),
      );
    } else if (commandRequestCount === 3) {
      response.end(
        JSON.stringify({
          sender: { name: 'FeedbackForwardingSender', isOperator: true, isPlayer: false },
          feedbackTruncated: false,
          results: [
            {
              command: 'missing final',
              outcome: 'not_found',
              feedback: [],
              message: 'Paper found no target for this command',
              rawMessage: null,
            },
          ],
        }),
      );
    } else {
      response.end(
        JSON.stringify({
          sender: { name: 'FeedbackForwardingSender', isOperator: true, isPlayer: false },
          feedbackTruncated: false,
          results: [
            {
              command: 'say wrong',
              outcome: 'dispatched',
              feedback: [],
              message: null,
              rawMessage: null,
            },
          ],
        }),
      );
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
  const logs = collectLines(child.stderr, parseLogRecord);

  send(child, { jsonrpc: '2.0', id: 60, method: 'server/discover', params: requestParams({}) });
  const discovered = await waitFor(messages, 60);
  assert.match(String(discovered.result?.instructions), /operator-level non-player sender/);
  assert.match(String(discovered.result?.instructions), /stops at the first per-command failure/);
  assert.match(String(discovered.result?.instructions), /non-empty attempted prefix/);
  assert.match(String(discovered.result?.instructions), /outside Dirt edit history and undo/);
  assert.match(
    String(discovered.result?.instructions),
    /timeout, disconnect, or unexpected internal failure can leave completion ambiguous/i,
  );
  assert.doesNotMatch(String(discovered.result?.instructions), /dryRun|get_server_status/);

  send(child, { jsonrpc: '2.0', id: 61, method: 'tools/list', params: requestParams({}) });
  const catalog = await waitFor(messages, 61);
  assert.deepEqual(
    catalog.result?.tools?.map((tool) => tool.name),
    ['run_minecraft_commands'],
  );
  configuredTools.run_minecraft_commands = false;
  send(child, { jsonrpc: '2.0', id: 62, method: 'tools/list', params: requestParams({}) });
  const fixedCatalog = await waitFor(messages, 62);
  assert.deepEqual(
    fixedCatalog.result?.tools?.map((tool) => tool.name),
    ['run_minecraft_commands'],
  );

  const firstInput = { commands: [` /${secretCommand} `] };
  send(child, {
    jsonrpc: '2.0',
    id: 63,
    method: 'tools/call',
    params: requestParams({ name: 'run_minecraft_commands', arguments: firstInput }),
  });
  const succeeded = await waitFor(messages, 63);
  const successfulCommandOutput = {
    sender: { name: 'FeedbackForwardingSender', isOperator: true, isPlayer: false },
    feedbackTruncated: false,
    results: [
      {
        command: secretCommand,
        outcome: 'dispatched',
        feedback: [secretFeedback],
        message: null,
        rawMessage: null,
      },
    ],
  };
  assert.deepEqual(
    succeeded.result,
    completeResult({
      content: [{ type: 'text', text: 'Dispatched 1 command in order.' }],
      structuredContent: successfulCommandOutput,
    }),
  );

  const mixedInput = { commands: ['say ready', 'bad syntax', 'say skipped'] };
  send(child, {
    jsonrpc: '2.0',
    id: 64,
    method: 'tools/call',
    params: requestParams({ name: 'run_minecraft_commands', arguments: mixedInput }),
  });
  const mixed = await waitFor(messages, 64);
  assert.equal(mixed.result?.isError, true);
  assert.deepEqual(mixed.result?.content, [
    {
      type: 'text',
      text: 'Command 2 of 3 failed during dispatch. 1 prior command was dispatched. 1 remaining command was not attempted.',
    },
  ]);
  assert.deepEqual(mixed.result?.structuredContent, {
    sender: { name: 'FeedbackForwardingSender', isOperator: true, isPlayer: false },
    feedbackTruncated: true,
    results: [
      {
        command: 'say ready',
        outcome: 'dispatched',
        feedback: [],
        message: null,
        rawMessage: null,
      },
      {
        command: 'bad syntax',
        outcome: 'dispatch_failed',
        feedback: ['Usage: /bad'],
        message: 'Incorrect argument',
        rawMessage: 'Unhandled exception executing command',
      },
    ],
  });

  const finalFailureInput = { commands: ['missing final'] };
  send(child, {
    jsonrpc: '2.0',
    id: 65,
    method: 'tools/call',
    params: requestParams({ name: 'run_minecraft_commands', arguments: finalFailureInput }),
  });
  const finalFailure = await waitFor(messages, 65);
  assert.equal(finalFailure.result?.isError, true);
  assert.deepEqual(finalFailure.result?.content, [
    { type: 'text', text: 'Command 1 of 1 was not found. No prior commands were dispatched.' },
  ]);
  assert.deepEqual(finalFailure.result?.structuredContent, {
    sender: { name: 'FeedbackForwardingSender', isOperator: true, isPlayer: false },
    feedbackTruncated: false,
    results: [
      {
        command: 'missing final',
        outcome: 'not_found',
        feedback: [],
        message: 'Paper found no target for this command',
        rawMessage: null,
      },
    ],
  });

  send(child, {
    jsonrpc: '2.0',
    id: 66,
    method: 'tools/call',
    params: requestParams({ name: 'run_minecraft_commands', arguments: { commands: ['say expected'] } }),
  });
  const mismatched = await waitFor(messages, 66);
  assert.equal(mismatched.result?.isError, true);
  assert.equal(mismatched.result?.structuredContent?.error?.code, 'bridge_invalid_response');
  assert.equal(
    mismatched.result?.structuredContent?.error?.message,
    'Paper bridge response was not the requested fail-fast command prefix.',
  );

  const requestCountBeforeInvalidInput = requests.length;
  send(child, {
    jsonrpc: '2.0',
    id: 67,
    method: 'tools/call',
    params: requestParams({
      name: 'run_minecraft_commands',
      arguments: { commands: ['say first\nsay second'] },
    }),
  });
  const invalid = await waitFor(messages, 67);
  assert.equal(invalid.result?.isError, true);
  assert.equal(invalid.result?.structuredContent, undefined);
  assert.equal(requests.length, requestCountBeforeInvalidInput);

  assert.deepEqual(
    requests.map(({ method, path }) => ({ method, path })),
    [
      { method: 'GET', path: '/v1/server-status' },
      { method: 'POST', path: '/v1/run-minecraft-commands' },
      { method: 'POST', path: '/v1/run-minecraft-commands' },
      { method: 'POST', path: '/v1/run-minecraft-commands' },
      { method: 'POST', path: '/v1/run-minecraft-commands' },
    ],
  );
  assert.deepEqual(requestAt(requests, 1).body, firstInput);
  assert.deepEqual(requestAt(requests, 2).body, mixedInput);
  assert.deepEqual(requestAt(requests, 3).body, finalFailureInput);
  for (const request of requests.slice(1)) {
    assert.equal(request.headers['content-type'], 'application/json');
    assert.equal(typeof request.headers['x-dirt-call-id'], 'string');
  }

  await waitForValue(
    logs,
    (record) =>
      record.event === 'tool.completed' && record.operation === 'run_minecraft_commands' && record.request_id === 66,
    'mismatched command response audit',
  );
  const commandAudits = logs.values.filter(
    (record) => record.event === 'tool.completed' && record.operation === 'run_minecraft_commands',
  );
  assert.deepEqual(
    commandAudits.map((record) => ({
      level: record.level,
      requestId: record.request_id,
      success: record.success,
      outcome: record.outcome,
      resultCount: record.result_count,
    })),
    [
      { level: 'info', requestId: 63, success: true, outcome: 'dispatched', resultCount: 1 },
      { level: 'warning', requestId: 64, success: false, outcome: 'partial_failure', resultCount: 2 },
      { level: 'warning', requestId: 65, success: false, outcome: 'partial_failure', resultCount: 1 },
      { level: 'error', requestId: 66, success: false, outcome: undefined, resultCount: undefined },
    ],
  );
  const serializedLogs = JSON.stringify(logs.values);
  assert.equal(serializedLogs.includes(secretCommand), false);
  assert.equal(serializedLogs.includes(secretFeedback), false);

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});

test('serves the configured tool snapshot over MCP 2025-06-18 and rejects disabled calls locally', async (context) => {
  const configuredTools = toolConfiguration(false);
  configuredTools.ping_server = true;
  configuredTools.get_blocks = true;
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
    response.end(JSON.stringify(request.url === '/v1/ping' ? { status: 'ok' } : minimalServerStatus(configuredTools)));
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

  send(child, {
    jsonrpc: '2.0',
    id: 20,
    method: 'initialize',
    params: {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'legacy-test', version: '1' },
    },
  });
  const initialized = await waitFor(messages, 20);
  assert.equal(initialized.jsonrpc, '2.0');
  assert.equal(initialized.result?.protocolVersion, '2025-06-18');
  assert.deepEqual(initialized.result?.capabilities, { tools: { listChanged: false } });
  assert.deepEqual(initialized.result?.serverInfo, { name: 'dirt-mcp', version: packageMetadata.version });
  assert.equal(initialized.result?.resultType, undefined);
  assert.equal(Object.hasOwn(initialized.result ?? {}, '_meta'), false);
  const instructions = initialized.result?.instructions;
  assert.equal(typeof instructions, 'string');
  assert.match(instructions as string, /live Paper worlds/);
  assert.match(instructions as string, /Mutation tools can apply immediately/);
  assert.match(instructions as string, /callId at the structuredContent root/);
  assert.match(instructions as string, /structuredContent\.error/);
  assert.match(instructions as string, /Correctable failures also include strict code-specific details/);
  assert.match(instructions as string, /undo_edit/);
  assert.doesNotMatch(instructions as string, /Enabled tools|Available inspection tools/);
  assert.doesNotMatch(instructions as string, /get_server_status|get_edit_history/);

  send(child, { jsonrpc: '2.0', method: 'notifications/initialized', params: {} });
  send(child, { jsonrpc: '2.0', id: 21, method: 'tools/list', params: {} });
  const catalog = await waitFor(messages, 21);
  assert.ok(catalog.result);
  assert.equal(catalog.jsonrpc, '2.0');
  assert.equal(catalog.result.resultType, undefined);
  assert.equal(Object.hasOwn(catalog.result, '_meta'), false);
  assert.deepEqual(
    catalog.result.tools?.map((tool) => tool.name),
    ['ping_server', 'get_blocks', 'set_blocks', 'undo_edit'],
  );
  const listedMetadata = JSON.stringify(catalog.result.tools);
  assert.doesNotMatch(listedMetadata, /get_server_status|get_edit_history/);

  send(child, {
    jsonrpc: '2.0',
    id: 22,
    method: 'tools/call',
    params: { name: 'ping_server', arguments: {} },
  });
  const pinged = await waitFor(messages, 22);
  assert.equal(pinged.jsonrpc, '2.0');
  assert.deepEqual(pinged.result?.content, [{ type: 'text', text: 'ok' }]);
  assert.deepEqual(pinged.result?.structuredContent, { status: 'ok' });
  assert.equal(pinged.result?.resultType, undefined);
  assert.equal(Object.hasOwn(pinged.result ?? {}, '_meta'), false);

  const requestCountBeforeDisabledCall = requests.length;
  send(child, {
    jsonrpc: '2.0',
    id: 23,
    method: 'tools/call',
    params: { name: 'get_server_status', arguments: {} },
  });
  const disabled = await waitFor(messages, 23);
  assert.equal(disabled.error?.code, -32_602);
  assert.match(disabled.error?.message ?? '', /Tool get_server_status disabled/);
  assert.equal(requests.length, requestCountBeforeDisabledCall);
  assert.equal(requests.length, 2);
  assert.equal(requestAt(requests, 0).path, '/v1/server-status');
  assert.equal(requestAt(requests, 1).path, '/v1/ping');

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
        response.end(
          JSON.stringify({
            error: {
              code: 'unauthorized',
              message: 'bad token',
              details: { reason: 'authentication_failed' },
            },
          }),
        );
      } else if (bootstrapFailure === 'domain-error') {
        response.statusCode = 404;
        response.setHeader('Content-Type', 'application/json');
        response.end(
          JSON.stringify({
            error: {
              code: 'world_not_found',
              message: 'unexpected route result',
              details: { world: 'world' },
            },
          }),
        );
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
    [],
  );
  assert.equal(bootstrapAttempts, 2);

  send(child, { jsonrpc: '2.0', id: 42, method: 'server/discover', params: requestParams({}) });
  const discovered = await waitFor(messages, 42);
  assert.equal(discovered.result?.instructions, 'No Dirt MCP tools are enabled for this server.');

  configuredTools.get_server_status = true;
  send(child, { jsonrpc: '2.0', id: 43, method: 'tools/list', params: requestParams({}) });
  const fixedSnapshot = await waitFor(messages, 43);
  assert.ok(fixedSnapshot.result);
  assert.deepEqual(
    fixedSnapshot.result.tools?.map((tool) => tool.name),
    [],
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
      response.end(
        JSON.stringify({
          error: {
            code: 'unauthorized',
            message: 'bad token',
            details: { reason: 'authentication_failed' },
          },
        }),
      );
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
  const unauthorizedCallId = unauthorized.result.structuredContent?.callId;
  assert.ok(typeof unauthorizedCallId === 'string');
  assert.match(unauthorizedCallId, uuidV4Pattern);
  assert.deepEqual(unauthorized.result.structuredContent, {
    callId: unauthorizedCallId,
    error: {
      code: 'bridge_unauthorized',
      message: 'Paper bridge rejected DIRT_MCP_BRIDGE_TOKEN.',
      details: { reason: 'authentication_failed' },
    },
  });

  behavior = 'invalid';
  const invalid = await callPing(11);
  assert.ok(invalid.result);
  assert.equal(invalid.result.isError, true);
  const invalidCallId = invalid.result.structuredContent?.callId;
  assert.ok(typeof invalidCallId === 'string');
  assert.match(invalidCallId, uuidV4Pattern);
  assert.deepEqual(invalid.result.structuredContent, {
    callId: invalidCallId,
    error: {
      code: 'bridge_invalid_response',
      message: 'Paper bridge response did not match the documented schema.',
    },
  });

  behavior = 'malformed';
  const malformed = await callPing(12);
  assert.ok(malformed.result);
  assert.equal(malformed.result.isError, true);
  const malformedCallId = malformed.result.structuredContent?.callId;
  assert.ok(typeof malformedCallId === 'string');
  assert.match(malformedCallId, uuidV4Pattern);
  assert.deepEqual(malformed.result.structuredContent, {
    callId: malformedCallId,
    error: {
      code: 'bridge_invalid_response',
      message: 'Paper bridge returned invalid JSON.',
    },
  });

  behavior = 'unstructured';
  const unstructured = await callPing(13);
  assert.ok(unstructured.result);
  assert.equal(unstructured.result.isError, true);
  const unstructuredCallId = unstructured.result.structuredContent?.callId;
  assert.ok(typeof unstructuredCallId === 'string');
  assert.match(unstructuredCallId, uuidV4Pattern);
  assert.deepEqual(unstructured.result.structuredContent, {
    callId: unstructuredCallId,
    error: {
      code: 'bridge_http_error',
      message: 'Paper bridge returned unstructured HTTP 502.',
      details: { status: 502 },
    },
  });

  await new Promise<void>((resolve, reject) => {
    bridge.close((error) => (error === undefined ? resolve() : reject(error)));
  });
  const unavailable = await callPing(14);
  assert.ok(unavailable.result);
  assert.equal(unavailable.result.isError, true);
  const unavailableCallId = unavailable.result.structuredContent?.callId;
  assert.ok(typeof unavailableCallId === 'string');
  assert.match(unavailableCallId, uuidV4Pattern);
  const unavailableMessage = unavailable.result.structuredContent?.error?.message;
  assert.ok(typeof unavailableMessage === 'string');
  assert.deepEqual(unavailable.result.structuredContent, {
    callId: unavailableCallId,
    error: {
      code: 'bridge_unavailable',
      message: unavailableMessage,
      details: { reason: 'request_failed' },
    },
  });

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});
