import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { createInterface } from 'node:readline';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import {
  CLIENT_CAPABILITIES_META_KEY,
  CLIENT_INFO_META_KEY,
  PROTOCOL_VERSION_META_KEY,
  SERVER_INFO_META_KEY,
} from '@modelcontextprotocol/server';

const packageDirectory = dirname(dirname(fileURLToPath(import.meta.url)));

function collectLines(stream, parse) {
  const collected = { values: [], waiters: [] };
  createInterface({ input: stream }).on('line', (line) => {
    const value = parse(line);
    collected.values.push(value);
    collected.waiters = collected.waiters.filter((waiter) => !waiter(value));
  });
  return collected;
}

function waitForValue(collected, predicate, timeoutMilliseconds = 5_000) {
  const existing = collected.values.find(predicate);
  if (existing !== undefined) {
    return Promise.resolve(existing);
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      collected.waiters = collected.waiters.filter((waiter) => waiter !== receive);
      reject(new Error('Timed out waiting for MCP process output'));
    }, timeoutMilliseconds);
    const receive = (value) => {
      if (!predicate(value)) {
        return false;
      }
      clearTimeout(timer);
      resolve(value);
      return true;
    };
    collected.waiters.push(receive);
  });
}

function waitFor(messages, id) {
  return waitForValue(messages, (message) => message.id === id);
}

function send(child, message) {
  child.stdin.write(`${JSON.stringify(message)}\n`);
}

function modernParams(params) {
  return {
    ...params,
    _meta: {
      [PROTOCOL_VERSION_META_KEY]: '2026-07-28',
      [CLIENT_INFO_META_KEY]: { name: 'bridge-test', version: '1' },
      [CLIENT_CAPABILITIES_META_KEY]: {},
    },
  };
}

function modernResult(result) {
  return {
    ...result,
    resultType: 'complete',
    _meta: {
      [SERVER_INFO_META_KEY]: { name: 'dirt-mcp', version: '0.1.0' },
    },
  };
}

