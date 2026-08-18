import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { createInterface } from 'node:readline';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const packageDirectory = dirname(dirname(fileURLToPath(import.meta.url)));

function collectMessages(stream) {
  const messages = { values: [], waiters: [] };
  createInterface({ input: stream }).on('line', (line) => {
    const message = JSON.parse(line);
    messages.values.push(message);
    messages.waiters = messages.waiters.filter((waiter) => !waiter(message));
  });
  return messages;
}

function waitFor(messages, id, timeoutMilliseconds = 5_000) {
  const existing = messages.values.find((message) => message.id === id);
  if (existing !== undefined) {
    return Promise.resolve(existing);
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      messages.waiters = messages.waiters.filter((waiter) => waiter !== receive);
      reject(new Error(`Timed out waiting for MCP response ${id}`));
    }, timeoutMilliseconds);
    const receive = (message) => {
      if (message.id !== id) {
        return false;
      }
      clearTimeout(timer);
      resolve(message);
      return true;
    };
    messages.waiters.push(receive);
  });
}

function send(child, message) {
  child.stdin.write(`${JSON.stringify(message)}\n`);
}

async function initialize(child, messages) {
  send(child, {
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: {
      protocolVersion: '2025-11-25',
      capabilities: {},
      clientInfo: { name: 'bridge-test', version: '1' },
    },
  });
  const initialized = await waitFor(messages, 1);
  assert.equal(initialized.result.serverInfo.name, 'dirt-mcp');
  send(child, { jsonrpc: '2.0', method: 'notifications/initialized' });
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
  child.stderr.resume();
  context.after(() => {
    if (child.exitCode === null) {
      child.kill();
    }
  });
  const messages = collectMessages(child.stdout);
  await initialize(child, messages);

  send(child, {
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/call',
    params: { name: 'dirt_status', arguments: {} },
  });
  const status = await waitFor(messages, 2);
  assert.deepEqual(status.result, {
    content: [{ type: 'text', text: JSON.stringify(health, null, 2) }],
    structuredContent: health,
  });

  const region = {
    world: 'world',
    min: { x: 1, y: 2, z: 3 },
    max: { x: 2, y: 2, z: 3 },
  };
  send(child, {
    jsonrpc: '2.0',
    id: 3,
    method: 'tools/call',
    params: { name: 'inspect_blocks', arguments: region },
  });
  const inspected = await waitFor(messages, 3);
  assert.deepEqual(inspected.result, {
    content: [{ type: 'text', text: JSON.stringify(inspection, null, 2) }],
    structuredContent: inspection,
  });

  send(child, {
    jsonrpc: '2.0',
    id: 4,
    method: 'tools/call',
    params: {
      name: 'fill_region',
      arguments: { ...region, destination: 'minecraft:dirt' },
    },
  });
  const failedFill = await waitFor(messages, 4);
  assert.equal(failedFill.result.isError, true);
  assert.deepEqual(failedFill.result.content, [{
    type: 'text',
    text: 'Could not fill the region: change_limit_exceeded: Too many changes',
  }]);

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
  }
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

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});
