#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { open, readdir, readFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const repositoryRoot = dirname(import.meta.dirname);
const runDirectory = `${repositoryRoot}/.dev/paper`;
const detailLogDirectory = `${runDirectory}/plugins/DirtMCP/logs`;
const token = (await readFile(`${repositoryRoot}/.dev/secrets/bridge-token`, 'utf8')).trim();
const bridgePort = 8_765;

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
const largeMin = { x: 32, y: 256, z: 32 };
const largeMax = { x: 95, y: 319, z: 95 };
const largeRegion = { world, min: largeMin, max: largeMax };
const northStairs = 'minecraft:dark_oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]';
const southStairs = 'minecraft:dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]';
const baseUrl = `http://127.0.0.1:${bridgePort}`;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const uuidV4Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const editIdsToUndo = [];
const observedEditIds = new Set();
const mutationCallIds = new Set();
const detailedLogSnapshots = new Map();
const editMutationPaths = new Set(['/v1/replace-region-blocks', '/v1/set-blocks']);
const inspectionPaths = new Set([
  '/v1/count-region-block-states',
  '/v1/get-player-context',
  '/v1/get-perspective-view',
  '/v1/get-blocks',
  '/v1/scan-orthographic-view',
]);
const bridgeOperations = [
  'pingServer',
  'getServerStatus',
  'countRegionBlockStates',
  'getBlocks',
  'scanOrthographicView',
  'getPlayerContext',
  'getPerspectiveView',
  'replaceRegionBlocks',
  'setBlocks',
  'getEditHistory',
  'undoEdits',
  'runMinecraftCommands',
];
const playerContextInclude = {
  equipment: true,
  inventory: false,
  enderChest: false,
  vitals: false,
  movement: false,
  client: false,
  effects: false,
};

function materializeBridgeRequest(path, body) {
  switch (path) {
    case '/v1/get-blocks':
      return {
        includeBlockStatePatterns: [],
        excludeBlockStatePatterns: [],
        includeAir: false,
        maxResults: 1_024,
        ...body,
      };
    case '/v1/scan-orthographic-view':
      return { depth: 0, maxResults: 1_024, ...body };
    case '/v1/get-player-context':
      return { ...body, include: { ...playerContextInclude, ...body.include } };
    case '/v1/get-perspective-view':
      return {
        width: 21,
        height: 13,
        verticalFieldOfViewDegrees: 70,
        maxDistance: 32,
        fluidCollision: 'never',
        ignorePassableBlocks: false,
        ...body,
      };
    case '/v1/replace-region-blocks':
    case '/v1/set-blocks':
      return { seed: 0, dryRun: false, maxChangedBlocks: null, ...body };
    default:
      return body;
  }
}

function bridgeTimeoutMilliseconds(path) {
  if (editMutationPaths.has(path) || path === '/v1/undo-edits') return 300_000;
  if (path === '/v1/run-minecraft-commands') return 120_000;
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

async function bridgeGet(path, callId = randomUUID()) {
  const headers = { Authorization: `Bearer ${token}`, 'X-Dirt-Call-Id': callId };
  const response = await fetch(`${baseUrl}${path}`, {
    headers,
    signal: AbortSignal.timeout(bridgeTimeoutMilliseconds(path)),
  });
  assert.equal(response.status, 200, `GET ${path} returned HTTP ${response.status}`);
  return response.json();
}

async function bridgeResponse(path, body) {
  const callId = randomUUID();
  const requestBody = materializeBridgeRequest(path, body);
  if (editMutationPaths.has(path)) mutationCallIds.add(callId);
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-Dirt-Call-Id': callId,
    },
    body: JSON.stringify(requestBody),
    signal: AbortSignal.timeout(bridgeTimeoutMilliseconds(path)),
  });
  const text = await response.text();
  const document = JSON.parse(text);
  if (editMutationPaths.has(path)) {
    const committed = response.ok && document.outcome === 'committed';
    trackEditId(committed ? document.edit?.editId : document.error?.editId);
    if (committed) assert.equal(document.edit?.label, requestBody.label);
  }
  if (response.ok && path === '/v1/undo-edits' && Array.isArray(document.undoneEdits)) {
    for (const edit of document.undoneEdits) {
      if (!sameUuid(editIdsToUndo.at(-1), edit?.editId)) break;
      editIdsToUndo.pop();
    }
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
    fileNames = (await readdir(detailLogDirectory)).filter((name) => /^dirt-detail\.\d+\.jsonl$/.test(name)).toSorted();
  } catch (error) {
    if (error?.code === 'ENOENT') return [];
    throw error;
  }

  const activeFileNames = new Set(fileNames);
  for (const fileName of detailedLogSnapshots.keys()) {
    if (!activeFileNames.has(fileName)) detailedLogSnapshots.delete(fileName);
  }

  const records = [];
  for (const fileName of fileNames) {
    // Rotation can retire a generation between listing and opening it.
    // oxlint-disable-next-line eslint/no-await-in-loop
    const fileRecords = await readDetailedLogFile(fileName);
    if (fileRecords) records.push(...fileRecords);
  }
  return records;
}

