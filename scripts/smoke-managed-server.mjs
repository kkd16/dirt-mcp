#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('..', import.meta.url));
const runDirectory = `${repositoryRoot}/paper-plugin/run`;
const detailLogDirectory = `${runDirectory}/plugins/DirtMCP/logs`;
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
const setMin = { x: 5, y: 0, z: 0 };
const setMax = { x: 6, y: 0, z: 0 };
const northStairs = 'minecraft:dark_oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]';
const southStairs = 'minecraft:dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]';
const baseUrl = `http://127.0.0.1:${bridgePort}`;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const uuidV4Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const editIdsToUndo = [];
const observedEditIds = new Set();
const mutationCallIds = new Set();
const editMutationPaths = new Set(['/v1/replace-region-blocks', '/v1/fill-region', '/v1/set-blocks']);
const inspectionPaths = new Set([
  '/v1/count-region-block-states',
  '/v1/get-region-blocks',
  '/v1/scan-orthographic-view',
]);

function bridgeTimeoutMilliseconds(path) {
  if (editMutationPaths.has(path) || path === '/v1/undo-edit') return 120_000;
  return inspectionPaths.has(path) ? 30_000 : 3_000;
}

function sameUuid(left, right) {
  return typeof left === 'string' && typeof right === 'string' && left.toLowerCase() === right.toLowerCase();
}

function trackEditId(editId) {
  if (typeof editId !== 'string' || !uuidV4Pattern.test(editId)) return;
  observedEditIds.add(editId.toLowerCase());
  if (!editIdsToUndo.some((retained) => sameUuid(retained, editId))) editIdsToUndo.push(editId);
}

async function bridgeGet(path, callId) {
  const headers = { Authorization: `Bearer ${token}` };
  if (callId !== undefined) headers['X-Dirt-Call-Id'] = callId;
  const response = await fetch(`${baseUrl}${path}`, {
    headers,
    signal: AbortSignal.timeout(bridgeTimeoutMilliseconds(path)),
  });
  assert.equal(response.status, 200, `GET ${path} returned HTTP ${response.status}`);
  return response.json();
}

async function bridgeResponse(path, body) {
  const callId = randomUUID();
  if (editMutationPaths.has(path)) mutationCallIds.add(callId);
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-Dirt-Call-Id': callId,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(bridgeTimeoutMilliseconds(path)),
  });
  const text = await response.text();
  const document = JSON.parse(text);
  if (editMutationPaths.has(path)) {
    trackEditId(response.ok && document.outcome === 'committed' ? document.edit?.editId : document.error?.editId);
  }
  if (response.ok && path === '/v1/undo-edit' && sameUuid(editIdsToUndo.at(-1), body.editId)) {
    editIdsToUndo.pop();
  }
  return { status: response.status, ok: response.ok, body: document, callId };
}

async function bridgeRequest(path, body) {
  const response = await bridgeResponse(path, body);
  if (!response.ok) {
    throw new Error(`${path} returned ${response.status}: ${JSON.stringify(response.body)}`);
  }
  if (response.body.undoCallId !== undefined) {
    assert.ok(sameUuid(response.body.undoCallId, response.callId));
  } else if (response.body.edit?.callId !== undefined) {
    assert.ok(sameUuid(response.body.edit.callId, response.callId));
  }
  return response.body;
}

async function readDetailedLogRecords() {
  let fileNames;
  try {
    fileNames = (await readdir(detailLogDirectory)).filter((name) => /^dirt-detail\.\d+\.jsonl$/.test(name));
  } catch (error) {
    if (error?.code === 'ENOENT') return [];
    throw error;
  }

  const records = [];
  for (const fileName of fileNames) {
    let content;
    try {
      // Rotation can retire a generation between listing and reading it.
      // oxlint-disable-next-line eslint/no-await-in-loop
      content = await readFile(`${detailLogDirectory}/${fileName}`, 'utf8');
    } catch (error) {
      if (error?.code === 'ENOENT') continue;
      throw error;
    }
    assert.equal(content.includes(token), false, `${fileName} contains the bridge bearer token`);

    const lines = content.split('\n');
    if (!content.endsWith('\n')) lines.pop();
    for (const line of lines.filter((entry) => entry.length > 0)) {
      const record = JSON.parse(line);
      assert.ok(record !== null && typeof record === 'object' && !Array.isArray(record));
      records.push(record);
    }
  }
  return records;
}

