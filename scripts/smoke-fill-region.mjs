#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url));
const runDirectory = `${repositoryRoot}/paper-plugin/run`;
const token = (await readFile(`${runDirectory}/.dirt-mcp-token`, 'utf8')).trim();
const state = Object.fromEntries(
  (await readFile(`${runDirectory}/.dirt-mcp-dev-state`, 'utf8'))
    .trim()
    .split('\n')
    .map((line) => line.split('=', 2)),
);
const bridgePort = Number(state.BRIDGE_PORT);
assert.ok(Number.isInteger(bridgePort) && bridgePort > 0 && bridgePort <= 65_535, 'Invalid bridge port');

const world = 'world';
const min = { x: 0, y: 0, z: 0 };
const max = { x: 1, y: 1, z: 1 };
const baseUrl = `http://127.0.0.1:${bridgePort}`;

async function bridgeRequest(path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${path} returned ${response.status}: ${text}`);
  }
  return JSON.parse(text);
}

async function paperCommand(command) {
  await new Promise((resolve, reject) => {
    const child = spawn(`${repositoryRoot}/scripts/dev-paper`, ['command', command], { stdio: 'inherit' });
    child.once('error', reject);
    child.once('exit', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`Paper command exited with status ${code}`));
      }
    });
  });
}

async function waitForRegion(region) {
  let lastError;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      return await bridgeRequest('/v1/inspect-region', region);
    } catch (error) {
      lastError = error;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  }
  throw lastError;
}

function normalizedJson(value) {
  if (Array.isArray(value)) {
    return value.map(normalizedJson);
  }
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, entry]) => [key, normalizedJson(entry)]),
    );
  }
  return value;
}

const region = { world, min, max };
let editNeedsUndo = false;
let fixtureIsForceLoaded = false;
try {
  await paperCommand('forceload add 0 0');
  fixtureIsForceLoaded = true;

  const original = await waitForRegion(region);
  const originalStates = Object.keys(original.blockStates);
  const destination = originalStates.length === 1 && originalStates[0].startsWith('minecraft:barrier')
    ? 'minecraft:amethyst_block'
    : 'minecraft:barrier';

  const preview = await bridgeRequest('/v1/fill-region', { ...region, destination, dryRun: true });
  const alreadyMatching = original.blockStates[preview.destination] ?? 0;
  assert.equal(preview.dryRun, true);
  assert.equal(preview.volume, original.volume);
  assert.equal(preview.changedBlocks, original.volume - alreadyMatching);
  assert.ok(preview.changedBlocks > 0, 'Smoke destination must change at least one block');

  const filled = await bridgeRequest('/v1/fill-region', { ...region, destination });
  editNeedsUndo = filled.changedBlocks > 0;
  assert.equal(filled.dryRun, false);
  assert.equal(filled.destination, preview.destination);
  assert.equal(filled.changedBlocks, preview.changedBlocks);

  const afterFill = await bridgeRequest('/v1/inspect-region', region);
  assert.deepEqual(afterFill.blockStates, { [filled.destination]: filled.volume });

  const noOp = await bridgeRequest('/v1/fill-region', { ...region, destination: filled.destination });
  assert.equal(noOp.changedBlocks, 0);

  const undone = await bridgeRequest('/v1/undo-last-edit', { world });
  editNeedsUndo = false;
  assert.equal(undone.changedBlocks, filled.changedBlocks);

  const restored = await bridgeRequest('/v1/inspect-region', region);
  assert.deepEqual(normalizedJson(restored), normalizedJson(original));
  process.stdout.write(`fill_region smoke test passed in ${world}\n`);
} finally {
  if (editNeedsUndo) {
    try {
      await bridgeRequest('/v1/undo-last-edit', { world });
    } catch (error) {
      process.stderr.write(`Could not restore smoke-test edit: ${error.message}\n`);
    }
  }
  if (fixtureIsForceLoaded) {
    try {
      await paperCommand('forceload remove 0 0');
    } catch (error) {
      process.stderr.write(`Could not release smoke-test chunk: ${error.message}\n`);
    }
  }
}
