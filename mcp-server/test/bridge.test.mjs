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
  const health = {
    status: 'ok',
    service: 'dirt-mcp-paper',
    version: '0.1.0-test',
    minecraftVersion: '26.2',
  };
  const inspection = {
    world: 'world',
    bounds: { min: { x: 1, y: 2, z: 3 }, max: { x: 2, y: 2, z: 3 } },
    volume: 2,
    matchedBlocks: 1,
    mode: 'blocks',
    blocks: [{ position: { x: 1, y: 2, z: 3 }, state: 'minecraft:stone' }],
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
    if (request.url === '/v1/health') {
      response.end(JSON.stringify(health));
    } else if (request.url === '/v1/inspect-blocks') {
      response.end(JSON.stringify(inspection));
    } else if (request.url === '/v1/fill-region') {
      response.statusCode = 413;
      response.end(JSON.stringify({
        error: { code: 'change_limit_exceeded', message: 'Too many changes' },
      }));
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
    params: modernParams({ name: 'dirt_status', arguments: {} }),
  });
  const status = await waitFor(messages, 2);
  assert.deepEqual(status.result, modernResult({
    content: [{ type: 'text', text: JSON.stringify(health, null, 2) }],
    structuredContent: health,
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
    params: modernParams({ name: 'inspect_blocks', arguments: region }),
  });
  const inspected = await waitFor(messages, 3);
  assert.deepEqual(inspected.result, modernResult({
    content: [{ type: 'text', text: JSON.stringify(inspection, null, 2) }],
    structuredContent: inspection,
  }));

  send(child, {
    jsonrpc: '2.0',
    id: 4,
    method: 'tools/call',
    params: modernParams({
      name: 'fill_region',
      arguments: { ...region, destination: 'minecraft:dirt' },
    }),
  });
  const failedFill = await waitFor(messages, 4);
  assert.deepEqual(failedFill.result, modernResult({
    isError: true,
    content: [{
      type: 'text',
      text: 'Could not fill the region: change_limit_exceeded: Too many changes',
    }],
  }));
  await waitForValue(errors, (line) => line.includes('tool=fill_region'));

  assert.deepEqual(
    requests.map(({ method, path }) => ({ method, path })),
    [
      { method: 'GET', path: '/v1/health' },
      { method: 'POST', path: '/v1/inspect-blocks' },
      { method: 'POST', path: '/v1/fill-region' },
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
  assert.equal(new Set(callIds).size, 3);
  assert.deepEqual(requests[1].body, {
    ...region,
    include: [],
    exclude: [],
    includeAir: false,
    maxResults: 10_000,
    mode: 'blocks',
  });
  assert.equal(requests[1].headers['content-type'], 'application/json');
  assert.deepEqual(requests[2].body, {
    ...region,
    destination: 'minecraft:dirt',
    dryRun: false,
  });
  assert.equal(requests[2].headers['content-type'], 'application/json');

  const auditLines = errors.values.filter((line) => line.startsWith('Dirt MCP tool_call '));
  assert.equal(auditLines.length, 3);
  assert.match(
    auditLines[0],
    new RegExp(`^Dirt MCP tool_call tool=dirt_status call=${callIds[0]} request=2 `
      + 'client="bridge-test/1" outcome=ok duration_ms=\\d+$'),
  );
  assert.match(
    auditLines[1],
    new RegExp(`^Dirt MCP tool_call tool=inspect_blocks call=${callIds[1]} request=3 `
      + 'client="bridge-test/1" world="world" outcome=ok duration_ms=\\d+$'),
  );
  assert.match(
    auditLines[2],
    new RegExp(`^Dirt MCP tool_call tool=fill_region call=${callIds[2]} request=4 `
      + 'client="bridge-test/1" world="world" outcome=error duration_ms=\\d+$'),
  );

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});