async function waitForDetailedLogRecord(predicate, failureMessage) {
  const deadline = Date.now() + 5_000;
  do {
    // The bridge sends its response before the request worker publishes the completion record.
    // oxlint-disable-next-line eslint/no-await-in-loop
    const record = (await readDetailedLogRecords()).find(predicate);
    if (record) return record;
    // oxlint-disable-next-line eslint/no-await-in-loop
    await delay(100);
  } while (Date.now() < deadline);

  throw new Error(failureMessage);
}

function assertDetailedLogEnvelope(record) {
  assert.ok(!Number.isNaN(Date.parse(record.timestamp)));
  assert.equal(record.service, 'dirt-mcp-paper');
  assert.equal(record.component, 'bridge');
  assert.equal(record.event, 'bridge.request_completed');
  assert.ok(typeof record.message === 'string' && record.message.length > 0);
  assert.ok(Number.isSafeInteger(record.pid) && record.pid > 0);
  assert.ok(typeof record.thread === 'string' && record.thread.length > 0);
  assert.ok(Number.isSafeInteger(record.thread_id) && record.thread_id > 0);
}

async function assertDetailedPingLog(callId) {
  const record = await waitForDetailedLogRecord(
    (candidate) => sameUuid(candidate.call_id, callId),
    `Paper detail log did not record ping call ${callId}`,
  );
  assertDetailedLogEnvelope(record);
  assert.equal(record.level, 'debug');
  assert.equal(record.call_id, callId);
  assert.equal(record.operation, 'ping_server');
  assert.equal(record.http_status, 200);
}

async function assertDetailedMutationLog(result) {
  const record = await waitForDetailedLogRecord(
    (candidate) => sameUuid(candidate.call_id, result.edit.callId) && sameUuid(candidate.edit_id, result.edit.editId),
    `Paper detail log did not record committed edit ${result.edit.editId}`,
  );
  assertDetailedLogEnvelope(record);
  assert.equal(record.level, 'info');
  assert.equal(record.call_id, result.edit.callId);
  assert.equal(record.operation, result.edit.operation);
  assert.equal(record.edit_id, result.edit.editId);
  assert.equal(record.outcome, 'committed');
  assert.equal(record.changed_block_count, result.changedBlockCount);
}

async function paperCommand(command) {
  await new Promise((resolve, reject) => {
    const child = spawn(`${repositoryRoot}/scripts/dev-paper`, ['command', command], {
      stdio: 'inherit',
      signal: AbortSignal.timeout(30_000),
    });
    child.once('error', reject);
    child.once('exit', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`Paper command ${JSON.stringify(command)} exited with status ${code}`));
      }
    });
  });
}

async function restoreBlocks(blocks) {
  for (const { position, blockState } of blocks) {
    // Fixture restoration is deliberately serial through the managed Paper console.
    // oxlint-disable-next-line eslint/no-await-in-loop
    await paperCommand(`setblock ${position.x} ${position.y} ${position.z} ${blockState} replace`);
    // The console helper only queues input. Observe the commanded state before sending the
    // next command so restoration cannot race the exact snapshot checks that follow.
    // oxlint-disable-next-line eslint/no-await-in-loop
    await waitForRestoredBlock(position, blockState);
  }
}

async function waitForRestoredBlock(position, blockState) {
  let observedState;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      // Restoration readiness retries are deliberately serial.
      // oxlint-disable-next-line eslint/no-await-in-loop
      const inspection = await bridgeRequest('/v1/get-region-blocks', {
        world,
        min: position,
        max: position,
        includeAir: true,
        format: 'blocks',
      });
      observedState = inspection.blocks[0]?.blockState;
      if (observedState === blockState) return;
    } catch {
      // A transient bridge failure is retried until the bounded deadline below.
    }
    // oxlint-disable-next-line eslint/no-await-in-loop
    await delay(100);
  }
  throw new Error(
    `Paper did not restore ${position.x},${position.y},${position.z} to ${blockState}; observed ${String(observedState)}`,
  );
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
      await delay(100);
    }
  }
  throw lastError;
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