async function readDetailedLogFile(fileName) {
  let logFile;
  try {
    logFile = await open(`${detailLogDirectory}/${fileName}`, 'r');
  } catch (error) {
    if (error?.code === 'ENOENT') return undefined;
    throw error;
  }

  try {
    const stats = await logFile.stat();
    let snapshot = detailedLogSnapshots.get(fileName);
    if (!snapshot || snapshot.device !== stats.dev || snapshot.inode !== stats.ino || stats.size < snapshot.offset) {
      snapshot = {
        device: stats.dev,
        inode: stats.ino,
        offset: 0,
        pending: Buffer.alloc(0),
        records: [],
      };
    }

    const appendedLength = stats.size - snapshot.offset;
    if (appendedLength === 0) {
      detailedLogSnapshots.set(fileName, snapshot);
      return snapshot.records;
    }

    const appended = Buffer.alloc(appendedLength);
    let bytesRead = 0;
    while (bytesRead < appended.length) {
      // Positional reads are serial so a short read resumes at the correct byte.
      // oxlint-disable-next-line eslint/no-await-in-loop
      const result = await logFile.read(appended, bytesRead, appended.length - bytesRead, snapshot.offset + bytesRead);
      if (result.bytesRead === 0) break;
      bytesRead += result.bytesRead;
    }

    const content = Buffer.concat([snapshot.pending, appended.subarray(0, bytesRead)]);
    assert.equal(content.includes(token), false, `${fileName} contains the bridge bearer token`);

    const finalLineBreak = content.lastIndexOf(0x0a);
    const newRecords = [];
    if (finalLineBreak !== -1) {
      const completeContent = content.subarray(0, finalLineBreak).toString('utf8');
      for (const line of completeContent.split('\n').filter((entry) => entry.length > 0)) {
        const record = JSON.parse(line);
        assert.ok(record !== null && typeof record === 'object' && !Array.isArray(record));
        newRecords.push(record);
      }
    }

    snapshot.offset += bytesRead;
    snapshot.pending = finalLineBreak === -1 ? content : Buffer.from(content.subarray(finalLineBreak + 1));
    snapshot.records.push(...newRecords);
    detailedLogSnapshots.set(fileName, snapshot);
    return snapshot.records;
  } finally {
    await logFile.close();
  }
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
  assert.equal(record.operation_id, 'pingServer');
  assert.equal(record.http_status, 200);
}

async function assertDetailedMutationLog(result, operationId) {
  const record = await waitForDetailedLogRecord(
    (candidate) => sameUuid(candidate.call_id, result.edit.callId) && sameUuid(candidate.edit_id, result.edit.editId),
    `Paper detail log did not record committed edit ${result.edit.editId}`,
  );
  assertDetailedLogEnvelope(record);
  assert.equal(record.level, 'info');
  assert.equal(record.call_id, result.edit.callId);
  assert.equal(record.operation_id, operationId);
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
  assert.equal(record.operation_id, 'runMinecraftCommands');
  assert.equal(record.http_status, 200);
  assert.equal(record.result_count, expectedResultCount);
  const records = await readDetailedLogRecords();
  assert.equal(JSON.stringify(records).includes(commandMarker), false, 'Paper detail logs contain raw command input');
}

