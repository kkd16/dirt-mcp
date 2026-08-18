import assert from 'node:assert/strict';
import { copyFile, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import {
  CLIENT_CAPABILITIES_META_KEY,
  CLIENT_INFO_META_KEY,
  PROTOCOL_VERSION_META_KEY,
} from '@modelcontextprotocol/server';

const packageDirectory = dirname(dirname(fileURLToPath(import.meta.url)));
const repositoryRoot = dirname(packageDirectory);

function modernParams(params) {
  return {
    ...params,
    _meta: {
      [PROTOCOL_VERSION_META_KEY]: '2026-07-28',
      [CLIENT_INFO_META_KEY]: { name: 'hot-reload-test', version: '1' },
      [CLIENT_CAPABILITIES_META_KEY]: {},
    },
  };
}

function waitFor(list, predicate, timeoutMilliseconds = 5_000) {
  const existing = list.values.find(predicate);
  if (existing !== undefined) {
    return Promise.resolve(existing);
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      list.waiters = list.waiters.filter((waiter) => waiter !== receive);
      reject(new Error('Timed out waiting for MCP output'));
    }, timeoutMilliseconds);
    const receive = (value) => {
      if (!predicate(value)) {
        return false;
      }
      clearTimeout(timer);
      resolve(value);
      return true;
    };
    list.waiters.push(receive);
  });
}

function collectLines(stream, parse) {
  const collected = { values: [], waiters: [] };
  createInterface({ input: stream }).on('line', (line) => {
    const value = parse(line);
    collected.values.push(value);
    collected.waiters = collected.waiters.filter((waiter) => !waiter(value));
  });
  return collected;
}

test('reloads tools without replacing the stdio process', async (context) => {
  const temporaryDirectory = await mkdtemp(join(repositoryRoot, 'node_modules/.dirt-mcp-reload-'));
  await copyFile(join(packageDirectory, 'dist/index.js'), join(temporaryDirectory, 'index.js'));
  const toolsPath = join(temporaryDirectory, 'tools.js');
  await copyFile(join(packageDirectory, 'dist/tools.js'), toolsPath);

  const child = spawn(process.execPath, [join(temporaryDirectory, 'index.js')], {
    cwd: repositoryRoot,
    env: {
      ...process.env,
      DIRT_MCP_BRIDGE_TOKEN: 'hot-reload-test-token',
      DIRT_MCP_DEV_RELOAD: '1',
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  context.after(async () => {
    if (child.exitCode === null) {
      child.kill();
    }
    await rm(temporaryDirectory, { recursive: true, force: true });
  });

  const messages = collectLines(child.stdout, (line) => JSON.parse(line));
  const errors = collectLines(child.stderr, (line) => line);
  const send = (message) => child.stdin.write(`${JSON.stringify(message)}\n`);

  send({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: modernParams({}) });
  const initial = await waitFor(messages, (message) => message.id === 2);
  assert.equal(initial.result.tools[0].title, 'Dirt MCP status');
  assert.deepEqual(
    initial.result.tools.map((tool) => tool.name).sort(),
    [
      'dirt_status',
      'fill_region',
      'inspect_blocks',
      'inspect_region',
      'replace_blocks',
      'undo_last_edit',
    ],
  );

  const toolsSource = await readFile(toolsPath, 'utf8');
  const changedSource = toolsSource.replace('Dirt MCP status', 'Reloaded Dirt MCP status');
  assert.notEqual(changedSource, toolsSource);
  await writeFile(toolsPath, changedSource);

  await waitFor(errors, (line) => line.includes('Reloaded Dirt MCP tools'));
  send({ jsonrpc: '2.0', id: 3, method: 'tools/list', params: modernParams({}) });
  const reloaded = await waitFor(messages, (message) => message.id === 3);
  assert.equal(reloaded.result.tools[0].title, 'Reloaded Dirt MCP status');

  await writeFile(toolsPath, 'this is not valid JavaScript');
  await waitFor(errors, (line) => line.includes('Could not reload Dirt MCP tools'));
  send({ jsonrpc: '2.0', id: 4, method: 'tools/list', params: modernParams({}) });
  const retained = await waitFor(messages, (message) => message.id === 4);
  assert.equal(retained.result.tools[0].title, 'Reloaded Dirt MCP status');

  child.stdin.end();
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('MCP process did not exit after stdin closed')), 2_000);
    child.once('exit', (code) => {
      clearTimeout(timer);
      assert.equal(code, 0);
      resolve();
    });
  });
});
