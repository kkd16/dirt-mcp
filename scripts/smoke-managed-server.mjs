#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url));
const runDirectory = `${repositoryRoot}/paper-plugin/run`;
const token = (await readFile(`${runDirectory}/.dirt-mcp-token`, 'utf8')).trim();
const runtimeState = Object.fromEntries(
  (await readFile(`${runDirectory}/.dirt-mcp-dev-state`, 'utf8'))
    .trim()
    .split('\n')
    .map((line) => line.split('=', 2)),
);
const bridgePort = Number(runtimeState.BRIDGE_PORT);
assert.ok(Number.isInteger(bridgePort) && bridgePort > 0 && bridgePort <= 65_535, 'Invalid bridge port');

const world = 'world';
const min = { x: 0, y: 0, z: 0 };
const max = { x: 1, y: 1, z: 1 };
const stairMin = { x: 2, y: 0, z: 0 };
const stairMiddle = { x: 3, y: 0, z: 0 };
const stairMax = { x: 4, y: 0, z: 0 };
const commandPosition = { x: 5, y: 0, z: 0 };
const setPosition = { x: 6, y: 0, z: 0 };
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
    const child = spawn(`${repositoryRoot}/scripts/dev-paper`, ['command', command], {
      stdio: 'inherit',
    });
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

async function restoreBlocks(blocks) {
  const result = await bridgeRequest('/v1/run-minecraft-commands', {
    commands: blocks.map(
      ({ position, blockState }) => `setblock ${position.x} ${position.y} ${position.z} ${blockState} replace`,
    ),
  });
  assert.ok(result.results.every(({ outcome }) => outcome === 'dispatched'));
}

async function waitForRegion(region) {
  let lastError;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      // Readiness retries must be serial so each attempt observes the latest server state.
      // oxlint-disable-next-line eslint/no-await-in-loop
      return await bridgeRequest('/v1/count-region-block-states', region);
    } catch (error) {
      lastError = error;
      // oxlint-disable-next-line eslint/no-await-in-loop
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
        .toSorted(([left], [right]) => left.localeCompare(right))
        .map(([key, entry]) => [key, normalizedJson(entry)]),
    );
  }
  return value;
}

function sortedBlockKeys(blocks) {
  return blocks.map(({ position, blockState }) => `${position.x},${position.y},${position.z}:${blockState}`).toSorted();
}

function expectedBlockKeys(blockState) {
  const blocks = [];
  for (let y = min.y; y <= max.y; y += 1) {
    for (let z = min.z; z <= max.z; z += 1) {
      for (let x = min.x; x <= max.x; x += 1) {
        blocks.push(`${x},${y},${z}:${blockState}`);
      }
    }
  }
  return blocks.toSorted();
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
  return blocks.toSorted();
}

function assertExactBlocks(inspection, blockState) {
  assert.equal(inspection.format, 'blocks');
  assert.equal(inspection.volume, 8);
  assert.equal(inspection.matchedBlockCount, 8);
  assert.deepEqual(inspection.bounds, { min, max });
  assert.deepEqual(sortedBlockKeys(inspection.blocks), expectedBlockKeys(blockState));
}