async function paperCommand(command) {
  await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [`${repositoryRoot}/scripts/dev.mjs`, 'command'], {
      cwd: repositoryRoot,
      stdio: ['pipe', 'inherit', 'inherit'],
      signal: AbortSignal.timeout(30_000),
    });
    child.once('error', reject);
    child.stdin.once('error', reject);
    child.once('exit', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`Managed Paper command exited with status ${code}`));
      }
    });
    child.stdin.end(`${command}\n`);
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

function blockStateType(blockState) {
  const propertiesStart = blockState.indexOf('[');
  return propertiesStart === -1 ? blockState : blockState.slice(0, propertiesStart);
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

function assertCommittedEdit(result, operation) {
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

async function undoRetainedBatch(results) {
  const edits = results.map((result) => result.edit);
  const editIds = edits.map((edit) => edit.editId);
  assert.deepEqual(
    editIdsToUndo
      .slice(-editIds.length)
      .toReversed()
      .map((editId) => editId.toLowerCase()),
    editIds.map((editId) => editId.toLowerCase()),
  );
  const undone = await bridgeRequest('/v1/undo-edits', { world, editIds });
  assert.equal(undone.outcome, 'completed');
  assert.equal(undone.world, world);
  assert.deepEqual(undone.undoneEdits, edits);
  assert.match(undone.undoCallId, uuidV4Pattern);
  assert.ok(!Number.isNaN(Date.parse(undone.undoneAt)));
}

async function undoRetained(result) {
  await undoRetainedBatch([result]);
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
      undo = await bridgeResponse('/v1/undo-edits', { world, editIds: [latest.editId] });
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
let wideFixtureIsForceLoaded = false;
let originalRegionFixture;
let originalStairFixture;
let originalSetFixture;
let originalCopyFixture;
let cleanupFailed = false;
let smokeCompleted = false;
try {
  const unauthenticated = await fetch(`${baseUrl}/v1/ping`, {
    headers: { 'X-Dirt-Call-Id': randomUUID() },
    signal: AbortSignal.timeout(3_000),
  });
  assert.equal(unauthenticated.status, 401);
  assert.deepEqual((await unauthenticated.json()).error, {
    code: 'unauthorized',
    message: 'A valid bearer token is required',
    details: { reason: 'authentication_failed' },
  });

  const unknownRoute = await fetch(`${baseUrl}/v1/ping/extra`, {
    headers: { Authorization: `Bearer ${token}`, 'X-Dirt-Call-Id': randomUUID() },
    signal: AbortSignal.timeout(3_000),
  });
  assert.equal(unknownRoute.status, 404);
  assert.deepEqual((await unknownRoute.json()).error, {
    code: 'route_not_found',
    message: 'No bridge operation matches this path',
    details: { reason: 'route_not_found' },
  });

  const pingCallId = randomUUID();
  assert.deepEqual(await bridgeGet('/v1/ping', pingCallId), { status: 'ok' });
  await assertDetailedPingLog(pingCallId);
  assert.deepEqual(await bridgeGet('/v1/capabilities'), { operations: bridgeOperations });
  const serverStatus = await bridgeRequest('/v1/server-status', {
    includePlayers: true,
    includeWorlds: true,
    includeConfiguration: true,
  });
  assert.deepEqual(Object.keys(serverStatus).toSorted(), [
    'builds',
    'configuration',
    'performance',
    'players',
    'worlds',
  ]);
  assert.equal(serverStatus.builds.minecraft, '26.2');
  assert.match(serverStatus.builds.paper, /^26\.2-121-/u);
  assert.equal(serverStatus.builds.dirtPlugin, '0.1.0-SNAPSHOT');
  assert.match(serverStatus.builds.fawe, /^2\.15\.4(?:[+.-]|$)/u);
  assert.ok(serverStatus.performance.tpsOneMinute >= 0);
  assert.equal(serverStatus.players.online, serverStatus.players.entries.length);
  assert.ok(serverStatus.worlds.some((entry) => entry.name === world));
  assert.deepEqual(serverStatus.configuration.limits, {
    maxRegionVolume: 1_048_576,
    maxEditTouchedChunks: 512,
    maxInspectionTouchedChunks: 128,
    maxPerspectiveTouchedChunks: 256,
    maxBlockStatePatterns: 64,
    maxPaletteEntries: 256,
    maxChangedBlocks: 262_144,
    maxInspectionVolume: 262_144,
    maxPerspectiveRayDistanceBudget: 131_072,
    maxInspectionResultLimit: 4_096,
    maxPerspectiveRays: 2_048,
    maxCommandsPerRequest: 10,
    maxCommandFeedbackCharacters: 8_192,
  });
  assert.deepEqual(serverStatus.configuration.editHistory, {
    maxEntriesPerWorld: 50,
    maxEntriesTotal: 200,
    maxRetainedChangedBlocks: 2_621_440,
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
    minimumRequired: serverStatus.configuration.limits.maxInspectionTouchedChunks + 1,
    maximum: serverStatus.configuration.limits.maxInspectionTouchedChunks,
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
    (state) => state !== blockStateType(firstOriginalState),
  );
  const commandFirstState = commandStates[0];
  const commandSecondState = commandStates[1];
  assert.ok(commandFirstState);
  assert.ok(commandSecondState);

  const oversizedCommandBatch = await bridgeResponse('/v1/run-minecraft-commands', {
    commands: [
      `setblock ${setMin.x} ${setMin.y} ${setMin.z} ${commandFirstState} replace`,
      ...Array.from({ length: serverStatus.configuration.limits.maxCommandsPerRequest }, () => 'time query gametime'),
    ],
  });
  assert.equal(oversizedCommandBatch.status, 400);
  assert.deepEqual(oversizedCommandBatch.body.error.details, {
    reason: 'too_many_items',
    fields: ['commands'],
    maximum: serverStatus.configuration.limits.maxCommandsPerRequest,
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
  assert.ok(versionFeedback.includes(serverStatus.builds.dirtPlugin));
  assert.ok(timeFeedback.length > 0);
  assert.ok(commandRun.results.every(({ message, rawMessage }) => message === null && rawMessage === null));
  const afterOrderedCommands = await bridgeRequest('/v1/get-blocks', {
    world,
    min: setMin,
    max: setMin,
  });
  const canonicalCommandSecondState = expandStructure(afterOrderedCommands)[0].blockState;
  assert.equal(blockStateType(canonicalCommandSecondState), commandSecondState);

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
  assert.equal(expandStructure(afterMissingCommand)[0].blockState, canonicalCommandSecondState);

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
  assert.equal(expandStructure(afterFailedCommand)[0].blockState, canonicalCommandSecondState);

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
  const occupiedFixtureTypes = new Set(
    [firstOriginalState, secondOriginalState, firstCopyState, secondCopyState].map(blockStateType),
  );
  const setStates = distinctStates.filter((state) => !occupiedFixtureTypes.has(state));
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
    label: 'Preview an empty smoke edit',
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
    seed: setSeed,
    outcome: 'preview',
    edit: null,
    blockCount: 0,
    changedBlockCount: 0,
    unchangedBlockCount: 0,
  });
  await assertEditHistory([]);

  const setPreview = await bridgeRequest('/v1/set-blocks', {
    world,
    label: 'Preview smoke palette blocks',
    origin: setMin,
    palettes: setPalettes,
    placements: [],
    runs: [[0, 0, 0, 0, 1, 0, 0]],
    seed: setSeed,
    dryRun: true,
  });
  assert.equal(setPreview.world, world);
  assert.equal(setPreview.seed, setSeed);
  assert.equal(setPreview.outcome, 'preview');
  assert.equal(setPreview.edit, null);
  assert.equal(setPreview.blockCount, 2);
  assert.equal(setPreview.changedBlockCount, 2);
  assert.equal(setPreview.unchangedBlockCount, 2 - setPreview.changedBlockCount);
  await assertEditHistory([]);

  const cappedSet = await bridgeResponse('/v1/set-blocks', {
    world,
    label: 'Reject an oversized smoke edit',
    origin: setMin,
    palettes: setPalettes,
    placements: [],
    runs: [[0, 0, 0, 0, 1, 0, 0]],
    seed: setSeed,
    maxChangedBlocks: 1,
  });
  assert.equal(cappedSet.status, 413);
  assert.equal(cappedSet.body.error.code, 'change_limit_exceeded');
  assert.deepEqual(cappedSet.body.error.details, { maximum: 1 });
  await assertEditHistory([]);

  const duplicateSet = await bridgeResponse('/v1/set-blocks', {
    world,
    label: 'Reject overlapping smoke placements',
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
    label: 'Reject an invalid smoke block state',
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
    label: 'Set smoke palette blocks',
    origin: setMin,
    palettes: setPalettes,
    placements: [],
    runs: [[0, 0, 0, 0, 1, 0, 0]],
    seed: setSeed,
  });
  assert.equal(setResponse.status, 200);
  const setResult = setResponse.body;
  assertCommittedEdit(setResult, 'set_blocks');
  assert.equal(setResult.edit.callId, setResponse.callId);
  assert.equal(setResult.world, world);
  assert.deepEqual(setResult.bounds, { min: setMin, max: setMax });
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
  const firstCanonicalSetState = afterSetStates.get('5,0,0');
  const secondCanonicalSetState = afterSetStates.get('6,0,0');
  assert.ok(firstCanonicalSetState);
  assert.ok(secondCanonicalSetState);
  assert.ok(setStates.includes(blockStateType(firstCanonicalSetState)));
  assert.ok(setStates.includes(blockStateType(secondCanonicalSetState)));

  const copyResponse = await bridgeResponse('/v1/set-blocks', {
    ...afterSet,
    label: 'Copy the smoke palette structure',
    origin: copyMin,
  });
  assert.equal(copyResponse.status, 200);
  const copyResult = copyResponse.body;
  assertCommittedEdit(copyResult, 'set_blocks');
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
  await undoRetainedBatch([copyResult, setResult]);
  await assertEditHistory([]);
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

  const consumedSetUndo = await bridgeResponse('/v1/undo-edits', {
    world,
    editIds: [setResult.edit.editId],
  });
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
    originalStates.length === 1 && blockStateType(originalStates[0]) === 'minecraft:barrier'
      ? 'minecraft:amethyst_block'
      : 'minecraft:barrier';
  const regionSetInput = {
    world,
    label: 'Set the smoke inspection region',
    origin: min,
    palettes: [[{ blockState: regionBlockState }]],
    placements: [],
    runs: [[0, 0, 0, 0, max.x - min.x, max.y - min.y, max.z - min.z]],
  };

  const regionPreview = await bridgeRequest('/v1/set-blocks', { ...regionSetInput, dryRun: true });
  assert.equal(regionPreview.outcome, 'preview');
  assert.equal(regionPreview.edit, null);
  assert.equal(regionPreview.blockCount, original.volume);
  assert.ok(regionPreview.changedBlockCount > 0, 'Smoke destination must change at least one block');
  await assertEditHistory([]);

  const regionSet = await bridgeRequest('/v1/set-blocks', {
    ...regionSetInput,
    seed: regionPreview.seed,
  });
  assertCommittedEdit(regionSet, 'set_blocks');
  assert.equal(regionSet.seed, regionPreview.seed);
  assert.equal(regionSet.changedBlockCount, regionPreview.changedBlockCount);
  await assertEditHistory([regionSet.edit]);

  const afterRegionSet = await bridgeRequest('/v1/count-region-block-states', region);
  const regionStateEntries = Object.entries(afterRegionSet.blockStateCounts);
  assert.equal(regionStateEntries.length, 1);
  const [[regionState, regionBlockCount]] = regionStateEntries;
  assert.equal(blockStateType(regionState), regionBlockState);
  assert.equal(regionBlockCount, regionSet.blockCount);
  const alreadyMatching = original.blockStateCounts[regionState] ?? 0;
  assert.equal(regionPreview.changedBlockCount, original.volume - alreadyMatching);
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
  assert.deepEqual(view, {
    world,
    origin: { x: 0, y: 0, z: 0 },
    palettes: [[{ blockState: regionState }]],
    placements: [[0, 0, 0, 1]],
    runs: [],
  });

  const replayView = await bridgeRequest('/v1/set-blocks', {
    ...view,
    label: 'Replay orthographic smoke view',
    dryRun: true,
  });
  assert.equal(replayView.outcome, 'preview');
  assert.equal(replayView.blockCount, 1);
  assert.equal(replayView.changedBlockCount, 0);

  const deeperView = await bridgeRequest('/v1/scan-orthographic-view', {
    ...viewRequest,
    depth: 1,
  });
  assert.deepEqual(deeperView, {
    world,
    origin: { x: 0, y: 0, z: 0 },
    palettes: [[{ blockState: regionState }]],
    placements: [[0, 0, 0, 0]],
    runs: [],
  });

  const viewExtension = await bridgeRequest('/v1/set-blocks', {
    world,
    label: 'Extend the orthographic smoke surface',
    origin: { x: 0, y: 2, z: 0 },
    palettes: [[{ blockState: regionState }]],
    placements: [],
    runs: [[0, 0, 0, 0, 0, 0, 1]],
  });
  assertCommittedEdit(viewExtension, 'set_blocks');
  await assertEditHistory([viewExtension.edit, regionSet.edit]);

  const limitedView = await bridgeRequest('/v1/scan-orthographic-view', {
    ...viewRequest,
    origin: { ...viewRequest.origin, y: 1 },
    verticalRadius: 1,
    maxResults: 1,
  });
  assert.deepEqual(limitedView, {
    world,
    origin: { x: 0, y: 0, z: 0 },
    palettes: [[{ blockState: regionState }]],
    placements: [],
    runs: [[0, 0, 0, 1, 0, 2, 1]],
  });
  await undoRetained(viewExtension);
  await assertEditHistory([regionSet.edit]);

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
    label: 'Preview replacing the smoke region',
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
    label: 'Replace the smoke region',
    sourceBlockStatePatterns: [regionState],
    destinationPalette: [{ blockState: replacementDestination }],
    seed: replacePreview.seed,
  });
  assertCommittedEdit(replaced, 'replace_region_blocks');
  assert.equal(replaced.matchedBlockCount, replacePreview.matchedBlockCount);
  assert.equal(replaced.changedBlockCount, replacePreview.changedBlockCount);
  await assertEditHistory([replaced.edit, regionSet.edit]);
  const replacedBlocks = await bridgeRequest('/v1/get-blocks', region);
  const replacementStates = new Set(expandStructure(replacedBlocks).map(({ blockState }) => blockState));
  assert.equal(replacementStates.size, 1);
  const [replacementState] = replacementStates;
  assert.equal(blockStateType(replacementState), replacementDestination);
  assertExactBlocks(replacedBlocks, replacementState);

  const nonLatestUndo = await bridgeResponse('/v1/undo-edits', {
    world,
    editIds: [regionSet.edit.editId],
  });
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

  await paperCommand('forceload add -32 -32 175 175');
  wideFixtureIsForceLoaded = true;
  const originalLargeRegion = await waitForRegion(largeRegion);
  assert.equal(originalLargeRegion.volume, 262_144);
  const originalLargeTypes = new Set(Object.keys(originalLargeRegion.blockStateCounts).map(blockStateType));
  const largeDestination = [
    'minecraft:barrier',
    'minecraft:structure_void',
    'minecraft:light[level=15]',
    'minecraft:jigsaw[orientation=down_east]',
    'minecraft:command_block[conditional=false,facing=down]',
  ].find((blockState) => !originalLargeTypes.has(blockStateType(blockState)));
  assert.ok(largeDestination);
  assert.equal(originalLargeTypes.has(blockStateType(largeDestination)), false);

  const largeEdit = await bridgeRequest('/v1/set-blocks', {
    world,
    label: 'Exercise the 262144 block edit ceiling',
    origin: largeMin,
    palettes: [[{ blockState: largeDestination }]],
    placements: [],
    runs: [[0, 0, 0, 0, 63, 63, 63]],
  });
  assertCommittedEdit(largeEdit, 'set_blocks');
  assert.equal(largeEdit.blockCount, 262_144);
  assert.equal(largeEdit.changedBlockCount, 262_144);
  await assertEditHistory([largeEdit.edit]);

  const afterLargeEdit = await bridgeRequest('/v1/count-region-block-states', largeRegion);
  const largeStateEntries = Object.entries(afterLargeEdit.blockStateCounts);
  assert.equal(largeStateEntries.length, 1);
  const [[canonicalLargeDestination, canonicalLargeCount]] = largeStateEntries;
  assert.equal(blockStateType(canonicalLargeDestination), blockStateType(largeDestination));
  assert.equal(canonicalLargeCount, 262_144);

  const concurrentInspections = await Promise.all(
    Array.from({ length: 4 }, () => bridgeRequest('/v1/count-region-block-states', largeRegion)),
  );
  for (const inspection of concurrentInspections) {
    assert.deepEqual(inspection.blockStateCounts, { [canonicalLargeDestination]: 262_144 });
  }

  const widePerspective = await bridgeRequest('/v1/get-perspective-view', {
    source: {
      type: 'location',
      world,
      cameraPosition: { x: 64.5, y: 319.5, z: 64.5 },
      rotation: { yaw: 0, pitch: 90 },
    },
    width: 15,
    height: 15,
    verticalFieldOfViewDegrees: 170,
    maxDistance: 96,
  });
  assert.ok(widePerspective.checkedChunkCount > 32);
  assert.ok(widePerspective.checkedChunkCount <= serverStatus.configuration.limits.maxPerspectiveTouchedChunks);

  await undoRetained(largeEdit);
  await assertEditHistory([]);
  assert.deepEqual(await bridgeRequest('/v1/count-region-block-states', largeRegion), originalLargeRegion);

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

  const exactStateDestinations = [
    { blockState: 'minecraft:gold_block', weight: 50 },
    { blockState: 'minecraft:diamond_block', weight: 50 },
  ];
  const exactStateDestinationTypes = new Set(exactStateDestinations.map(({ blockState }) => blockState));
  const exactStatePreview = await bridgeRequest('/v1/replace-region-blocks', {
    ...stairRegion,
    label: 'Preview exact-state stair replacement',
    sourceBlockStatePatterns: ['minecraft:dark_oak_stairs', 'minecraft:air'],
    destinationPalette: exactStateDestinations,
    dryRun: true,
  });
  assert.equal(exactStatePreview.matchedBlockCount, 3);
  assert.equal(exactStatePreview.changedBlockCount, 3);

  const exactStateReplacement = await bridgeRequest('/v1/replace-region-blocks', {
    ...stairRegion,
    label: 'Replace exact stair states',
    sourceBlockStatePatterns: ['minecraft:dark_oak_stairs', 'minecraft:air'],
    destinationPalette: exactStateDestinations,
    seed: exactStatePreview.seed,
  });
  assertCommittedEdit(exactStateReplacement, 'replace_region_blocks');
  assert.equal(exactStateReplacement.matchedBlockCount, 3);
  assert.equal(exactStateReplacement.changedBlockCount, 3);
  const exactStateBlocks = await bridgeRequest('/v1/get-blocks', {
    ...stairRegion,
    includeBlockStatePatterns: ['minecraft:gold_block', 'minecraft:diamond_block'],
  });
  assert.equal(expandStructure(exactStateBlocks).length, 3);
  assert.ok(
    expandStructure(exactStateBlocks).every(({ blockState }) =>
      exactStateDestinationTypes.has(blockStateType(blockState)),
    ),
  );
  const firstSeededLayout = sortedBlockKeys(expandStructure(exactStateBlocks));

  await undoRetained(exactStateReplacement);

  const replayedReplacement = await bridgeRequest('/v1/replace-region-blocks', {
    ...stairRegion,
    label: 'Replay exact stair replacement',
    sourceBlockStatePatterns: ['minecraft:dark_oak_stairs', 'minecraft:air'],
    destinationPalette: exactStateDestinations,
    seed: exactStatePreview.seed,
  });
  assertCommittedEdit(replayedReplacement, 'replace_region_blocks');
  assert.equal(replayedReplacement.changedBlockCount, exactStateReplacement.changedBlockCount);
  const replayedBlocks = await bridgeRequest('/v1/get-blocks', {
    ...stairRegion,
    includeBlockStatePatterns: ['minecraft:gold_block', 'minecraft:diamond_block'],
  });
  assert.deepEqual(sortedBlockKeys(expandStructure(replayedBlocks)), firstSeededLayout);
  await undoRetained(replayedReplacement);

  const propertySetInput = {
    world,
    label: 'Change one stair orientation',
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
  assertCommittedEdit(propertySet, 'set_blocks');
  assert.equal(propertySet.changedBlockCount, 1);
  await assertDetailedMutationLog(propertySet, 'setBlocks');
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
  if (wideFixtureIsForceLoaded) {
    try {
      await paperCommand('forceload remove -32 -32 175 175');
    } catch (error) {
      process.stderr.write(`Could not release wide smoke-test chunks: ${error.message}\n`);
      cleanupFailed = true;
    }
  } else if (fixtureIsForceLoaded) {
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
