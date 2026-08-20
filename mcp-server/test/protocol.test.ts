import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { collectLines, modernParams, send, waitForValue } from './support/mcp-process.ts';

const packageDirectory = dirname(dirname(fileURLToPath(import.meta.url)));

interface ProtocolResponse {
  readonly error?: {
    readonly code: number;
    readonly data?: { readonly requested?: string; readonly supported?: readonly string[] };
    readonly message: string;
  };
  readonly id: number;
  readonly result?: {
    readonly capabilities?: { readonly tools?: { readonly listChanged?: boolean } };
    readonly supportedVersions?: readonly string[];
  };
}

function spawnDirtServer() {
  return spawn(process.execPath, [join(packageDirectory, 'dist/index.js')], {
    env: { ...process.env, DIRT_MCP_BRIDGE_TOKEN: 'protocol-test-token' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
}

test('discovers only the current protocol and a static tool catalog', async (context) => {
  const child = spawnDirtServer();
  context.after(() => {
    if (child.exitCode === null) child.kill();
  });
  const messages = collectLines(child.stdout, (line) => JSON.parse(line) as ProtocolResponse);

  send(child, {
    jsonrpc: '2.0',
    id: 1,
    method: 'server/discover',
    params: modernParams({}),
  });
  const response = await waitForValue(messages, (message) => message.id === 1);
  assert.deepEqual(response.result?.supportedVersions, ['2026-07-28']);
  assert.equal(response.result?.capabilities?.tools?.listChanged, false);

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});

test('rejects legacy MCP initialization', async (context) => {
  const child = spawnDirtServer();
  context.after(() => {
    if (child.exitCode === null) child.kill();
  });
  const messages = collectLines(child.stdout, (line) => JSON.parse(line) as ProtocolResponse);

  send(child, {
    jsonrpc: '2.0',
    id: 2,
    method: 'initialize',
    params: {
      protocolVersion: '2025-11-25',
      capabilities: {},
      clientInfo: { name: 'legacy-test', version: '1' },
    },
  });
  const response = await waitForValue(messages, (message) => message.id === 2);
  assert.equal(response.error?.code, -32_022);
  assert.equal(response.error?.message, 'Unsupported protocol version: 2025-11-25');
  assert.deepEqual(response.error?.data, { supported: ['2026-07-28'], requested: '2025-11-25' });

  const exited = once(child, 'exit');
  child.stdin.end();
  const [exitCode] = await exited;
  assert.equal(exitCode, 0);
});
