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

async function bridgeResponse(path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  const document = JSON.parse(text);
  return { status: response.status, ok: response.ok, body: document };
}

async function bridgeRequest(path, body) {
  const response = await bridgeResponse(path, body);
  if (!response.ok) {
    throw new Error(`${path} returned ${response.status}: ${JSON.stringify(response.body)}`);
  }
  return response.body;
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

function sortedBlockKeys(blocks) {
  return blocks
    .map(({ position, state }) => `${position.x},${position.y},${position.z}:${state}`)
    .sort();
}

function expectedBlockKeys(state) {
  const blocks = [];
  for (let y = min.y; y <= max.y; y += 1) {
    for (let z = min.z; z <= max.z; z += 1) {
      for (let x = min.x; x <= max.x; x += 1) {
        blocks.push(`${x},${y},${z}:${state}`);
      }
    }
  }
  return blocks.sort();
}

function expandedRunKeys(runs) {
  const blocks = [];
  for (const run of runs) {
    assert.ok(run.from.x <= run.to.x && run.from.y <= run.to.y && run.from.z <= run.to.z);
    const varyingAxes = ['x', 'y', 'z'].filter((axis) => run.from[axis] !== run.to[axis]);
    assert.ok(varyingAxes.length <= 1, 'Each run must be axis-aligned');
    for (let y = run.from.y; y <= run.to.y; y += 1) {
      for (let z = run.from.z; z <= run.to.z; z += 1) {
        for (let x = run.from.x; x <= run.to.x; x += 1) {
          blocks.push(`${x},${y},${z}:${run.state}`);
        }
      }
    }
  }
  return blocks.sort();
}

function assertExactBlocks(inspection, state) {
  assert.equal(inspection.mode, 'blocks');
  assert.equal(inspection.volume, 8);
  assert.equal(inspection.matchedBlocks, 8);
  assert.deepEqual(inspection.bounds, { min, max });
  assert.deepEqual(sortedBlockKeys(inspection.blocks), expectedBlockKeys(state));
}

const region = { world, min, max };
let editsToUndo = 0;
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
  editsToUndo += filled.changedBlocks > 0 ? 1 : 0;
  assert.equal(filled.dryRun, false);
  assert.equal(filled.destination, preview.destination);
  assert.equal(filled.changedBlocks, preview.changedBlocks);

  const afterFill = await bridgeRequest('/v1/inspect-region', region);
  assert.deepEqual(afterFill.blockStates, { [filled.destination]: filled.volume });

  const exactBlocks = await bridgeRequest('/v1/inspect-blocks', region);
  assertExactBlocks(exactBlocks, filled.destination);

  const viewRequest = {
    world,
    origin: { x: 0, y: 0, z: 2 },
    direction: 'north',
    horizontalRadius: 0,
    verticalRadius: 0,
    maxDistance: 2,
  };
  const view = await bridgeRequest('/v1/inspect-view', viewRequest);
  assert.equal(view.direction, 'north');
  assert.deepEqual(view.basis, {
    forward: { x: 0, y: 0, z: -1 },
    horizontal: { x: 1, y: 0, z: 0 },
    vertical: { x: 0, y: 1, z: 0 },
  });
  assert.deepEqual(view.viewport, {
    horizontalRadius: 0,
    verticalRadius: 0,
    maxDistance: 2,
  });
  assert.deepEqual(view.bounds, {
    min: { x: 0, y: 0, z: 0 },
    max: { x: 0, y: 0, z: 1 },
  });
  assert.equal(view.scannedVolume, 2);
  assert.equal(view.visibleBlocks, 1);
  assert.deepEqual(view.blocks, [{
    position: { x: 0, y: 0, z: 1 },
    offset: { horizontal: 0, vertical: 0, distance: 1 },
    state: filled.destination,
  }]);

  const limitedView = await bridgeResponse('/v1/inspect-view', {
    ...viewRequest,
    verticalRadius: 1,
    maxResults: 1,
  });
  assert.equal(limitedView.status, 413);
  assert.deepEqual(limitedView.body, {
    error: {
      code: 'result_too_large',
      message: 'View result exceeds maxResults of 1 visible blocks',
    },
  });

  const exactRuns = await bridgeRequest('/v1/inspect-blocks', {
    ...region,
    include: [filled.destination],
    mode: 'runs',
    maxResults: 8,
  });
  assert.equal(exactRuns.mode, 'runs');
  assert.equal(exactRuns.matchedBlocks, 8);
  assert.ok(exactRuns.runs.length > 0 && exactRuns.runs.length < 8);
  assert.deepEqual(expandedRunKeys(exactRuns.runs), expectedBlockKeys(filled.destination));

  const excluded = await bridgeRequest('/v1/inspect-blocks', {
    ...region,
    exclude: [filled.destination],
  });
  assert.equal(excluded.matchedBlocks, 0);
  assert.deepEqual(excluded.blocks, []);

  const limited = await bridgeResponse('/v1/inspect-blocks', { ...region, maxResults: 1 });
  assert.equal(limited.status, 413);
  assert.deepEqual(limited.body, {
    error: {
      code: 'result_too_large',
      message: 'Inspection result exceeds maxResults of 1 entries',
    },
  });

  const replacementDestination = 'minecraft:gold_block';
  const replacePreview = await bridgeRequest('/v1/replace-blocks', {
    ...region,
    source: filled.destination,
    destination: replacementDestination,
    dryRun: true,
  });
  assert.equal(replacePreview.dryRun, true);
  assert.equal(replacePreview.matchedBlocks, 8);
  assert.equal(replacePreview.changedBlocks, 8);

  const replaced = await bridgeRequest('/v1/replace-blocks', {
    ...region,
    source: filled.destination,
    destination: replacementDestination,
  });
  editsToUndo += replaced.changedBlocks > 0 ? 1 : 0;
  assert.equal(replaced.dryRun, false);
  assert.equal(replaced.matchedBlocks, replacePreview.matchedBlocks);
  assert.equal(replaced.changedBlocks, replacePreview.changedBlocks);
  assertExactBlocks(await bridgeRequest('/v1/inspect-blocks', region), replaced.destination);

  const replacementUndone = await bridgeRequest('/v1/undo-last-edit', { world });
  editsToUndo -= 1;
  assert.equal(replacementUndone.changedBlocks, replaced.changedBlocks);
  assertExactBlocks(await bridgeRequest('/v1/inspect-blocks', region), filled.destination);

  const noOp = await bridgeRequest('/v1/fill-region', { ...region, destination: filled.destination });
  assert.equal(noOp.changedBlocks, 0);

  const undone = await bridgeRequest('/v1/undo-last-edit', { world });
  editsToUndo -= 1;
  assert.equal(undone.changedBlocks, filled.changedBlocks);

  const restored = await bridgeRequest('/v1/inspect-region', region);
  assert.deepEqual(normalizedJson(restored), normalizedJson(original));
  process.stdout.write(`managed bridge smoke test passed in ${world}\n`);
} finally {
  if (editsToUndo > 0) {
    try {
      while (editsToUndo > 0) {
        await bridgeRequest('/v1/undo-last-edit', { world });
        editsToUndo -= 1;
      }
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
