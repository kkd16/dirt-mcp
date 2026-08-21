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
const copyMin = { x: 7, y: 0, z: 0 };
const copyMax = { x: 8, y: 0, z: 0 };
const northStairs = 'minecraft:dark_oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]';
const southStairs = 'minecraft:dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]';
const baseUrl = `http://127.0.0.1:${bridgePort}`;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const uuidV4Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const editIdsToUndo = [];
const observedEditIds = new Set();
const mutationCallIds = new Set();
const editMutationPaths = new Set(['/v1/replace-region-blocks', '/v1/set-blocks']);
const longRunningMutationPaths = new Set([...editMutationPaths, '/v1/run-minecraft-commands']);
const inspectionPaths = new Set([
  '/v1/count-region-block-states',
  '/v1/get-player-context',
  '/v1/get-perspective-view',
  '/v1/get-blocks',
  '/v1/scan-orthographic-view',
]);

function bridgeTimeoutMilliseconds(path) {
  if (longRunningMutationPaths.has(path) || path === '/v1/undo-edit') return 120_000;
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

async function assertDetailedCommandLog(callId, commandMarker, expectedResultCount) {
  const record = await waitForDetailedLogRecord(
    (candidate) => sameUuid(candidate.call_id, callId),
    `Paper detail log did not record command call ${callId}`,
  );
  assertDetailedLogEnvelope(record);
  assert.equal(record.call_id, callId);
  assert.equal(record.operation, 'run_minecraft_commands');
  assert.equal(record.http_status, 200);
  assert.equal(record.result_count, expectedResultCount);
  const records = await readDetailedLogRecords();
  assert.equal(JSON.stringify(records).includes(commandMarker), false, 'Paper detail logs contain raw command input');
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
      const inspection = await bridgeRequest('/v1/get-blocks', {
        world,
        min: position,
        max: position,
        includeAir: true,
      });
      observedState = expandStructure(inspection)[0]?.blockState;
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

function relativeBlockKeys(structure) {
  return expandStructure(structure)
    .map(
      ({ position, blockState }) =>
        `${position.x - structure.origin.x},${position.y - structure.origin.y},${position.z - structure.origin.z}:${blockState}`,
    )
    .toSorted();
}

function expandStructure(structure) {
  const blocks = [];
  const add = (paletteIndex, x, y, z) => {
    const palette = structure.palettes[paletteIndex];
    assert.equal(palette?.length, 1, 'Exact get_blocks palettes must be singletons');
    assert.equal(Object.hasOwn(palette[0], 'weight'), false, 'Exact get_blocks palettes cannot be weighted');
    blocks.push({
      position: { x: structure.origin.x + x, y: structure.origin.y + y, z: structure.origin.z + z },
      blockState: palette[0].blockState,
    });
  };
  for (const [paletteIndex, x, y, z] of structure.placements) add(paletteIndex, x, y, z);
  for (const [paletteIndex, x, y, z, toX, toY, toZ] of structure.runs) {
    assert.ok(x <= toX && y <= toY && z <= toZ, 'Runs must use forward inclusive corners');
    for (let offsetY = y; offsetY <= toY; offsetY += 1) {
      for (let offsetZ = z; offsetZ <= toZ; offsetZ += 1) {
        for (let offsetX = x; offsetX <= toX; offsetX += 1) add(paletteIndex, offsetX, offsetY, offsetZ);
      }
    }
  }
  assert.equal(new Set(blocks.map(({ position }) => `${position.x},${position.y},${position.z}`)).size, blocks.length);
  return blocks;
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

function assertExactBlocks(inspection, blockState) {
  const blocks = expandStructure(inspection);
  assert.deepEqual(inspection.origin, min);
  assert.equal(blocks.length, 8);
  assert.deepEqual(sortedBlockKeys(blocks), expectedBlockKeys(blockState));
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
let originalCopyFixture;
let cleanupFailed = false;
let smokeCompleted = false;
try {
  const unauthenticated = await fetch(`${baseUrl}/v1/ping`, { signal: AbortSignal.timeout(3_000) });
  assert.equal(unauthenticated.status, 401);
  assert.deepEqual((await unauthenticated.json()).error, {
    code: 'unauthorized',
    message: 'A valid bearer token is required',
    details: { reason: 'authentication_failed' },
  });

  const unknownRoute = await fetch(`${baseUrl}/v1/ping/extra`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(3_000),
  });
  assert.equal(unknownRoute.status, 404);
  assert.deepEqual((await unknownRoute.json()).error, {
    code: 'not_found',
    message: 'No bridge operation matches this path',
    details: { reason: 'route_not_found' },
  });

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
  assert.ok(serverStatus.limits.maxCommandsPerRequest > 0);
  assert.ok(serverStatus.limits.maxCommandFeedbackCharacters > 0);
  assert.ok(serverStatus.editHistory.maxEntriesPerWorld > 0);
  assert.ok(serverStatus.editHistory.maxEntriesTotal >= serverStatus.editHistory.maxEntriesPerWorld);
  assert.ok(serverStatus.editHistory.maxRetainedChangedBlocks >= serverStatus.limits.maxChangedBlocks);
  assert.deepEqual(serverStatus.logging, {
    consoleLevel: 'info',
    detailFileMaxBytes: 10_485_760,
    detailFileRetainedFiles: 5,
  });
  assert.deepEqual(serverStatus.defaults, { getBlocksIncludeAir: false, editDryRun: false });
  assert.deepEqual(serverStatus.tools, {
    ping_server: true,
    get_server_status: true,
    get_player_context: true,
    get_perspective_view: true,
    count_region_block_states: true,
    get_blocks: true,
    scan_orthographic_view: true,
    replace_region_blocks: true,
    set_blocks: true,
    get_edit_history: true,
    undo_edit: true,
    run_minecraft_commands: true,
  });
  await assertEditHistory([]);

  const missingPlayer = randomUUID();
  const missingPlayerContext = await bridgeResponse('/v1/get-player-context', {
    player: missingPlayer,
  });
  assert.equal(missingPlayerContext.status, 404);
  assert.deepEqual(missingPlayerContext.body.error, {
    code: 'player_not_found',
    message: `Player is not online: ${missingPlayer}`,
    details: { player: missingPlayer },
  });
  const missingPlayerPerspective = await bridgeResponse('/v1/get-perspective-view', {
    source: { type: 'player', player: missingPlayer },
  });
  assert.equal(missingPlayerPerspective.status, 404);
  assert.deepEqual(missingPlayerPerspective.body.error, {
    code: 'player_not_found',
    message: `Player is not online: ${missingPlayer}`,
    details: { player: missingPlayer },
  });

  const chunkHeavyRegion = await bridgeResponse('/v1/count-region-block-states', {
    world,
    min: { x: 0, y: 0, z: 0 },
    max: { x: 4096, y: 0, z: 0 },
  });
  assert.equal(chunkHeavyRegion.status, 413);
  assert.equal(chunkHeavyRegion.body.error.code, 'region_too_large');
  assert.deepEqual(chunkHeavyRegion.body.error.details, {
    reason: 'touched_chunks',
    minimumRequired: serverStatus.limits.maxInspectionTouchedChunks + 1,
    maximum: serverStatus.limits.maxInspectionTouchedChunks,
  });

  await paperCommand('forceload add 0 0');
  fixtureIsForceLoaded = true;
  const original = await waitForRegion(region);
  originalRegionFixture = await bridgeRequest('/v1/get-blocks', {
    ...region,
    includeAir: true,
  });
  assert.equal(expandStructure(originalRegionFixture).length, original.volume);

  originalSetFixture = await bridgeRequest('/v1/get-blocks', {
    world,
    min: setMin,
    max: setMax,
    includeAir: true,
  });
  assert.equal(expandStructure(originalSetFixture).length, 2);
  const originalSetStates = new Map(
    expandStructure(originalSetFixture).map((block) => [
      `${block.position.x},${block.position.y},${block.position.z}`,
      block.blockState,
    ]),
  );
  const firstOriginalState = originalSetStates.get('5,0,0');
  const secondOriginalState = originalSetStates.get('6,0,0');
  assert.ok(firstOriginalState);
  assert.ok(secondOriginalState);
  originalCopyFixture = await bridgeRequest('/v1/get-blocks', {
    world,
    min: copyMin,
    max: copyMax,
    includeAir: true,
  });
  const originalCopyStates = expandStructure(originalCopyFixture);
  const firstCopyState = originalCopyStates.find(({ position }) => position.x === copyMin.x)?.blockState;
  const secondCopyState = originalCopyStates.find(({ position }) => position.x === copyMax.x)?.blockState;
  assert.ok(firstCopyState);
  assert.ok(secondCopyState);
  const commandStates = ['minecraft:stone', 'minecraft:gold_block', 'minecraft:diamond_block'].filter(
    (state) => state !== firstOriginalState,
  );
  const commandFirstState = commandStates[0];
  const commandSecondState = commandStates[1];
  assert.ok(commandFirstState);
  assert.ok(commandSecondState);

  const oversizedCommandBatch = await bridgeResponse('/v1/run-minecraft-commands', {
    commands: [
      `setblock ${setMin.x} ${setMin.y} ${setMin.z} ${commandFirstState} replace`,
      ...Array.from({ length: serverStatus.limits.maxCommandsPerRequest }, () => 'time query gametime'),
    ],
  });
  assert.equal(oversizedCommandBatch.status, 400);
  assert.deepEqual(oversizedCommandBatch.body.error.details, {
    reason: 'too_many_items',
    fields: ['commands'],
    maximum: serverStatus.limits.maxCommandsPerRequest,
  });
  const afterOversizedCommandBatch = await bridgeRequest('/v1/get-blocks', {
    world,
    min: setMin,
    max: setMin,
    includeAir: true,
  });
  assert.equal(expandStructure(afterOversizedCommandBatch)[0].blockState, firstOriginalState);

  const commandRunResponse = await bridgeResponse('/v1/run-minecraft-commands', {
    commands: [
      'dirt version',
      `/setblock ${setMin.x} ${setMin.y} ${setMin.z} ${commandFirstState} replace`,
      `setblock ${setMin.x} ${setMin.y} ${setMin.z} ${commandSecondState} replace`,
      'time query gametime',
    ],
  });
  assert.equal(commandRunResponse.status, 200);
  const commandRun = commandRunResponse.body;
  assert.equal(typeof commandRun.sender.name, 'string');
  assert.ok(commandRun.sender.name.length > 0);
  assert.equal(commandRun.sender.isOperator, true);
  assert.equal(commandRun.sender.isPlayer, false);
  assert.equal(commandRun.feedbackTruncated, false);
  assert.deepEqual(
    commandRun.results.map(({ command, outcome }) => ({ command, outcome })),
    [
      { command: 'dirt version', outcome: 'dispatched' },
      {
        command: `setblock ${setMin.x} ${setMin.y} ${setMin.z} ${commandFirstState} replace`,
        outcome: 'dispatched',
      },
      {
        command: `setblock ${setMin.x} ${setMin.y} ${setMin.z} ${commandSecondState} replace`,
        outcome: 'dispatched',
      },
      { command: 'time query gametime', outcome: 'dispatched' },
    ],
  );
  const [versionFeedback, , , timeFeedback] = commandRun.results.map(({ feedback }) => feedback.join('\n'));
  assert.ok(versionFeedback.includes(serverStatus.builds.dirtMcp));
  assert.ok(timeFeedback.length > 0);
  assert.ok(commandRun.results.every(({ message, rawMessage }) => message === null && rawMessage === null));
  const afterOrderedCommands = await bridgeRequest('/v1/get-blocks', {
    world,
    min: setMin,
    max: setMin,
  });
  assert.equal(expandStructure(afterOrderedCommands)[0].blockState, commandSecondState);

  const commandMarker = `dirt_smoke_missing_${randomUUID().replaceAll('-', '')}`;
  const missingCommandRun = await bridgeResponse('/v1/run-minecraft-commands', {
    commands: [commandMarker, `setblock ${setMin.x} ${setMin.y} ${setMin.z} ${commandFirstState} replace`],
  });
  assert.equal(missingCommandRun.status, 200);
  assert.deepEqual(
    missingCommandRun.body.results.map(({ outcome }) => outcome),
    ['not_found'],
  );
  assert.ok(missingCommandRun.body.results[0].message.length > 0);
  assert.equal(missingCommandRun.body.results[0].rawMessage, null);
  await assertDetailedCommandLog(missingCommandRun.callId, commandMarker, 1);
  const afterMissingCommand = await bridgeRequest('/v1/get-blocks', {
    world,
    min: setMin,
    max: setMin,
  });
  assert.equal(expandStructure(afterMissingCommand)[0].blockState, commandSecondState);

  const invalidTimeQuery = `time query dirt_smoke_invalid_${randomUUID().replaceAll('-', '')}`;
  const failedCommandRun = await bridgeRequest('/v1/run-minecraft-commands', {
    commands: [invalidTimeQuery, `setblock ${setMin.x} ${setMin.y} ${setMin.z} ${firstOriginalState} replace`],
  });
  assert.deepEqual(
    failedCommandRun.results.map(({ outcome }) => outcome),
    ['dispatch_failed'],
  );
  assert.ok(failedCommandRun.results[0].message.length > 0);
  assert.ok(failedCommandRun.results[0].rawMessage.length > 0);
  const afterFailedCommand = await bridgeRequest('/v1/get-blocks', {
    world,
    min: setMin,
    max: setMin,
    includeAir: true,
  });
  assert.equal(expandStructure(afterFailedCommand)[0].blockState, commandSecondState);

  const restoredCommandRun = await bridgeRequest('/v1/run-minecraft-commands', {
    commands: [`setblock ${setMin.x} ${setMin.y} ${setMin.z} ${firstOriginalState} replace`],
  });
  assert.deepEqual(
    restoredCommandRun.results.map(({ outcome }) => outcome),
    ['dispatched'],
  );
  const afterCommandRestore = await bridgeRequest('/v1/get-blocks', {
    world,
    min: setMin,
    max: setMin,
    includeAir: true,
  });
  assert.equal(expandStructure(afterCommandRestore)[0].blockState, firstOriginalState);
  await assertEditHistory([]);

  const distinctStates = [
    'minecraft:diamond_block',
    'minecraft:gold_block',
    'minecraft:emerald_block',
    'minecraft:redstone_block',
    'minecraft:lapis_block',
    'minecraft:iron_block',
    'minecraft:copper_block',
    'minecraft:coal_block',
  ];
  const setStates = distinctStates.filter(
    (state) => ![firstOriginalState, secondOriginalState, firstCopyState, secondCopyState].includes(state),
  );
  assert.ok(setStates.length >= 2);
  const firstSetState = setStates[0];
  const secondSetState = setStates[1];
  const setPalettes = [
    [
      { blockState: firstSetState, weight: 50 },
      { blockState: secondSetState, weight: 50 },
    ],
  ];
  const setSeed = 1_234_567;

  const emptySet = await bridgeRequest('/v1/set-blocks', {
    world,
    origin: setMin,
    palettes: [],
    placements: [],
    runs: [],
    seed: setSeed,
    dryRun: true,
  });
  assert.deepEqual(emptySet, {
    world,
    bounds: null,
    palettes: [],
    seed: setSeed,
    outcome: 'no_change',
    edit: null,
    blockCount: 0,
    changedBlockCount: 0,
    unchangedBlockCount: 0,
  });
  await assertEditHistory([]);

  const setPreview = await bridgeRequest('/v1/set-blocks', {
    world,
    origin: setMin,
    palettes: setPalettes,
    placements: [],
    runs: [[0, 0, 0, 0, 1, 0, 0]],
    seed: setSeed,
    dryRun: true,
  });
  assert.equal(setPreview.world, world);
  assert.deepEqual(setPreview.palettes, setPalettes);
  assert.equal(setPreview.seed, setSeed);
  assert.equal(setPreview.outcome, 'preview');
  assert.equal(setPreview.edit, null);
  assert.equal(setPreview.blockCount, 2);
  assert.equal(setPreview.changedBlockCount, 2);
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
    runs: [],
  });
  assert.equal(duplicateSet.status, 400);
  assert.equal(duplicateSet.body.error.code, 'invalid_request');
  assert.deepEqual(duplicateSet.body.error.details, { reason: 'duplicate', field: 'placements[1]' });

  const invalidSet = await bridgeResponse('/v1/set-blocks', {
    world,
    origin: setMin,
    palettes: [[{ blockState: firstSetState }], [{ blockState: 'minecraft:not_a_block' }]],
    placements: [
      [0, 0, 0, 0],
      [1, 1, 0, 0],
    ],
    runs: [],
  });
  assert.equal(invalidSet.status, 400);
  assert.equal(invalidSet.body.error.code, 'invalid_request');
  assert.deepEqual(invalidSet.body.error.details, {
    reason: 'invalid_value',
    field: 'palettes[1][0].blockState',
  });
  const afterInvalidSet = await bridgeRequest('/v1/get-blocks', {
    world,
    min: setMin,
    max: setMax,
    includeAir: true,
  });
  assert.deepEqual(
    sortedBlockKeys(expandStructure(afterInvalidSet)),
    sortedBlockKeys(expandStructure(originalSetFixture)),
  );

  const setResponse = await bridgeResponse('/v1/set-blocks', {
    world,
    origin: setMin,
    palettes: setPalettes,
    placements: [],
    runs: [[0, 0, 0, 0, 1, 0, 0]],
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
  const afterSet = await bridgeRequest('/v1/get-blocks', {
    world,
    min: setMin,
    max: setMax,
    includeAir: true,
  });
  const afterSetStates = new Map(
    expandStructure(afterSet).map((block) => [
      `${block.position.x},${block.position.y},${block.position.z}`,
      block.blockState,
    ]),
  );
  assert.ok(setStates.includes(afterSetStates.get('5,0,0')));
  assert.ok(setStates.includes(afterSetStates.get('6,0,0')));

  const copyResponse = await bridgeResponse('/v1/set-blocks', { ...afterSet, origin: copyMin });
  assert.equal(copyResponse.status, 200);
  const copyResult = copyResponse.body;
  retainEdit(copyResult, 'set_blocks');
  assert.equal(copyResult.edit.callId, copyResponse.callId);
  assert.deepEqual(copyResult.bounds, { min: copyMin, max: copyMax });
  assert.equal(copyResult.blockCount, 2);
  assert.equal(copyResult.changedBlockCount, 2);
  await assertEditHistory([copyResult.edit, setResult.edit]);
  const copiedStructure = await bridgeRequest('/v1/get-blocks', {
    world,
    min: copyMin,
    max: copyMax,
    includeAir: true,
  });
  assert.deepEqual(relativeBlockKeys(copiedStructure), relativeBlockKeys(afterSet));
  await undoRetained(copyResult);
  await assertEditHistory([setResult.edit]);
  const afterCopyUndo = await bridgeRequest('/v1/get-blocks', {
    world,
    min: copyMin,
    max: copyMax,
    includeAir: true,
  });
  assert.deepEqual(
    sortedBlockKeys(expandStructure(afterCopyUndo)),
    sortedBlockKeys(expandStructure(originalCopyFixture)),
  );
  originalCopyFixture = undefined;

  await undoRetained(setResult);
  await assertEditHistory([]);
  const consumedSetUndo = await bridgeResponse('/v1/undo-edit', { world, editId: setResult.edit.editId });
  assert.equal(consumedSetUndo.status, 404);
  assert.equal(consumedSetUndo.body.error.code, 'edit_not_found');
  assert.deepEqual(consumedSetUndo.body.error.details, {
    world,
    requestedEditId: setResult.edit.editId,
  });
  const afterSetUndo = await bridgeRequest('/v1/get-blocks', {
    world,
    min: setMin,
    max: setMax,
    includeAir: true,
  });
  assert.deepEqual(
    sortedBlockKeys(expandStructure(afterSetUndo)),
    sortedBlockKeys(expandStructure(originalSetFixture)),
  );
  originalSetFixture = undefined;

  const originalStates = Object.keys(original.blockStateCounts);
  const regionBlockState =
    originalStates.length === 1 && originalStates[0].startsWith('minecraft:barrier')
      ? 'minecraft:amethyst_block'
      : 'minecraft:barrier';
  const regionSetInput = {
    world,
    origin: min,
    palettes: [[{ blockState: regionBlockState }]],
    placements: [],
    runs: [[0, 0, 0, 0, max.x - min.x, max.y - min.y, max.z - min.z]],
  };

  const regionPreview = await bridgeRequest('/v1/set-blocks', { ...regionSetInput, dryRun: true });
  const previewState = regionPreview.palettes[0][0].blockState;
  const alreadyMatching = original.blockStateCounts[previewState] ?? 0;
  assert.equal(regionPreview.outcome, 'preview');
  assert.equal(regionPreview.edit, null);
  assert.equal(regionPreview.blockCount, original.volume);
  assert.equal(regionPreview.changedBlockCount, original.volume - alreadyMatching);
  assert.ok(regionPreview.changedBlockCount > 0, 'Smoke destination must change at least one block');
  await assertEditHistory([]);

  const regionSet = await bridgeRequest('/v1/set-blocks', {
    ...regionSetInput,
    seed: regionPreview.seed,
  });
  retainEdit(regionSet, 'set_blocks');
  const regionState = regionSet.palettes[0][0].blockState;
  assert.equal(regionSet.seed, regionPreview.seed);
  assert.deepEqual(regionSet.palettes, regionPreview.palettes);
  assert.equal(regionSet.changedBlockCount, regionPreview.changedBlockCount);
  await assertEditHistory([regionSet.edit]);

  const afterRegionSet = await bridgeRequest('/v1/count-region-block-states', region);
  assert.deepEqual(afterRegionSet.blockStateCounts, { [regionState]: regionSet.blockCount });

  const exactBlocks = await bridgeRequest('/v1/get-blocks', region);
  assertExactBlocks(exactBlocks, regionState);

  const syntheticCamera = { x: 0.5, y: 3, z: 0.5 };
  const perspective = await bridgeRequest('/v1/get-perspective-view', {
    source: {
      type: 'location',
      world,
      cameraPosition: syntheticCamera,
      rotation: { yaw: 0, pitch: 90 },
    },
    width: 1,
    height: 1,
    maxDistance: 8,
  });
  assert.deepEqual(perspective.source, { type: 'location' });
  assert.equal(perspective.world, world);
  assert.deepEqual(perspective.cameraPosition, syntheticCamera);
  assert.deepEqual(perspective.rotation, { yaw: 0, pitch: 90 });
  assert.ok(Math.abs(perspective.lookDirection.x) < 1e-12);
  assert.ok(Math.abs(perspective.lookDirection.y + 1) < 1e-12);
  assert.ok(Math.abs(perspective.lookDirection.z) < 1e-12);
  assert.deepEqual(perspective.basis.forward, perspective.lookDirection);
  assert.equal(perspective.viewport.width, 1);
  assert.equal(perspective.viewport.height, 1);
  assert.equal(perspective.viewport.maxDistance, 8);
  assert.equal(perspective.checkedChunkCount, 1);
  assert.equal(perspective.blockStatePalette.length, 1);
  assert.ok(perspective.blockStatePalette[0].startsWith('minecraft:'));
  assert.equal(perspective.hits.length, 1);
  assert.equal(perspective.hits[0].blockStateIndex, 1);
  assert.equal(perspective.hits[0].blockPosition.x, 0);
  assert.equal(perspective.hits[0].blockPosition.z, 0);
  assert.ok(perspective.hits[0].blockPosition.y <= syntheticCamera.y);
  assert.equal(perspective.crosshairHitIndex, 0);

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
      blockState: regionState,
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
      blockState: regionState,
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
      details: { reason: 'visible_blocks', minimumRequired: 2, maximum: 1 },
    },
  });

  const exactRuns = await bridgeRequest('/v1/get-blocks', {
    ...region,
    includeBlockStatePatterns: [regionState],
    maxResults: 8,
  });
  assert.deepEqual(exactRuns.origin, min);
  assert.deepEqual(exactRuns.placements, []);
  assert.equal(exactRuns.runs.length, 1);
  assert.deepEqual(sortedBlockKeys(expandStructure(exactRuns)), expectedBlockKeys(regionState));

  const excluded = await bridgeRequest('/v1/get-blocks', {
    ...region,
    excludeBlockStatePatterns: [regionState],
  });
  assert.deepEqual(excluded, { world, origin: min, palettes: [], placements: [], runs: [] });

  const compressed = await bridgeRequest('/v1/get-blocks', { ...region, maxResults: 1 });
  assert.equal(compressed.placements.length + compressed.runs.length, 1);
  assert.equal(expandStructure(compressed).length, 8);

  const replacementDestination = 'minecraft:gold_block';
  const replacePreview = await bridgeRequest('/v1/replace-region-blocks', {
    ...region,
    sourceBlockStatePatterns: [regionState],
    destinationPalette: [{ blockState: replacementDestination }],
    dryRun: true,
  });
  assert.equal(replacePreview.outcome, 'preview');
  assert.equal(replacePreview.edit, null);
  assert.equal(replacePreview.matchedBlockCount, 8);
  assert.equal(replacePreview.changedBlockCount, 8);

  const replaced = await bridgeRequest('/v1/replace-region-blocks', {
    ...region,
    sourceBlockStatePatterns: [regionState],
    destinationPalette: [{ blockState: replacementDestination }],
    seed: replacePreview.seed,
  });
  retainEdit(replaced, 'replace_region_blocks');
  assert.equal(replaced.matchedBlockCount, replacePreview.matchedBlockCount);
  assert.equal(replaced.changedBlockCount, replacePreview.changedBlockCount);
  await assertEditHistory([replaced.edit, regionSet.edit]);
  assertExactBlocks(await bridgeRequest('/v1/get-blocks', region), replaced.destinationPalette[0].blockState);

  const nonLatestUndo = await bridgeResponse('/v1/undo-edit', { world, editId: regionSet.edit.editId });
  assert.equal(nonLatestUndo.status, 409);
  assert.equal(nonLatestUndo.body.error.code, 'edit_not_latest');
  assert.deepEqual(nonLatestUndo.body.error.details, {
    world,
    requestedEditId: regionSet.edit.editId,
    newestEditId: replaced.edit.editId,
  });
  await assertEditHistory([replaced.edit, regionSet.edit]);

  await undoRetained(replaced);
  await assertEditHistory([regionSet.edit]);
  assertExactBlocks(await bridgeRequest('/v1/get-blocks', region), regionState);

  const noOp = await bridgeRequest('/v1/set-blocks', regionSetInput);
  assert.equal(noOp.changedBlockCount, 0);
  assert.equal(noOp.outcome, 'no_change');
  assert.equal(noOp.edit, null);
  await assertEditHistory([regionSet.edit]);

  await undoRetained(regionSet);
  await assertEditHistory([]);

  const restored = await bridgeRequest('/v1/count-region-block-states', region);
  assert.deepEqual(restored, original);
  const restoredBlocks = await bridgeRequest('/v1/get-blocks', {
    ...region,
    includeAir: true,
  });
  assert.deepEqual(
    sortedBlockKeys(expandStructure(restoredBlocks)),
    sortedBlockKeys(expandStructure(originalRegionFixture)),
  );
  originalRegionFixture = undefined;

  originalStairFixture = await bridgeRequest('/v1/get-blocks', {
    ...stairRegion,
    includeAir: true,
  });
  assert.equal(expandStructure(originalStairFixture).length, 3);
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
  const exactStateBlocks = await bridgeRequest('/v1/get-blocks', {
    ...stairRegion,
    includeBlockStatePatterns: ['minecraft:gold_block', 'minecraft:diamond_block'],
  });
  assert.equal(expandStructure(exactStateBlocks).length, 3);
  assert.ok(
    expandStructure(exactStateBlocks).every(
      ({ blockState }) => blockState === 'minecraft:gold_block' || blockState === 'minecraft:diamond_block',
    ),
  );
  const firstSeededLayout = sortedBlockKeys(expandStructure(exactStateBlocks));

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
  const replayedBlocks = await bridgeRequest('/v1/get-blocks', {
    ...stairRegion,
    includeBlockStatePatterns: ['minecraft:gold_block', 'minecraft:diamond_block'],
  });
  assert.deepEqual(sortedBlockKeys(expandStructure(replayedBlocks)), firstSeededLayout);
  await undoRetained(replayedReplacement);

  const propertySetInput = {
    world,
    origin: stairMin,
    palettes: [[{ blockState: southStairs }]],
    placements: [],
    runs: [[0, 0, 0, 0, 0, 0, 0]],
  };
  const propertySetPreview = await bridgeRequest('/v1/set-blocks', { ...propertySetInput, dryRun: true });
  assert.equal(propertySetPreview.changedBlockCount, 1);

  const propertySet = await bridgeRequest('/v1/set-blocks', {
    ...propertySetInput,
    seed: propertySetPreview.seed,
  });
  retainEdit(propertySet, 'set_blocks');
  assert.equal(propertySet.changedBlockCount, 1);
  await assertDetailedMutationLog(propertySet);
  const propertySetBlocks = await bridgeRequest('/v1/get-blocks', {
    world,
    min: stairMin,
    max: stairMin,
  });
  assert.deepEqual(sortedBlockKeys(expandStructure(propertySetBlocks)), [
    `${stairMin.x},${stairMin.y},${stairMin.z}:${southStairs}`,
  ]);

  await undoRetained(propertySet);

  await restoreBlocks(expandStructure(originalStairFixture));
  const restoredStairFixture = await bridgeRequest('/v1/get-blocks', {
    ...stairRegion,
    includeAir: true,
  });
  assert.deepEqual(
    sortedBlockKeys(expandStructure(restoredStairFixture)),
    sortedBlockKeys(expandStructure(originalStairFixture)),
  );
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
  if (!cleanupSettled && (originalRegionFixture || originalStairFixture || originalSetFixture || originalCopyFixture)) {
    process.stderr.write('Skipping direct fixture restoration because bridge cleanup did not reach quiescence\n');
  }
  if (cleanupSettled && originalRegionFixture) {
    try {
      await restoreBlocks(expandStructure(originalRegionFixture));
    } catch (error) {
      process.stderr.write(`Could not restore region fixture: ${error.message}\n`);
      cleanupFailed = true;
    }
  }
  if (cleanupSettled && originalStairFixture) {
    try {
      await restoreBlocks(expandStructure(originalStairFixture));
    } catch (error) {
      process.stderr.write(`Could not restore stair fixture: ${error.message}\n`);
      cleanupFailed = true;
    }
  }
  if (cleanupSettled && originalSetFixture) {
    try {
      await restoreBlocks(expandStructure(originalSetFixture));
    } catch (error) {
      process.stderr.write(`Could not restore set-blocks fixture: ${error.message}\n`);
      cleanupFailed = true;
    }
  }
  if (cleanupSettled && originalCopyFixture) {
    try {
      await restoreBlocks(expandStructure(originalCopyFixture));
    } catch (error) {
      process.stderr.write(`Could not restore copied-structure fixture: ${error.message}\n`);
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