function retainEdit(result, operation) {
  assert.equal(result.outcome, 'committed');
  assert.ok(result.edit);
  assert.match(result.edit.editId, uuidV4Pattern);
  assert.match(result.edit.callId, uuidV4Pattern);
  assert.match(result.edit.worldId, uuidPattern);
  assert.equal(result.edit.operation, operation);
  assert.equal(result.edit.world, world);
  assert.deepEqual(result.edit.bounds, result.bounds);
  assert.equal(result.edit.changedBlockCount, result.changedBlockCount);
  assert.equal(result.edit.status, 'committed');
  assert.ok(!Number.isNaN(Date.parse(result.edit.completedAt)));
}

async function undoRetained(result) {
  const editId = editIdsToUndo.at(-1);
  assert.ok(sameUuid(editId, result.edit.editId));
  const undone = await bridgeRequest('/v1/undo-edit', { world, editId });
  assert.deepEqual(undone.edit, result.edit);
  assert.match(undone.undoCallId, uuidV4Pattern);
  assert.ok(!Number.isNaN(Date.parse(undone.undoneAt)));
  return undone;
}

async function assertEditHistory(expectedEdits) {
  const history = await bridgeRequest('/v1/get-edit-history', { world });
  assert.equal(history.world, world);
  assert.deepEqual(history.edits, expectedEdits);
}

function ownsSmokeEdit(edit) {
  return (
    (typeof edit?.callId === 'string' && mutationCallIds.has(edit.callId.toLowerCase())) ||
    (typeof edit?.editId === 'string' && observedEditIds.has(edit.editId.toLowerCase()))
  );
}

async function cleanupRetainedEdits() {
  let emptyObservations = 0;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    let historyResponse;
    try {
      // A timed-out mutation may still hold the world lock briefly while finishing its recovery path.
      // oxlint-disable-next-line eslint/no-await-in-loop
      historyResponse = await bridgeResponse('/v1/get-edit-history', { world });
    } catch (error) {
      if (attempt === 39) throw error;
      // Cleanup readiness retries are deliberately serial.
      // oxlint-disable-next-line eslint/no-await-in-loop
      await delay(250);
      continue;
    }

    if (!historyResponse.ok) {
      const code = historyResponse.body.error?.code;
      if (code !== 'world_busy' && code !== 'bridge_busy' && code !== 'server_unavailable') {
        throw new Error(`History cleanup returned ${historyResponse.status}: ${JSON.stringify(historyResponse.body)}`);
      }
      // Cleanup readiness retries are deliberately serial.
      // oxlint-disable-next-line eslint/no-await-in-loop
      await delay(250);
      continue;
    }

    assert.equal(historyResponse.body.world, world);
    assert.ok(Array.isArray(historyResponse.body.edits));
    const ownedEdits = historyResponse.body.edits.filter(ownsSmokeEdit);
    editIdsToUndo.length = 0;
    for (const edit of ownedEdits.toReversed()) trackEditId(edit.editId);
    if (ownedEdits.length === 0) {
      if (historyResponse.body.edits.length !== 0) {
        throw new Error('A non-smoke edit is retained; refusing direct fixture restoration');
      }
      emptyObservations += 1;
      if (emptyObservations === 2) return;
      // Confirm emptiness after pending authenticated work has had a chance to acquire the lock.
      // oxlint-disable-next-line eslint/no-await-in-loop
      await delay(250);
      continue;
    }

    emptyObservations = 0;
    const latest = historyResponse.body.edits[0];
    if (!ownsSmokeEdit(latest)) {
      throw new Error('A non-smoke edit is newer than a retained smoke-test edit; refusing to undo it');
    }
    // Undo is deliberately serial because every request consumes the previous history entry.
    let undo;
    try {
      // oxlint-disable-next-line eslint/no-await-in-loop
      undo = await bridgeResponse('/v1/undo-edit', { world, editId: latest.editId });
    } catch {
      // A transport failure is ambiguous: the undo may still be running. The next history
      // request is an ordering fence and must settle before any direct fixture restoration.
      // oxlint-disable-next-line eslint/no-await-in-loop
      await delay(250);
      continue;
    }
    if (!undo.ok && undo.body.error?.code !== 'edit_not_found') {
      if (undo.body.error?.code === 'world_busy' || undo.body.error?.code === 'bridge_busy') {
        // Cleanup readiness retries are deliberately serial.
        // oxlint-disable-next-line eslint/no-await-in-loop
        await delay(250);
        continue;
      }
      throw new Error(`Undo cleanup returned ${undo.status}: ${JSON.stringify(undo.body)}`);
    }
  }
  throw new Error('Timed out reconciling retained smoke-test edits');
}