const region = { world, min, max };
const stairRegion = { world, min: stairMin, max: stairMax };
let editsToUndo = 0;
let fixtureIsForceLoaded = false;
let originalStairFixture;
let originalCommandFixture;
let originalSetFixture;
try {
  const unauthenticated = await fetch(`${baseUrl}/v1/ping`);
  assert.equal(unauthenticated.status, 401);
  assert.equal((await unauthenticated.json()).error.code, 'unauthorized');

  const unknownRoute = await fetch(`${baseUrl}/v1/ping/extra`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(unknownRoute.status, 404);
  assert.equal((await unknownRoute.json()).error.code, 'not_found');

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
  assert.ok(serverStatus.limits.maxTouchedChunks > 0);
  assert.ok(serverStatus.limits.defaultInspectionResultLimit <= serverStatus.limits.maxInspectionResultLimit);
  assert.ok(serverStatus.limits.maxCommandsPerRequest > 0);
  assert.ok(serverStatus.limits.maxCommandFeedbackCharacters > 0);

  const adminCommandRun = await bridgeRequest('/v1/run-minecraft-commands', {
    commands: ['dirt', 'dirt version', 'dirt status', 'dirt config'],
  });
  assert.equal(adminCommandRun.feedbackTruncated, false);
  assert.deepEqual(
    adminCommandRun.results.map(({ outcome }) => outcome),
    ['dispatched', 'dispatched', 'dispatched', 'dispatched'],
  );
  const [helpFeedback, versionFeedback, statusFeedback, configFeedback] = adminCommandRun.results.map(({ feedback }) =>
    feedback.join('\n'),
  );
  assert.ok(helpFeedback.includes('DIRT MCP  /  Command Center'));
  assert.ok(helpFeedback.includes('/dirt status'));
  assert.ok(versionFeedback.includes(`Version  ${serverStatus.builds.dirtMcp}`));
  assert.ok(statusFeedback.includes('● Running'));
  assert.ok(statusFeedback.includes(`Bridge  127.0.0.1:${bridgePort}`));
  assert.ok(configFeedback.includes('DIRT MCP  /  Active Configuration'));
  assert.ok(configFeedback.includes(`port  ${bridgePort}`));

  const chunkHeavyRegion = await bridgeResponse('/v1/count-region-block-states', {
    world,
    min: { x: 0, y: 0, z: 0 },
    max: { x: 4096, y: 0, z: 0 },
  });
  assert.equal(chunkHeavyRegion.status, 413);
  assert.equal(chunkHeavyRegion.body.error.code, 'region_too_large');

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
  assert.deepEqual(
    commandRun.results.map(({ outcome }) => outcome),
    ['dispatched', 'dispatched'],
  );
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

  await restoreBlocks([
    {
      position: commandPosition,
      blockState: originalCommandFixture.blocks[0].blockState,
    },
  ]);
  originalCommandFixture = undefined;

  originalSetFixture = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: setPosition,
    includeAir: true,
  });
  assert.equal(originalSetFixture.matchedBlockCount, 2);
  const originalSetStates = new Map(
    originalSetFixture.blocks.map((block) => [
      `${block.position.x},${block.position.y},${block.position.z}`,
      block.blockState,
    ]),
  );
  const firstOriginalState = originalSetStates.get('5,0,0');
  const secondOriginalState = originalSetStates.get('6,0,0');
  assert.ok(firstOriginalState);
  assert.ok(secondOriginalState);
  const firstSetState =
    firstOriginalState === 'minecraft:diamond_block' ? 'minecraft:gold_block' : 'minecraft:diamond_block';
  const secondSetState =
    secondOriginalState === 'minecraft:emerald_block' ? 'minecraft:redstone_block' : 'minecraft:emerald_block';
  const setPalettes = [
    [
      { blockState: firstOriginalState, weight: 50 },
      { blockState: firstSetState, weight: 50 },
    ],
    [{ blockState: secondSetState }],
  ];
  const setSeed = 1_234_567;

  const setPreview = await bridgeRequest('/v1/set-blocks', {
    world,
    origin: commandPosition,
    palettes: setPalettes,
    placements: [
      [0, 0, 0, 0],
      [1, 1, 0, 0],
    ],
    seed: setSeed,
    dryRun: true,
  });
  assert.equal(setPreview.world, world);
  assert.deepEqual(setPreview.palettes, setPalettes);
  assert.equal(setPreview.seed, setSeed);
  assert.equal(setPreview.dryRun, true);
  assert.equal(setPreview.blockCount, 2);
  assert.ok(setPreview.changedBlockCount === 1 || setPreview.changedBlockCount === 2);
  assert.equal(setPreview.unchangedBlockCount, 2 - setPreview.changedBlockCount);

  const duplicateSet = await bridgeResponse('/v1/set-blocks', {
    world,
    origin: commandPosition,
    palettes: [[{ blockState: firstSetState }], [{ blockState: secondSetState }]],
    placements: [
      [0, 0, 0, 0],
      [1, 0, 0, 0],
    ],
  });
  assert.equal(duplicateSet.status, 400);
  assert.equal(duplicateSet.body.error.code, 'invalid_request');

  const invalidSet = await bridgeResponse('/v1/set-blocks', {
    world,
    origin: commandPosition,
    palettes: [[{ blockState: firstSetState }], [{ blockState: 'minecraft:not_a_block' }]],
    placements: [
      [0, 0, 0, 0],
      [1, 1, 0, 0],
    ],
  });
  assert.equal(invalidSet.status, 400);
  assert.equal(invalidSet.body.error.code, 'invalid_request');
  const afterInvalidSet = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: setPosition,
    includeAir: true,
  });
  assert.deepEqual(sortedBlockKeys(afterInvalidSet.blocks), sortedBlockKeys(originalSetFixture.blocks));

  const setResult = await bridgeRequest('/v1/set-blocks', {
    world,
    origin: commandPosition,
    palettes: setPalettes,
    placements: [
      [0, 0, 0, 0],
      [1, 1, 0, 0],
    ],
    seed: setSeed,
  });
  editsToUndo += setResult.changedBlockCount > 0 ? 1 : 0;
  assert.deepEqual(setResult, {
    world,
    palettes: setPalettes,
    seed: setSeed,
    dryRun: false,
    blockCount: 2,
    changedBlockCount: setPreview.changedBlockCount,
    unchangedBlockCount: setPreview.unchangedBlockCount,
  });
  const afterSet = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: setPosition,
  });
  const afterSetStates = new Map(
    afterSet.blocks.map((block) => [`${block.position.x},${block.position.y},${block.position.z}`, block.blockState]),
  );
  assert.ok([firstOriginalState, firstSetState].includes(afterSetStates.get('5,0,0')));
  assert.equal(afterSetStates.get('6,0,0'), secondSetState);
  const setUndone = await bridgeRequest('/v1/undo-last-dirt-edit', { world });
  editsToUndo -= 1;
  assert.equal(setUndone.changedBlockCount, setResult.changedBlockCount);
  const afterSetUndo = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: commandPosition,
    max: setPosition,
    includeAir: true,
  });
  assert.deepEqual(sortedBlockKeys(afterSetUndo.blocks), sortedBlockKeys(originalSetFixture.blocks));
  originalSetFixture = undefined;

  const originalStates = Object.keys(original.blockStateCounts);
  const fillBlockState =
    originalStates.length === 1 && originalStates[0].startsWith('minecraft:barrier')
      ? 'minecraft:amethyst_block'
      : 'minecraft:barrier';

  const preview = await bridgeRequest('/v1/fill-region', {
    ...region,
    destinationPalette: [{ blockState: fillBlockState }],
    dryRun: true,
  });
  const previewState = preview.destinationPalette[0].blockState;
  const alreadyMatching = original.blockStateCounts[previewState] ?? 0;
  assert.equal(preview.dryRun, true);
  assert.equal(preview.volume, original.volume);
  assert.equal(preview.changedBlockCount, original.volume - alreadyMatching);
  assert.ok(preview.changedBlockCount > 0, 'Smoke destination must change at least one block');

  const filled = await bridgeRequest('/v1/fill-region', {
    ...region,
    destinationPalette: [{ blockState: fillBlockState }],
    seed: preview.seed,
  });
  editsToUndo += filled.changedBlockCount > 0 ? 1 : 0;
  const filledState = filled.destinationPalette[0].blockState;
  assert.equal(filled.dryRun, false);
  assert.equal(filled.seed, preview.seed);
  assert.deepEqual(filled.destinationPalette, preview.destinationPalette);
  assert.equal(filled.changedBlockCount, preview.changedBlockCount);

  const afterFill = await bridgeRequest('/v1/count-region-block-states', region);
  assert.deepEqual(afterFill.blockStateCounts, { [filledState]: filled.volume });

  const exactBlocks = await bridgeRequest('/v1/get-region-blocks', region);
  assertExactBlocks(exactBlocks, filledState);

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
    depth: 0,
  });
  assert.deepEqual(view.bounds, {
    min: { x: 0, y: 0, z: 0 },
    max: { x: 0, y: 0, z: 1 },
  });
  assert.equal(view.scannedVolume, 2);
  assert.equal(view.visibleBlockCount, 1);
  assert.deepEqual(view.blocks, [
    {
      position: { x: 0, y: 0, z: 1 },
      offset: { horizontal: 0, vertical: 0, distance: 1 },
      blockState: filledState,
    },
  ]);

  const deeperView = await bridgeRequest('/v1/scan-orthographic-view', {
    ...viewRequest,
    depth: 1,
  });
  assert.deepEqual(deeperView.viewport, {
    horizontalRadius: 0,
    verticalRadius: 0,
    maxDistance: 2,
    depth: 1,
  });
  assert.equal(deeperView.visibleBlockCount, 1);
  assert.deepEqual(deeperView.blocks, [
    {
      position: { x: 0, y: 0, z: 0 },
      offset: { horizontal: 0, vertical: 0, distance: 2 },
      blockState: filledState,
    },
  ]);

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
    includeBlockStatePatterns: [filledState],
    format: 'runs',
    maxResults: 8,
  });
  assert.equal(exactRuns.format, 'runs');
  assert.equal(exactRuns.matchedBlockCount, 8);
  assert.ok(exactRuns.runs.length > 0 && exactRuns.runs.length < 8);
  assert.deepEqual(expandedRunKeys(exactRuns.runs), expectedBlockKeys(filledState));

  const excluded = await bridgeRequest('/v1/get-region-blocks', {
    ...region,
    excludeBlockStatePatterns: [filledState],
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
    sourceBlockStatePatterns: [filledState],
    destinationPalette: [{ blockState: replacementDestination }],
    dryRun: true,
  });
  assert.equal(replacePreview.dryRun, true);
  assert.equal(replacePreview.matchedBlockCount, 8);
  assert.equal(replacePreview.changedBlockCount, 8);

  const replaced = await bridgeRequest('/v1/replace-region-blocks', {
    ...region,
    sourceBlockStatePatterns: [filledState],
    destinationPalette: [{ blockState: replacementDestination }],
    seed: replacePreview.seed,
  });
  editsToUndo += replaced.changedBlockCount > 0 ? 1 : 0;
  assert.equal(replaced.dryRun, false);
  assert.equal(replaced.matchedBlockCount, replacePreview.matchedBlockCount);
  assert.equal(replaced.changedBlockCount, replacePreview.changedBlockCount);
  assertExactBlocks(await bridgeRequest('/v1/get-region-blocks', region), replaced.destinationPalette[0].blockState);

  const replacementUndone = await bridgeRequest('/v1/undo-last-dirt-edit', { world });
  editsToUndo -= 1;
  assert.equal(replacementUndone.changedBlockCount, replaced.changedBlockCount);
  assertExactBlocks(await bridgeRequest('/v1/get-region-blocks', region), filledState);

  const noOp = await bridgeRequest('/v1/fill-region', {
    ...region,
    destinationPalette: [{ blockState: filledState }],
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
  await restoreBlocks([
    { position: stairMin, blockState: northStairs },
    { position: stairMiddle, blockState: 'minecraft:air' },
    { position: stairMax, blockState: southStairs },
  ]);

  const exactStatePreview = await bridgeRequest('/v1/replace-region-blocks', {
    ...stairRegion,
    sourceBlockStatePatterns: ['minecraft:dark_oak_stairs', 'minecraft:air'],
    destinationPalette: [
      { blockState: 'minecraft:gold_block', weight: 50 },
      { blockState: 'minecraft:diamond_block', weight: 50 },
    ],
    dryRun: true,
  });
  assert.equal(exactStatePreview.matchedBlockCount, 3);
  assert.equal(exactStatePreview.changedBlockCount, 3);

  const exactStateReplacement = await bridgeRequest('/v1/replace-region-blocks', {
    ...stairRegion,
    sourceBlockStatePatterns: ['minecraft:dark_oak_stairs', 'minecraft:air'],
    destinationPalette: [
      { blockState: 'minecraft:gold_block', weight: 50 },
      { blockState: 'minecraft:diamond_block', weight: 50 },
    ],
    seed: exactStatePreview.seed,
  });
  editsToUndo += exactStateReplacement.changedBlockCount > 0 ? 1 : 0;
  assert.equal(exactStateReplacement.matchedBlockCount, 3);
  assert.equal(exactStateReplacement.changedBlockCount, 3);
  const exactStateBlocks = await bridgeRequest('/v1/get-region-blocks', {
    ...stairRegion,
    includeBlockStatePatterns: ['minecraft:gold_block', 'minecraft:diamond_block'],
  });
  assert.equal(exactStateBlocks.matchedBlockCount, 3);
  assert.ok(
    exactStateBlocks.blocks.every(
      ({ blockState }) => blockState === 'minecraft:gold_block' || blockState === 'minecraft:diamond_block',
    ),
  );
  const firstSeededLayout = sortedBlockKeys(exactStateBlocks.blocks);

  const exactStateReplacementUndone = await bridgeRequest('/v1/undo-last-dirt-edit', { world });
  editsToUndo -= 1;
  assert.equal(exactStateReplacementUndone.changedBlockCount, 3);

  const replayedReplacement = await bridgeRequest('/v1/replace-region-blocks', {
    ...stairRegion,
    sourceBlockStatePatterns: ['minecraft:dark_oak_stairs', 'minecraft:air'],
    destinationPalette: [
      { blockState: 'minecraft:gold_block', weight: 50 },
      { blockState: 'minecraft:diamond_block', weight: 50 },
    ],
    seed: exactStatePreview.seed,
  });
  editsToUndo += replayedReplacement.changedBlockCount > 0 ? 1 : 0;
  assert.equal(replayedReplacement.changedBlockCount, exactStateReplacement.changedBlockCount);
  const replayedBlocks = await bridgeRequest('/v1/get-region-blocks', {
    ...stairRegion,
    includeBlockStatePatterns: ['minecraft:gold_block', 'minecraft:diamond_block'],
  });
  assert.deepEqual(sortedBlockKeys(replayedBlocks.blocks), firstSeededLayout);
  const replayedReplacementUndone = await bridgeRequest('/v1/undo-last-dirt-edit', { world });
  editsToUndo -= 1;
  assert.equal(replayedReplacementUndone.changedBlockCount, 3);

  const propertyFillPreview = await bridgeRequest('/v1/fill-region', {
    world,
    min: stairMin,
    max: stairMin,
    destinationPalette: [{ blockState: southStairs }],
    dryRun: true,
  });
  assert.equal(propertyFillPreview.changedBlockCount, 1);

  const propertyFill = await bridgeRequest('/v1/fill-region', {
    world,
    min: stairMin,
    max: stairMin,
    destinationPalette: [{ blockState: southStairs }],
    seed: propertyFillPreview.seed,
  });
  editsToUndo += propertyFill.changedBlockCount > 0 ? 1 : 0;
  assert.equal(propertyFill.changedBlockCount, 1);
  const propertyFillBlocks = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: stairMin,
    max: stairMin,
  });
  assert.deepEqual(sortedBlockKeys(propertyFillBlocks.blocks), [
    `${stairMin.x},${stairMin.y},${stairMin.z}:${southStairs}`,
  ]);

  const propertyFillUndone = await bridgeRequest('/v1/undo-last-dirt-edit', { world });
  editsToUndo -= 1;
  assert.equal(propertyFillUndone.changedBlockCount, 1);

  await restoreBlocks(originalStairFixture.blocks);
  originalStairFixture = undefined;
  process.stdout.write(`managed server smoke test passed in ${world}\n`);
} finally {
  if (editsToUndo > 0) {
    try {
      while (editsToUndo > 0) {
        // Undo is deliberately serial because every request consumes the previous history entry.
        // oxlint-disable-next-line eslint/no-await-in-loop
        await bridgeRequest('/v1/undo-last-dirt-edit', { world });
        editsToUndo -= 1;
      }
    } catch (error) {
      process.stderr.write(`Could not restore smoke-test edit: ${error.message}\n`);
    }
  }
  if (originalStairFixture) {
    try {
      await restoreBlocks(originalStairFixture.blocks);
    } catch (error) {
      process.stderr.write(`Could not restore stair fixture: ${error.message}\n`);
    }
  }
  if (originalCommandFixture) {
    try {
      await restoreBlocks([
        {
          position: commandPosition,
          blockState: originalCommandFixture.blocks[0].blockState,
        },
      ]);
    } catch (error) {
      process.stderr.write(`Could not restore command fixture: ${error.message}\n`);
    }
  }
  if (originalSetFixture) {
    try {
      await restoreBlocks(originalSetFixture.blocks);
    } catch (error) {
      process.stderr.write(`Could not restore set-blocks fixture: ${error.message}\n`);
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