test('forwards MCP tools to the authenticated bridge and preserves contract errors', async (context) => {
  const requests = [];
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
      entries: [{
        name: 'Builder',
        world: 'world',
        gameMode: 'creative',
        blockPosition: { x: 12, y: 70, z: -4 },
      }],
    },
    worlds: [{
      name: 'world',
      environment: 'normal',
      minY: -64,
      maxY: 319,
      spawn: { x: 0, y: 64, z: 0 },
      timeOfDay: 6000,
      storm: false,
      thundering: false,
      playerCount: 1,
    }],
    limits: {
        maxRegionVolume: 1_000_000,
        maxChangedBlocks: 250_000,
        maxRegionBlocksVolume: 32_768,
        defaultRegionBlocksResultLimit: 10_000,
        maxRegionBlocksResultLimit: 10_000,
        maxOrthographicViewVolume: 32_768,
        defaultOrthographicViewResultLimit: 2_048,
        maxOrthographicViewResultLimit: 10_000,
        maxCommandsPerRequest: 20,
        maxCommandFeedbackCharacters: 32_768,
        undoHistoryPerWorld: 20,
    },
    defaults: {
        regionBlocksIncludeAir: false,
        regionBlocksFormat: 'blocks',
        replaceRegionBlocksDryRun: false,
        fillRegionDryRun: false,
        setBlocksDryRun: false,
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
    blockStatePalette: [
      'minecraft:stone',
      'minecraft:oak_stairs[facing=north]',
      'minecraft:gold_block',
    ],
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
      },
      {
        command: 'missing',
        outcome: 'not_found',
        feedback: [],
        message: 'Paper found no target for this command',
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
    } else if (request.url === '/v1/get-region-blocks') {
      response.end(JSON.stringify(regionBlocks));
    } else if (request.url === '/v1/scan-orthographic-view') {
      response.end(JSON.stringify(view));
    } else if (request.url === '/v1/fill-region') {
      response.statusCode = 413;
      response.end(JSON.stringify({
        error: { code: 'change_limit_exceeded', message: 'Too many changes' },
      }));
    } else if (request.url === '/v1/set-blocks') {
      response.end(JSON.stringify(setBlocks));
    } else if (request.url === '/v1/run-minecraft-commands') {
      response.end(JSON.stringify(commandRun));
    } else {
      response.statusCode = 404;
      response.end('{}');
    }
  });
  bridge.listen(0, '127.0.0.1');
  await once(bridge, 'listening');
  context.after(() => new Promise((resolve, reject) => {
    bridge.close((error) => (error === undefined ? resolve() : reject(error)));
  }));

  const address = bridge.address();
  const child = spawn(process.execPath, [join(packageDirectory, 'dist/index.js')], {
    env: {
      ...process.env,
      DIRT_MCP_BRIDGE_TOKEN: 'bridge-test-token',
      DIRT_MCP_BRIDGE_URL: `http://127.0.0.1:${address.port}`,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  context.after(() => {
    if (child.exitCode === null) {
      child.kill();
    }
  });
  const messages = collectLines(child.stdout, (line) => JSON.parse(line));
  const errors = collectLines(child.stderr, (line) => line);

  send(child, {
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/call',
    params: modernParams({ name: 'ping_server', arguments: {} }),
  });
  const pinged = await waitFor(messages, 2);
  assert.deepEqual(pinged.result, modernResult({
    content: [{ type: 'text', text: 'ok' }],
    structuredContent: ping,
  }));

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
  assert.deepEqual(inspected.result, modernResult({
    content: [{ type: 'text', text: 'Matching blocks: 1; block entries: 1; world: world.' }],
    structuredContent: regionBlocks,
  }));

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
  assert.deepEqual(viewed.result, modernResult({
    content: [{ type: 'text', text: 'Scanned view in world: 3 visible blocks returned explicitly.' }],
    structuredContent: view,
  }));

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
  assert.deepEqual(gridViewed.result, modernResult({
    content: [{
      type: 'text',
      text: 'Scanned 3x3 view: 3 visible blocks using 3 block states.',
    }],
    structuredContent: gridView,
  }));

  send(child, {
    jsonrpc: '2.0',
    id: 6,
    method: 'tools/call',
    params: modernParams({
      name: 'fill_region',
      arguments: { ...region, blockState: 'minecraft:dirt' },
    }),
  });
  const failedFill = await waitFor(messages, 6);
  assert.deepEqual(failedFill.result, modernResult({
    isError: true,
    content: [{
      type: 'text',
      text: 'Could not fill the region: Too many changes',
    }],
    structuredContent: {
      error: { code: 'change_limit_exceeded', message: 'Too many changes' },
    },
  }));
  await waitForValue(errors, (line) => line.includes('tool=fill_region'));

  send(child, {
    jsonrpc: '2.0',
    id: 7,
    method: 'tools/call',
    params: modernParams({ name: 'get_server_status', arguments: {} }),
  });
  const status = await waitFor(messages, 7);
  assert.deepEqual(status.result, modernResult({
    content: [{
      type: 'text',
      text: 'Paper 26.2-112-main; players 1/20; loaded worlds: world.',
    }],
    structuredContent: serverStatus,
  }));

  const sparseChanges = [
    { position: { x: 1, y: 2, z: 3 }, blockState: 'minecraft:stone' },
    { position: { x: 5, y: 2, z: 3 }, blockState: 'minecraft:glass' },
  ];
  send(child, {
    jsonrpc: '2.0',
    id: 8,
    method: 'tools/call',
    params: modernParams({
      name: 'set_blocks',
      arguments: { world: 'world', changes: sparseChanges },
    }),
  });
  const blocksSet = await waitFor(messages, 8);
  assert.deepEqual(blocksSet.result, modernResult({
    content: [{
      type: 'text',
      text: 'Changed 1 of 2 explicitly listed blocks in world.',
    }],
    structuredContent: setBlocks,
  }));

  send(child, {
    jsonrpc: '2.0',
    id: 9,
    method: 'tools/call',
    params: modernParams({
      name: 'run_minecraft_commands',
      arguments: { commands: ['/say hello', 'missing'] },
    }),
  });
  const commands = await waitFor(messages, 9);
  assert.deepEqual(commands.result, modernResult({
    isError: true,
    content: [{
      type: 'text',
      text: 'Dispatched 1 of 2 command(s); see per-command outcomes.',
    }],
    structuredContent: commandRun,
  }));

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
    ],
  );
  for (const request of requests) {
    assert.equal(request.headers.authorization, 'Bearer bridge-test-token');
    assert.equal(request.headers.accept, 'application/json');
    assert.match(
      request.headers['x-dirt-call-id'],
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
  }
  const callIds = requests.map((request) => request.headers['x-dirt-call-id']);
  assert.equal(new Set(callIds).size, 8);
  assert.deepEqual(requests[1].body, {
    ...region,
    includeBlockStatePatterns: [],
    excludeBlockStatePatterns: [],
  });
  assert.equal(requests[1].headers['content-type'], 'application/json');
  assert.deepEqual(requests[2].body, viewInput);
  assert.equal(requests[2].headers['content-type'], 'application/json');
  assert.deepEqual(requests[3].body, viewInput);
  assert.equal(requests[3].headers['content-type'], 'application/json');
  assert.deepEqual(requests[4].body, {
    ...region,
    blockState: 'minecraft:dirt',
  });
  assert.equal(requests[4].headers['content-type'], 'application/json');
  assert.deepEqual(requests[6].body, { world: 'world', changes: sparseChanges });
  assert.equal(requests[6].headers['content-type'], 'application/json');
  assert.deepEqual(requests[7].body, { commands: ['/say hello', 'missing'] });
  assert.equal(requests[7].headers['content-type'], 'application/json');

  const auditLines = errors.values.filter((line) => line.startsWith('Dirt MCP tool_call '));
  assert.equal(auditLines.length, 8);
  assert.match(
    auditLines[0],
    new RegExp(`^Dirt MCP tool_call tool=ping_server call=${callIds[0]} request=2 `
      + 'client="bridge-test/1" outcome=ok duration_ms=\\d+$'),
  );
  assert.match(
    auditLines[1],
    new RegExp(`^Dirt MCP tool_call tool=get_region_blocks call=${callIds[1]} request=3 `
      + 'client="bridge-test/1" world="world" outcome=ok duration_ms=\\d+$'),
  );
  assert.match(
    auditLines[2],
    new RegExp(`^Dirt MCP tool_call tool=scan_orthographic_view call=${callIds[2]} request=4 `
      + 'client="bridge-test/1" world="world" outcome=ok duration_ms=\\d+$'),
  );
  assert.match(
    auditLines[3],
    new RegExp(`^Dirt MCP tool_call tool=scan_orthographic_view call=${callIds[3]} request=5 `
      + 'client="bridge-test/1" world="world" outcome=ok duration_ms=\\d+$'),
  );
  assert.match(
    auditLines[4],
    new RegExp(`^Dirt MCP tool_call tool=fill_region call=${callIds[4]} request=6 `
      + 'client="bridge-test/1" world="world" outcome=error duration_ms=\\d+$'),
  );
  assert.match(
    auditLines[5],
    new RegExp(`^Dirt MCP tool_call tool=get_server_status call=${callIds[5]} request=7 `
      + 'client="bridge-test/1" outcome=ok duration_ms=\\d+$'),
  );
  assert.match(
    auditLines[6],
    new RegExp(`^Dirt MCP tool_call tool=set_blocks call=${callIds[6]} request=8 `
      + 'client="bridge-test/1" world="world" outcome=ok duration_ms=\\d+$'),
  );
  assert.match(
    auditLines[7],
    new RegExp(`^Dirt MCP tool_call tool=run_minecraft_commands call=${callIds[7]} request=9 `
      + 'client="bridge-test/1" outcome=error duration_ms=\\d+$'),
  );

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
      DIRT_MCP_BRIDGE_URL: `http://127.0.0.1:${address.port}`,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  context.after(async () => {
    if (child.exitCode === null) child.kill();
    if (bridge.listening) {
      await new Promise((resolve, reject) => {
        bridge.close((error) => (error === undefined ? resolve() : reject(error)));
      });
    }
  });
  const messages = collectLines(child.stdout, (line) => JSON.parse(line));

  async function callPing(id) {
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

  behavior = 'unstructured';
  const unstructured = await callPing(12);
  assert.equal(unstructured.result.isError, true);
  assert.deepEqual(unstructured.result.structuredContent, {
    error: {
      code: 'bridge_http_error',
      message: 'Paper bridge returned unstructured HTTP 502.',
    },
  });

  await new Promise((resolve, reject) => {
    bridge.close((error) => (error === undefined ? resolve() : reject(error)));
  });
  const unavailable = await callPing(13);
  assert.equal(unavailable.result.isError, true);
  assert.equal(unavailable.result.structuredContent.error.code, 'bridge_unavailable');

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});