let fixtureIsForceLoaded = false;
let originalRegionFixture;
let originalStairFixture;
let originalSetFixture;
let cleanupFailed = false;
let smokeCompleted = false;
try {
  const unauthenticated = await fetch(`${baseUrl}/v1/ping`, { signal: AbortSignal.timeout(3_000) });
  assert.equal(unauthenticated.status, 401);
  assert.equal((await unauthenticated.json()).error.code, 'unauthorized');

  const unknownRoute = await fetch(`${baseUrl}/v1/ping/extra`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(3_000),
  });
  assert.equal(unknownRoute.status, 404);
  assert.equal((await unknownRoute.json()).error.code, 'not_found');

  const pingCallId = randomUUID();
  assert.deepEqual(await bridgeGet('/v1/ping', pingCallId), { status: 'ok' });
  await assertDetailedPingLog(pingCallId);
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
  assert.ok(serverStatus.editHistory.maxEntriesPerWorld > 0);
  assert.ok(serverStatus.editHistory.maxEntriesTotal >= serverStatus.editHistory.maxEntriesPerWorld);
  assert.ok(serverStatus.editHistory.maxRetainedChangedBlocks >= serverStatus.limits.maxChangedBlocks);
  assert.deepEqual(serverStatus.logging, {
    consoleLevel: 'info',
    detailFileMaxBytes: 10_485_760,
    detailFileRetainedFiles: 5,
  });
  assert.deepEqual(serverStatus.tools, {
    ping_server: true,
    get_server_status: true,
    count_region_block_states: true,
    get_region_blocks: true,
    scan_orthographic_view: true,
    replace_region_blocks: true,
    fill_region: true,
    set_blocks: true,
    get_edit_history: true,
    undo_edit: true,
  });
  await assertEditHistory([]);

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
  originalRegionFixture = await bridgeRequest('/v1/get-region-blocks', {
    ...region,
    includeAir: true,
    format: 'blocks',
  });
  assert.equal(originalRegionFixture.matchedBlockCount, original.volume);

  originalSetFixture = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: setMin,
    max: setMax,
    includeAir: true,
    format: 'blocks',
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
    origin: setMin,
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
  assert.equal(setPreview.outcome, 'preview');
  assert.equal(setPreview.edit, null);
  assert.equal(setPreview.blockCount, 2);
  assert.ok(setPreview.changedBlockCount === 1 || setPreview.changedBlockCount === 2);
  assert.equal(setPreview.unchangedBlockCount, 2 - setPreview.changedBlockCount);
  await assertEditHistory([]);

  const duplicateSet = await bridgeResponse('/v1/set-blocks', {
    world,
    origin: setMin,
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
    origin: setMin,
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
    min: setMin,
    max: setMax,
    includeAir: true,
  });
  assert.deepEqual(sortedBlockKeys(afterInvalidSet.blocks), sortedBlockKeys(originalSetFixture.blocks));

  const setResponse = await bridgeResponse('/v1/set-blocks', {
    world,
    origin: setMin,
    palettes: setPalettes,
    placements: [
      [0, 0, 0, 0],
      [1, 1, 0, 0],
    ],
    seed: setSeed,
  });
  assert.equal(setResponse.status, 200);
  const setResult = setResponse.body;
  retainEdit(setResult, 'set_blocks');
  assert.equal(setResult.edit.callId, setResponse.callId);
  assert.equal(setResult.world, world);
  assert.deepEqual(setResult.bounds, { min: setMin, max: setMax });
  assert.deepEqual(setResult.palettes, setPalettes);
  assert.equal(setResult.seed, setSeed);
  assert.equal(setResult.blockCount, 2);
  assert.equal(setResult.changedBlockCount, setPreview.changedBlockCount);
  assert.equal(setResult.unchangedBlockCount, setPreview.unchangedBlockCount);
  await assertEditHistory([setResult.edit]);
  const afterSet = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: setMin,
    max: setMax,
    includeAir: true,
  });
  const afterSetStates = new Map(
    afterSet.blocks.map((block) => [`${block.position.x},${block.position.y},${block.position.z}`, block.blockState]),
  );
  assert.ok([firstOriginalState, firstSetState].includes(afterSetStates.get('5,0,0')));
  assert.equal(afterSetStates.get('6,0,0'), secondSetState);
  await undoRetained(setResult);
  await assertEditHistory([]);
  const consumedSetUndo = await bridgeResponse('/v1/undo-edit', { world, editId: setResult.edit.editId });
  assert.equal(consumedSetUndo.status, 404);
  assert.equal(consumedSetUndo.body.error.code, 'edit_not_found');
  const afterSetUndo = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: setMin,
    max: setMax,
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
  assert.equal(preview.outcome, 'preview');
  assert.equal(preview.edit, null);
  assert.equal(preview.volume, original.volume);
  assert.equal(preview.changedBlockCount, original.volume - alreadyMatching);
  assert.ok(preview.changedBlockCount > 0, 'Smoke destination must change at least one block');
  await assertEditHistory([]);

  const filled = await bridgeRequest('/v1/fill-region', {
    ...region,
    destinationPalette: [{ blockState: fillBlockState }],
    seed: preview.seed,
  });
  retainEdit(filled, 'fill_region');
  const filledState = filled.destinationPalette[0].blockState;
  assert.equal(filled.seed, preview.seed);
  assert.deepEqual(filled.destinationPalette, preview.destinationPalette);
  assert.equal(filled.changedBlockCount, preview.changedBlockCount);
  await assertEditHistory([filled.edit]);

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
  assert.equal(replacePreview.outcome, 'preview');
  assert.equal(replacePreview.edit, null);
  assert.equal(replacePreview.matchedBlockCount, 8);
  assert.equal(replacePreview.changedBlockCount, 8);

  const replaced = await bridgeRequest('/v1/replace-region-blocks', {
    ...region,
    sourceBlockStatePatterns: [filledState],
    destinationPalette: [{ blockState: replacementDestination }],
    seed: replacePreview.seed,
  });
  retainEdit(replaced, 'replace_region_blocks');
  assert.equal(replaced.matchedBlockCount, replacePreview.matchedBlockCount);
  assert.equal(replaced.changedBlockCount, replacePreview.changedBlockCount);
  await assertEditHistory([replaced.edit, filled.edit]);
  assertExactBlocks(await bridgeRequest('/v1/get-region-blocks', region), replaced.destinationPalette[0].blockState);

  const nonLatestUndo = await bridgeResponse('/v1/undo-edit', { world, editId: filled.edit.editId });
  assert.equal(nonLatestUndo.status, 409);
  assert.equal(nonLatestUndo.body.error.code, 'edit_not_latest');
  await assertEditHistory([replaced.edit, filled.edit]);

  await undoRetained(replaced);
  await assertEditHistory([filled.edit]);
  assertExactBlocks(await bridgeRequest('/v1/get-region-blocks', region), filledState);

  const noOp = await bridgeRequest('/v1/fill-region', {
    ...region,
    destinationPalette: [{ blockState: filledState }],
  });
  assert.equal(noOp.changedBlockCount, 0);
  assert.equal(noOp.outcome, 'no_change');
  assert.equal(noOp.edit, null);
  await assertEditHistory([filled.edit]);

  await undoRetained(filled);
  await assertEditHistory([]);

  const restored = await bridgeRequest('/v1/count-region-block-states', region);
  assert.deepEqual(restored, original);
  const restoredBlocks = await bridgeRequest('/v1/get-region-blocks', {
    ...region,
    includeAir: true,
    format: 'blocks',
  });
  assert.deepEqual(sortedBlockKeys(restoredBlocks.blocks), sortedBlockKeys(originalRegionFixture.blocks));
  originalRegionFixture = undefined;

  originalStairFixture = await bridgeRequest('/v1/get-region-blocks', {
    ...stairRegion,
    includeAir: true,
    format: 'blocks',
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
  retainEdit(exactStateReplacement, 'replace_region_blocks');
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

  await undoRetained(exactStateReplacement);

  const replayedReplacement = await bridgeRequest('/v1/replace-region-blocks', {
    ...stairRegion,
    sourceBlockStatePatterns: ['minecraft:dark_oak_stairs', 'minecraft:air'],
    destinationPalette: [
      { blockState: 'minecraft:gold_block', weight: 50 },
      { blockState: 'minecraft:diamond_block', weight: 50 },
    ],
    seed: exactStatePreview.seed,
  });
  retainEdit(replayedReplacement, 'replace_region_blocks');
  assert.equal(replayedReplacement.changedBlockCount, exactStateReplacement.changedBlockCount);
  const replayedBlocks = await bridgeRequest('/v1/get-region-blocks', {
    ...stairRegion,
    includeBlockStatePatterns: ['minecraft:gold_block', 'minecraft:diamond_block'],
  });
  assert.deepEqual(sortedBlockKeys(replayedBlocks.blocks), firstSeededLayout);
  await undoRetained(replayedReplacement);

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
  retainEdit(propertyFill, 'fill_region');
  assert.equal(propertyFill.changedBlockCount, 1);
  await assertDetailedMutationLog(propertyFill);
  const propertyFillBlocks = await bridgeRequest('/v1/get-region-blocks', {
    world,
    min: stairMin,
    max: stairMin,
  });
  assert.deepEqual(sortedBlockKeys(propertyFillBlocks.blocks), [
    `${stairMin.x},${stairMin.y},${stairMin.z}:${southStairs}`,
  ]);

  await undoRetained(propertyFill);

  await restoreBlocks(originalStairFixture.blocks);
  const restoredStairFixture = await bridgeRequest('/v1/get-region-blocks', {
    ...stairRegion,
    includeAir: true,
    format: 'blocks',
  });
  assert.deepEqual(sortedBlockKeys(restoredStairFixture.blocks), sortedBlockKeys(originalStairFixture.blocks));
  originalStairFixture = undefined;
  await assertEditHistory([]);
  smokeCompleted = true;
} finally {
  let cleanupSettled = mutationCallIds.size === 0;
  if (mutationCallIds.size > 0) {
    try {
      await cleanupRetainedEdits();
      cleanupSettled = true;
    } catch (error) {
      process.stderr.write(`Could not restore smoke-test edit: ${error.message}\n`);
      cleanupFailed = true;
    }
  }
  if (!cleanupSettled && (originalRegionFixture || originalStairFixture || originalSetFixture)) {
    process.stderr.write('Skipping direct fixture restoration because bridge cleanup did not reach quiescence\n');
  }
  if (cleanupSettled && originalRegionFixture) {
    try {
      await restoreBlocks(originalRegionFixture.blocks);
    } catch (error) {
      process.stderr.write(`Could not restore region fixture: ${error.message}\n`);
      cleanupFailed = true;
    }
  }
  if (cleanupSettled && originalStairFixture) {
    try {
      await restoreBlocks(originalStairFixture.blocks);
    } catch (error) {
      process.stderr.write(`Could not restore stair fixture: ${error.message}\n`);
      cleanupFailed = true;
    }
  }
  if (cleanupSettled && originalSetFixture) {
    try {
      await restoreBlocks(originalSetFixture.blocks);
    } catch (error) {
      process.stderr.write(`Could not restore set-blocks fixture: ${error.message}\n`);
      cleanupFailed = true;
    }
  }
  if (fixtureIsForceLoaded) {
    try {
      await paperCommand('forceload remove 0 0');
    } catch (error) {
      process.stderr.write(`Could not release smoke-test chunk: ${error.message}\n`);
      cleanupFailed = true;
    }
  }
  if (cleanupFailed) process.exitCode = 1;
}

if (smokeCompleted && !cleanupFailed) process.stdout.write(`managed server smoke test passed in ${world}\n`);
