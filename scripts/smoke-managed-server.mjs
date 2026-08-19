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
const stairMax = { x: 4, y: 0, z: 0 };
const commandPosition = { x: 5, y: 0, z: 0 };
const sparsePosition = { x: 6, y: 0, z: 0 };
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

async function bridgeSetBlocks(blocks) {
  const result = await bridgeRequest('/v1/run-minecraft-commands', {
    commands: blocks.map(({ position, blockState }) => (
      `setblock ${position.x} ${position.y} ${position.z} ${blockState} replace`
    )),
  });
  assert.ok(result.results.every(({ outcome }) => outcome === 'dispatched'));
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
let originalCommandFixture;
let originalSparseFixture;
try {
  assert.deepEqual(await bridgeGet('/v1/ping'), { status: 'ok' });
  const serverStatus = await bridgeGet('/v1/server-status');
  assert.ok(serverStatus.builds.minecraft.length > 0);
  assert.ok(serverStatus.builds.paper.length > 0);
  assert.ok(serverStatus.builds.dirtMcp.length > 0);
  assert.ok(serverStatus.builds.fawe.length > 0);
  assert.ok(serverStatus.performance.tpsOneMinute >= 0);
  assert.ok(serverStatus.worlds.some((entry) => entry.name === world));
  assert.ok(serverStatus.limits.maxRequestBytes > 0);
  assert.ok(serverStatus.limits.maxRegionVolume > 0);
  assert.ok(
    serverStatus.limits.defaultInspectionResultLimit
      <= serverStatus.limits.maxInspectionResultLimit,
  );
  assert.ok(serverStatus.limits.maxCommandsPerRequest > 0);
  assert.ok(serverStatus.limits.maxCommandFeedbackCharacters > 0);

  await paperCommand('forceload add 0 0');
  fixtureIsForceLoaded = true;
  const original = await waitForRegion(region);

  originalCommandFixture = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: commandPosition,
    includeAir: true,
  });
  assert.equal(originalCommandFixture.matchedBlockCount, 1);
  const commandRun = await bridgeRequest('/v1/run-minecraft-commands', {
    commands: [
      `/setblock ${commandPosition.x} ${commandPosition.y} ${commandPosition.z} minecraft:stone replace`,
      `setblock ${commandPosition.x} ${commandPosition.y} ${commandPosition.z} minecraft:gold_block replace`,
    ],
  });
  assert.deepEqual(commandRun.sender, {
    name: 'FeedbackForwardingSender',
    isOperator: true,
    isPlayer: false,
  });
  assert.equal(commandRun.feedbackTruncated, false);
  assert.deepEqual(commandRun.results.map(({ outcome }) => outcome), ['dispatched', 'dispatched']);
  assert.ok(commandRun.results.every(({ feedback }) => feedback.length > 0));
  const afterCommandRun = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: commandPosition,
  });
  assert.equal(afterCommandRun.blocks[0].blockState, 'minecraft:gold_block');

  const continuedCommandRun = await bridgeRequest('/v1/run-minecraft-commands', {
    commands: [
      'dirt_command_that_does_not_exist',
      `setblock ${commandPosition.x} ${commandPosition.y} ${commandPosition.z} minecraft:diamond_block replace`,
    ],
  });
  assert.deepEqual(
    continuedCommandRun.results.map(({ outcome }) => outcome),
    ['not_found', 'dispatched'],
  );
  const afterContinuedCommandRun = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: commandPosition,
  });
  assert.equal(afterContinuedCommandRun.blocks[0].blockState, 'minecraft:diamond_block');

  const vanillaCommandRun = await bridgeRequest('/v1/run-minecraft-commands', {
    commands: [
      'time query gametime',
      `execute if block ${commandPosition.x} ${commandPosition.y} ${commandPosition.z} minecraft:diamond_block run time query gametime`,
    ],
  });
  assert.deepEqual(
    vanillaCommandRun.results.map(({ outcome }) => outcome),
    ['dispatched', 'dispatched'],
  );
  assert.ok(vanillaCommandRun.results.every(({ feedback }) => feedback.length > 0));
  assert.ok(vanillaCommandRun.results.every(({ message }) => message === null));
  assert.ok(vanillaCommandRun.results.every(({ rawMessage }) => rawMessage === null));

  const invalidSyntaxRun = await bridgeRequest('/v1/run-minecraft-commands', {
    commands: [
      'time query daytime',
      `setblock ${commandPosition.x} ${commandPosition.y} ${commandPosition.z} minecraft:diamond_block replace`,
    ],
  });
  assert.deepEqual(
    invalidSyntaxRun.results.map(({ outcome }) => outcome),
    ['dispatch_failed', 'dispatched'],
  );
  assert.ok(invalidSyntaxRun.results[0].message.length > 0);
  assert.ok(invalidSyntaxRun.results[0].rawMessage.length > 0);
  assert.notEqual(invalidSyntaxRun.results[0].message, invalidSyntaxRun.results[0].rawMessage);

  await bridgeSetBlocks([{
    position: commandPosition,
    blockState: originalCommandFixture.blocks[0].blockState,
  }]);
  originalCommandFixture = undefined;

  originalSparseFixture = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: sparsePosition,
    includeAir: true,
  });
  assert.equal(originalSparseFixture.matchedBlockCount, 2);
  const originalSparseStates = new Map(originalSparseFixture.blocks.map((block) => [
    `${block.position.x},${block.position.y},${block.position.z}`,
    block.blockState,
  ]));
  const firstOriginalState = originalSparseStates.get('5,0,0');
  const secondOriginalState = originalSparseStates.get('6,0,0');
  assert.ok(firstOriginalState);
  assert.ok(secondOriginalState);
  const firstSparseState = firstOriginalState === 'minecraft:diamond_block'
    ? 'minecraft:gold_block'
    : 'minecraft:diamond_block';
  const secondSparseState = secondOriginalState === 'minecraft:emerald_block'
    ? 'minecraft:redstone_block'
    : 'minecraft:emerald_block';

  const sparsePreview = await bridgeRequest('/v1/set-blocks', {
    world,
    changes: [
      { position: commandPosition, blockState: firstOriginalState },
      { position: sparsePosition, blockState: secondSparseState },
    ],
    dryRun: true,
  });
  assert.deepEqual(sparsePreview, {
    world,
    dryRun: true,
    blockCount: 2,
    changedBlockCount: 1,
    unchangedBlockCount: 1,
  });

  const duplicateSparse = await bridgeResponse('/v1/set-blocks', {
    world,
    changes: [
      { position: commandPosition, blockState: firstSparseState },
      { position: commandPosition, blockState: secondSparseState },
    ],
  });
  assert.equal(duplicateSparse.status, 400);
  assert.equal(duplicateSparse.body.error.code, 'invalid_request');

  const invalidSparse = await bridgeResponse('/v1/set-blocks', {
    world,
    changes: [
      { position: commandPosition, blockState: firstSparseState },
      { position: sparsePosition, blockState: 'minecraft:not_a_block' },
    ],
  });
  assert.equal(invalidSparse.status, 400);
  assert.equal(invalidSparse.body.error.code, 'invalid_request');
  const afterInvalidSparse = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: sparsePosition,
    includeAir: true,
  });
  assert.deepEqual(
    sortedBlockKeys(afterInvalidSparse.blocks),
    sortedBlockKeys(originalSparseFixture.blocks),
  );

  const sparseSet = await bridgeRequest('/v1/set-blocks', {
    world,
    changes: [
      { position: commandPosition, blockState: firstSparseState },
      { position: sparsePosition, blockState: secondSparseState },
    ],
  });
  editsToUndo += sparseSet.changedBlockCount > 0 ? 1 : 0;
  assert.deepEqual(sparseSet, {
    world,
    dryRun: false,
    blockCount: 2,
    changedBlockCount: 2,
    unchangedBlockCount: 0,
  });
  const afterSparseSet = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: sparsePosition,
  });
  assert.deepEqual(
    sortedBlockKeys(afterSparseSet.blocks),
    [
      `5,0,0:${firstSparseState}`,
      `6,0,0:${secondSparseState}`,
    ],
  );
  const sparseUndone = await bridgeRequest('/v1/undo-last-dirt-edit', { world });
  editsToUndo -= 1;
  assert.equal(sparseUndone.changedBlockCount, 2);
  const afterSparseUndo = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: sparsePosition,
    includeAir: true,
  });
  assert.deepEqual(
    sortedBlockKeys(afterSparseUndo.blocks),
    sortedBlockKeys(originalSparseFixture.blocks),
  );
  originalSparseFixture = undefined;

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
  assert.equal(originalStairFixture.matchedBlockCount, 3);
  await bridgeSetBlocks([
    { position: stairMin, blockState: northStairs },
    { position: stairMax, blockState: southStairs },
  ]);

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
  const exactStateBlocks = await bridgeRequest('/v1/get-region-blocks', {
    ...stairRegion,
    includeBlockStatePatterns: ['minecraft:gold_block', southStairs],
  });
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

  await bridgeSetBlocks(originalStairFixture.blocks);
  originalStairFixture = undefined;
  process.stdout.write(`managed server smoke test passed in ${world}\n`);
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
      await bridgeSetBlocks(originalStairFixture.blocks);
    } catch (error) {
      process.stderr.write(`Could not restore stair fixture: ${error.message}\n`);
    }
  }
  if (originalCommandFixture) {
    try {
      await bridgeSetBlocks([{
        position: commandPosition,
        blockState: originalCommandFixture.blocks[0].blockState,
      }]);
    } catch (error) {
      process.stderr.write(`Could not restore command fixture: ${error.message}\n`);
    }
  }
  if (originalSparseFixture) {
    try {
      await bridgeSetBlocks(originalSparseFixture.blocks);
    } catch (error) {
      process.stderr.write(`Could not restore sparse fixture: ${error.message}\n`);
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
