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
const stairMin = { x: 2, y: 0, z: 0 };
const stairMax = { x: 3, y: 0, z: 0 };
const northStairs = 'minecraft:dark_oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]';
const southStairs = 'minecraft:dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]';
const baseUrl = `http://127.0.0.1:${bridgePort}`;

async function bridgeGet(path) {
  const response = await fetch(`${baseUrl}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(response.status, 200);
  return response.json();
}

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

async function paperSetBlock(position, blockState) {
  await paperCommand(`setblock ${position.x} ${position.y} ${position.z} ${blockState} replace`);
}

async function waitForRegion(region) {
  let lastError;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      return await bridgeRequest('/v1/count-region-block-states', region);
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
    .map(({ position, blockState }) => `${position.x},${position.y},${position.z}:${blockState}`)
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
          blocks.push(`${x},${y},${z}:${run.blockState}`);
        }
      }
    }
  }
  return blocks.sort();
}

function assertExactBlocks(inspection, state) {
  assert.equal(inspection.format, 'blocks');
  assert.equal(inspection.volume, 8);
  assert.equal(inspection.matchedBlockCount, 8);
  assert.deepEqual(inspection.bounds, { min, max });
  assert.deepEqual(sortedBlockKeys(inspection.blocks), expectedBlockKeys(state));
}

const region = { world, min, max };
const stairRegion = { world, min: stairMin, max: stairMax };
let editsToUndo = 0;
let fixtureIsForceLoaded = false;
let originalStairFixture;
try {
  assert.deepEqual(await bridgeGet('/v1/ping'), { status: 'ok' });
  const serverStatus = await bridgeGet('/v1/server-status');
  assert.ok(serverStatus.builds.minecraft.length > 0);
  assert.ok(serverStatus.builds.paper.length > 0);
  assert.ok(serverStatus.builds.dirtMcp.length > 0);
  assert.ok(serverStatus.builds.fawe.length > 0);
  assert.ok(serverStatus.performance.tpsOneMinute >= 0);
  assert.ok(serverStatus.worlds.some((entry) => entry.name === world));
  assert.ok(serverStatus.limits.maxRegionVolume > 0);
  assert.ok(
    serverStatus.limits.defaultRegionBlocksResultLimit
      <= serverStatus.limits.maxRegionBlocksResultLimit,
  );
  assert.ok(
    serverStatus.limits.defaultOrthographicViewResultLimit
      <= serverStatus.limits.maxOrthographicViewResultLimit,
  );

  await paperCommand('forceload add 0 0');
  fixtureIsForceLoaded = true;

  const original = await waitForRegion(region);
  const originalStates = Object.keys(original.blockStateCounts);
  const fillBlockState = originalStates.length === 1 && originalStates[0].startsWith('minecraft:barrier')
    ? 'minecraft:amethyst_block'
    : 'minecraft:barrier';

  const preview = await bridgeRequest('/v1/fill-region', {
    ...region,
    blockState: fillBlockState,
    dryRun: true,
  });
  const alreadyMatching = original.blockStateCounts[preview.blockState] ?? 0;
  assert.equal(preview.dryRun, true);
  assert.equal(preview.volume, original.volume);
  assert.equal(preview.changedBlockCount, original.volume - alreadyMatching);
  assert.ok(preview.changedBlockCount > 0, 'Smoke destination must change at least one block');

  const filled = await bridgeRequest('/v1/fill-region', { ...region, blockState: fillBlockState });
  editsToUndo += filled.changedBlockCount > 0 ? 1 : 0;
  assert.equal(filled.dryRun, false);
  assert.equal(filled.blockState, preview.blockState);
  assert.equal(filled.changedBlockCount, preview.changedBlockCount);

  const afterFill = await bridgeRequest('/v1/count-region-block-states', region);
  assert.deepEqual(afterFill.blockStateCounts, { [filled.blockState]: filled.volume });

  const exactBlocks = await bridgeRequest('/v1/get-region-blocks', region);
  assertExactBlocks(exactBlocks, filled.blockState);

  const viewRequest = {
    world,
    origin: { x: 0, y: 0, z: 2 },
    direction: 'north',
    horizontalRadius: 0,
    verticalRadius: 0,
    maxDistance: 2,
  };
  const view = await bridgeRequest('/v1/scan-orthographic-view', viewRequest);
  assert.equal(view.direction, 'north');
  assert.equal(view.format, 'blocks');
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
  assert.equal(view.visibleBlockCount, 1);
  assert.deepEqual(view.blocks, [{
    position: { x: 0, y: 0, z: 1 },
    offset: { horizontal: 0, vertical: 0, distance: 1 },
    blockState: filled.blockState,
  }]);

  const limitedView = await bridgeResponse('/v1/scan-orthographic-view', {
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

  const exactRuns = await bridgeRequest('/v1/get-region-blocks', {
    ...region,
    includeBlockStatePatterns: [filled.blockState],
    format: 'runs',
    maxResults: 8,
  });
  assert.equal(exactRuns.format, 'runs');
  assert.equal(exactRuns.matchedBlockCount, 8);
  assert.ok(exactRuns.runs.length > 0 && exactRuns.runs.length < 8);
  assert.deepEqual(expandedRunKeys(exactRuns.runs), expectedBlockKeys(filled.blockState));

  const excluded = await bridgeRequest('/v1/get-region-blocks', {
    ...region,
    excludeBlockStatePatterns: [filled.blockState],
  });
  assert.equal(excluded.matchedBlockCount, 0);
  assert.deepEqual(excluded.blocks, []);

  const limited = await bridgeResponse('/v1/get-region-blocks', { ...region, maxResults: 1 });
  assert.equal(limited.status, 413);
  assert.deepEqual(limited.body, {
    error: {
      code: 'result_too_large',
      message: 'Inspection result exceeds maxResults of 1 entries',
    },
  });

  const replacementDestination = 'minecraft:gold_block';
  const replacePreview = await bridgeRequest('/v1/replace-region-blocks', {
    ...region,
    sourceBlockState: filled.blockState,
    destinationBlockState: replacementDestination,
    dryRun: true,
  });
  assert.equal(replacePreview.dryRun, true);
  assert.equal(replacePreview.matchedBlockCount, 8);
  assert.equal(replacePreview.changedBlockCount, 8);

  const replaced = await bridgeRequest('/v1/replace-region-blocks', {
    ...region,
    sourceBlockState: filled.blockState,
    destinationBlockState: replacementDestination,
  });
  editsToUndo += replaced.changedBlockCount > 0 ? 1 : 0;
  assert.equal(replaced.dryRun, false);
  assert.equal(replaced.matchedBlockCount, replacePreview.matchedBlockCount);
  assert.equal(replaced.changedBlockCount, replacePreview.changedBlockCount);
  assertExactBlocks(
    await bridgeRequest('/v1/get-region-blocks', region),
    replaced.destinationBlockState,
  );

  const replacementUndone = await bridgeRequest('/v1/undo-last-dirt-edit', { world });
  editsToUndo -= 1;
  assert.equal(replacementUndone.changedBlockCount, replaced.changedBlockCount);
  assertExactBlocks(await bridgeRequest('/v1/get-region-blocks', region), filled.blockState);

  const noOp = await bridgeRequest('/v1/fill-region', {
    ...region,
    blockState: filled.blockState,
  });
  assert.equal(noOp.changedBlockCount, 0);

  const undone = await bridgeRequest('/v1/undo-last-dirt-edit', { world });
  editsToUndo -= 1;
  assert.equal(undone.changedBlockCount, filled.changedBlockCount);

  const restored = await bridgeRequest('/v1/count-region-block-states', region);
  assert.deepEqual(normalizedJson(restored), normalizedJson(original));

  originalStairFixture = await bridgeRequest('/v1/get-region-blocks', {
    ...stairRegion,
    includeAir: true,
  });
  assert.equal(originalStairFixture.matchedBlockCount, 2);
  await paperSetBlock(stairMin, northStairs);
  await paperSetBlock(stairMax, southStairs);

  const exactStatePreview = await bridgeRequest('/v1/replace-region-blocks', {
    ...stairRegion,
    sourceBlockState: northStairs,
    destinationBlockState: 'minecraft:gold_block',
    dryRun: true,
  });
  assert.equal(exactStatePreview.matchedBlockCount, 1);
  assert.equal(exactStatePreview.changedBlockCount, 1);

  const exactStateReplacement = await bridgeRequest('/v1/replace-region-blocks', {
    ...stairRegion,
    sourceBlockState: northStairs,
    destinationBlockState: 'minecraft:gold_block',
  });
  editsToUndo += exactStateReplacement.changedBlockCount > 0 ? 1 : 0;
  assert.equal(exactStateReplacement.matchedBlockCount, 1);
  assert.equal(exactStateReplacement.changedBlockCount, 1);
  const exactStateBlocks = await bridgeRequest('/v1/get-region-blocks', stairRegion);
  assert.deepEqual(
    sortedBlockKeys(exactStateBlocks.blocks),
    [
      `${stairMin.x},${stairMin.y},${stairMin.z}:minecraft:gold_block`,
      `${stairMax.x},${stairMax.y},${stairMax.z}:${southStairs}`,
    ],
  );

  const exactStateReplacementUndone = await bridgeRequest('/v1/undo-last-dirt-edit', { world });
  editsToUndo -= 1;
  assert.equal(exactStateReplacementUndone.changedBlockCount, 1);

  const propertyFillPreview = await bridgeRequest('/v1/fill-region', {
    world,
    min: stairMin,
    max: stairMin,
    blockState: southStairs,
    dryRun: true,
  });
  assert.equal(propertyFillPreview.changedBlockCount, 1);

  const propertyFill = await bridgeRequest('/v1/fill-region', {
    world,
    min: stairMin,
    max: stairMin,
    blockState: southStairs,
  });
  editsToUndo += propertyFill.changedBlockCount > 0 ? 1 : 0;
  assert.equal(propertyFill.changedBlockCount, 1);
  const propertyFillBlocks = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: stairMin,
    max: stairMin,
  });
  assert.deepEqual(
    sortedBlockKeys(propertyFillBlocks.blocks),
    [`${stairMin.x},${stairMin.y},${stairMin.z}:${southStairs}`],
  );

  const propertyFillUndone = await bridgeRequest('/v1/undo-last-dirt-edit', { world });
  editsToUndo -= 1;
  assert.equal(propertyFillUndone.changedBlockCount, 1);

  for (const block of originalStairFixture.blocks) {
    await paperSetBlock(block.position, block.blockState);
  }
  originalStairFixture = undefined;
  process.stdout.write(`managed bridge smoke test passed in ${world}\n`);
} finally {
  if (editsToUndo > 0) {
    try {
      while (editsToUndo > 0) {
        await bridgeRequest('/v1/undo-last-dirt-edit', { world });
        editsToUndo -= 1;
      }
    } catch (error) {
      process.stderr.write(`Could not restore smoke-test edit: ${error.message}\n`);
    }
  }
  if (originalStairFixture) {
    try {
      for (const block of originalStairFixture.blocks) {
        await paperSetBlock(block.position, block.blockState);
      }
    } catch (error) {
      process.stderr.write(`Could not restore stair fixture: ${error.message}\n`);
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
