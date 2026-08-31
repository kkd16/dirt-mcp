import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DestinationPaletteSchema,
  EditLabelSchema,
  EditRecordSchema,
  GetEditHistoryOutputSchema,
  ReplaceRegionBlocksInputSchema,
  ReplaceRegionBlocksOutputSchema,
  SetBlocksInputSchema,
  SetBlocksOutputSchema,
  SourceBlockStatePatternsSchema,
  UndoEditsInputSchema,
  UndoEditsOutputSchema,
  getEditHistoryBridgeOutputSchema,
  replaceRegionBlocksBridgeOutputSchema,
  setBlocksBridgeOutputSchema,
  undoEditsBridgeOutputSchema,
} from '../dist/tools/editing.js';
import { MCP_TOOL_OPERATIONS, toolConfigurationFromCapabilities } from '../dist/tools/configuration.js';
import {
  GetServerStatusInputSchema,
  GetServerStatusOutputSchema,
  serverStatusBridgeOutputSchema,
} from '../dist/tools/status.js';

const CALL_ID = '11111111-1111-4111-8111-111111111111';
const EDIT_ID = '22222222-2222-4222-8222-222222222222';
const SECOND_EDIT_ID = '44444444-4444-4444-8444-444444444444';

const edit = {
  editId: EDIT_ID,
  callId: CALL_ID,
  label: 'Build wall',
  operation: 'set_blocks',
  world: 'world',
  worldId: '33333333-3333-4333-8333-333333333333',
  bounds: { min: { x: 0, y: 64, z: 0 }, max: { x: 1, y: 64, z: 0 } },
  changedBlockCount: 2,
  completedAt: '2026-08-23T12:00:00Z',
  status: 'committed',
} as const;

test('capability snapshots map one-to-one to the MCP tool catalog', () => {
  const none = toolConfigurationFromCapabilities({ operations: [] });
  assert.deepEqual(Object.keys(none), Object.keys(MCP_TOOL_OPERATIONS));
  assert.equal(
    Object.values(none).every((enabled) => !enabled),
    true,
  );

  const selected = toolConfigurationFromCapabilities({ operations: ['pingServer', 'setBlocks'] });
  assert.equal(selected.ping_server, true);
  assert.equal(selected.set_blocks, true);
  assert.equal(selected.get_blocks, false);
});

test('status schemas validate defaults, ordering, limits, and requested sections', () => {
  const input = GetServerStatusInputSchema.parse({});
  assert.deepEqual(input, { include: { players: false, worlds: true, configuration: false } });

  const output = {
    builds: { minecraft: '26.2', paper: '26.2-121', dirtPlugin: '0.1.0', fawe: '2.15.4' },
    performance: { tpsOneMinute: 20, averageTickTimeMillis: 4.2 },
    players: null,
    worlds: null,
    configuration: {
      limits: {
        maxRegionVolume: 1_000_000,
        maxEditTouchedChunks: 256,
        maxInspectionTouchedChunks: 256,
        maxPerspectiveTouchedChunks: 64,
        maxBlockStatePatterns: 64,
        maxPaletteEntries: 256,
        maxChangedBlocks: 100_000,
        maxInspectionVolume: 1_000_000,
        maxPerspectiveRayDistanceBudget: 100_000,
        maxInspectionResultLimit: 10_000,
        maxPerspectiveRays: 10_000,
        maxCommandsPerRequest: 100,
        maxCommandFeedbackCharacters: 16_000,
      },
      editHistory: { maxEntriesPerWorld: 20, maxEntriesTotal: 100, maxRetainedChangedBlocks: 1_000_000 },
    },
  };
  assert.equal(GetServerStatusOutputSchema.safeParse(output).success, true);
  const players = {
    online: 2,
    maximum: 20,
    entries: [
      {
        name: 'Alex',
        world: 'world',
        gameMode: 'creative',
        facing: 'north',
        blockPosition: { x: 0, y: 64, z: 0 },
      },
      {
        name: 'builder',
        world: 'world',
        gameMode: 'survival',
        facing: 'south',
        blockPosition: { x: 1, y: 64, z: 0 },
      },
    ],
  } as const;
  assert.equal(GetServerStatusOutputSchema.safeParse({ ...output, players }).success, true);
  assert.equal(GetServerStatusOutputSchema.safeParse({ ...output, players: { ...players, online: 1 } }).success, false);
  assert.equal(
    GetServerStatusOutputSchema.safeParse({ ...output, players: { ...players, entries: players.entries.toReversed() } })
      .success,
    false,
  );
  assert.equal(
    GetServerStatusOutputSchema.safeParse({
      ...output,
      builds: { ...output.builds, unexpected: 'extra' },
    }).success,
    false,
  );
  assert.equal(
    GetServerStatusOutputSchema.safeParse({
      ...output,
      configuration: { ...output.configuration, unexpected: {} },
    }).success,
    false,
  );
  assert.equal(
    GetServerStatusOutputSchema.safeParse({
      ...output,
      configuration: {
        ...output.configuration,
        limits: { ...output.configuration.limits, maxInspectionResultLimit: 1_000_001 },
      },
    }).success,
    false,
  );

  const bridgeSchema = serverStatusBridgeOutputSchema({
    includePlayers: false,
    includeWorlds: true,
    includeConfiguration: false,
  });
  assert.equal(bridgeSchema.safeParse({ ...output, configuration: null, worlds: [] }).success, true);
  assert.equal(bridgeSchema.safeParse({ ...output, configuration: null, worlds: null }).success, false);
  assert.equal(bridgeSchema.safeParse({ ...output, players: { online: 0, maximum: 20, entries: [] } }).success, false);
});

test('edit input schemas validate MCP arguments and materialize dryRun', () => {
  assert.equal(SourceBlockStatePatternsSchema.safeParse(['minecraft:stone', 'minecraft:stone']).success, false);
  assert.equal(
    DestinationPaletteSchema.safeParse([
      { blockState: 'minecraft:stone', weight: 50 },
      { blockState: 'minecraft:dirt', weight: 50 },
    ]).success,
    true,
  );
  assert.equal(
    DestinationPaletteSchema.safeParse([
      { blockState: 'minecraft:stone', weight: 40 },
      { blockState: 'minecraft:dirt', weight: 40 },
    ]).success,
    false,
  );
  assert.equal(EditLabelSchema.safeParse(' Build wall').success, false);
  assert.equal(EditLabelSchema.safeParse('Build\nwall').success, false);

  const replace = ReplaceRegionBlocksInputSchema.parse({
    world: 'world',
    min: { x: 0, y: 0, z: 0 },
    max: { x: 1, y: 1, z: 1 },
    sourceBlockStatePatterns: ['minecraft:stone'],
    destinationPalette: [{ blockState: 'minecraft:dirt' }],
    label: 'Replace stone',
  });
  assert.equal(replace.dryRun, false);
  assert.equal(replace.seed, undefined);
  assert.equal(replace.maxChangedBlocks, undefined);

  const set = SetBlocksInputSchema.parse({
    world: 'world',
    origin: { x: 0, y: 64, z: 0 },
    palettes: [[{ blockState: 'minecraft:stone' }]],
    placements: [[0, 0, 0, 0]],
    runs: [[0, 1, 0, 0, 2, 0, 0]],
    label: 'Place stone',
  });
  assert.equal(set.dryRun, false);
  assert.deepEqual(set.placements, [[0, 0, 0, 0]]);
  assert.deepEqual(set.runs, [[0, 1, 0, 0, 2, 0, 0]]);
  assert.equal(SetBlocksInputSchema.safeParse({ ...set, placements: [[0, 0, 0]] }).success, false);
  assert.equal(SetBlocksInputSchema.safeParse({ ...set, placements: [[1, 0, 0, 0]] }).success, false);
  assert.equal(SetBlocksInputSchema.safeParse({ ...set, palettes: [] }).success, false);
  assert.equal(SetBlocksInputSchema.safeParse({ ...set, palettes: [], placements: [], runs: [] }).success, true);
  assert.equal(
    SetBlocksInputSchema.safeParse({ ...set, placements: [], runs: [[1, 0, 0, 0, 0, 0, 0]] }).success,
    false,
  );
  assert.equal(
    SetBlocksInputSchema.safeParse({ ...set, placements: [], runs: [[0, 1, 0, 0, 0, 0, 0]] }).success,
    false,
  );
  const maximumPalettes = [
    Array.from({ length: 128 }, (_, index) => ({ blockState: `minecraft:test_a_${index}` })),
    Array.from({ length: 128 }, (_, index) => ({ blockState: `minecraft:test_b_${index}` })),
  ];
  assert.equal(SetBlocksInputSchema.safeParse({ ...set, palettes: maximumPalettes }).success, true);
  assert.equal(
    SetBlocksInputSchema.safeParse({
      ...set,
      palettes: [...maximumPalettes, [{ blockState: 'minecraft:one_entry_too_many' }]],
    }).success,
    false,
  );
});

test('edit response schemas enforce outcome-specific invariants', () => {
  assert.equal(EditRecordSchema.safeParse(edit).success, true);
  const replace = {
    world: 'world',
    bounds: edit.bounds,
    seed: 42,
    outcome: 'committed',
    edit: { ...edit, operation: 'replace_region_blocks' },
    matchedBlockCount: 2,
    changedBlockCount: 2,
  };
  assert.equal(ReplaceRegionBlocksOutputSchema.safeParse(replace).success, true);
  assert.equal(ReplaceRegionBlocksOutputSchema.safeParse({ ...replace, matchedBlockCount: 0 }).success, false);

  const set = {
    world: 'world',
    bounds: edit.bounds,
    seed: 42,
    outcome: 'committed',
    edit,
    blockCount: 2,
    changedBlockCount: 2,
    unchangedBlockCount: 0,
  };
  assert.equal(SetBlocksOutputSchema.safeParse(set).success, true);
  assert.equal(SetBlocksOutputSchema.safeParse({ ...set, bounds: null }).success, false);
  assert.equal(SetBlocksOutputSchema.safeParse({ ...set, blockCount: 0 }).success, false);
  assert.equal(
    SetBlocksOutputSchema.safeParse({
      ...set,
      bounds: null,
      outcome: 'no_change',
      edit: null,
      blockCount: 0,
      changedBlockCount: 0,
      unchangedBlockCount: 0,
    }).success,
    true,
  );
  assert.equal(GetEditHistoryOutputSchema.safeParse({ world: 'world', edits: [edit] }).success, true);
  assert.equal(GetEditHistoryOutputSchema.safeParse({ world: 'world', edits: [edit, edit] }).success, false);
});

test('undo advertises only completed results and keeps partial failures internal', () => {
  assert.equal(UndoEditsInputSchema.safeParse({ world: 'world', editIds: [EDIT_ID, EDIT_ID] }).success, false);
  const completed = {
    outcome: 'completed',
    world: 'world',
    undoneEdits: [edit],
    undoCallId: CALL_ID,
    undoneAt: '2026-08-23T12:01:00Z',
  };
  assert.equal(UndoEditsOutputSchema.safeParse(completed).success, true);
  assert.equal(UndoEditsOutputSchema.safeParse({ ...completed, undoneEdits: [edit, edit] }).success, false);
  assert.equal(
    UndoEditsOutputSchema.safeParse({
      outcome: 'partial',
      world: 'world',
      undoneEdits: [],
      undoCallId: CALL_ID,
      failure: { code: 'internal_error', message: 'Stopped', editId: EDIT_ID },
    }).success,
    false,
  );
});

test('edit bridge responses correlate with their request and call identity', () => {
  const replaceRequest = {
    world: 'world',
    label: 'Build wall',
    min: edit.bounds.max,
    max: edit.bounds.min,
    sourceBlockStatePatterns: ['minecraft:stone'],
    destinationPalette: [{ blockState: 'minecraft:dirt' }],
    seed: 42,
    dryRun: false,
    maxChangedBlocks: null,
  } as const;
  const replace = {
    world: 'world',
    bounds: edit.bounds,
    seed: 42,
    outcome: 'committed',
    edit: { ...edit, operation: 'replace_region_blocks' },
    matchedBlockCount: 2,
    changedBlockCount: 2,
  } as const;
  const replaceSchema = replaceRegionBlocksBridgeOutputSchema(replaceRequest, CALL_ID);
  assert.equal(replaceSchema.safeParse(replace).success, true);
  assert.equal(
    replaceRegionBlocksBridgeOutputSchema({ ...replaceRequest, maxChangedBlocks: 1 }, CALL_ID).safeParse(replace)
      .success,
    false,
  );
  assert.equal(replaceSchema.safeParse({ ...replace, world: 'other' }).success, false);
  assert.equal(replaceSchema.safeParse({ ...replace, seed: 43 }).success, false);
  assert.equal(
    replaceSchema.safeParse({ ...replace, outcome: 'preview', edit: null, changedBlockCount: 1 }).success,
    false,
  );
  assert.equal(
    replaceSchema.safeParse({ ...replace, edit: { ...replace.edit, label: 'Different label' } }).success,
    false,
  );
  assert.equal(replaceSchema.safeParse({ ...replace, matchedBlockCount: 3 }).success, false);
  assert.equal(
    replaceSchema.safeParse({
      ...replace,
      edit: { ...replace.edit, callId: '55555555-5555-4555-8555-555555555555' },
    }).success,
    false,
  );

  const setRequest = {
    world: 'world',
    label: 'Build wall',
    origin: { x: 0, y: 64, z: 0 },
    palettes: [[{ blockState: 'minecraft:stone' }]],
    placements: [
      [0, 0, 0, 0],
      [0, 1, 0, 0],
    ],
    runs: [],
    seed: 42,
    dryRun: false,
    maxChangedBlocks: null,
  } as const;
  const set = {
    world: 'world',
    bounds: edit.bounds,
    seed: 42,
    outcome: 'committed',
    edit,
    blockCount: 2,
    changedBlockCount: 2,
    unchangedBlockCount: 0,
  } as const;
  const setSchema = setBlocksBridgeOutputSchema(setRequest, CALL_ID);
  assert.equal(setSchema.safeParse(set).success, true);
  assert.equal(setSchema.safeParse({ ...set, unchangedBlockCount: 1 }).success, false);
  assert.equal(
    setSchema.safeParse({ ...set, blockCount: 3, changedBlockCount: 2, unchangedBlockCount: 1 }).success,
    false,
  );
  const shiftedBounds = { min: { x: 1, y: 64, z: 0 }, max: { x: 2, y: 64, z: 0 } } as const;
  assert.equal(
    setSchema.safeParse({ ...set, bounds: shiftedBounds, edit: { ...edit, bounds: shiftedBounds } }).success,
    false,
  );
  assert.equal(
    setSchema.safeParse({
      ...set,
      edit: { ...edit, bounds: { min: edit.bounds.min, max: edit.bounds.min } },
    }).success,
    false,
  );

  const runSchema = setBlocksBridgeOutputSchema(
    {
      ...setRequest,
      placements: [],
      runs: [[0, -1, 0, 0, 1, 1, 0]],
    },
    CALL_ID,
  );
  const runResult = {
    ...set,
    outcome: 'no_change',
    edit: null,
    bounds: { min: { x: -1, y: 64, z: 0 }, max: { x: 1, y: 65, z: 0 } },
    blockCount: 6,
    changedBlockCount: 0,
    unchangedBlockCount: 6,
  } as const;
  assert.equal(runSchema.safeParse(runResult).success, true);
  assert.equal(runSchema.safeParse({ ...runResult, blockCount: 5, unchangedBlockCount: 5 }).success, false);
});

test('history and undo bridge responses preserve world, call, and exact prefix identity', () => {
  const secondEdit = { ...edit, editId: SECOND_EDIT_ID };
  const historySchema = getEditHistoryBridgeOutputSchema({ world: 'world' });
  assert.equal(historySchema.safeParse({ world: 'world', edits: [edit] }).success, true);
  assert.equal(historySchema.safeParse({ world: 'other', edits: [edit] }).success, false);
  assert.equal(historySchema.safeParse({ world: 'world', edits: [{ ...edit, world: 'other' }] }).success, false);

  const request = { world: 'world', editIds: [EDIT_ID, SECOND_EDIT_ID] } as const;
  const completed = {
    outcome: 'completed',
    world: 'world',
    undoneEdits: [edit, secondEdit],
    undoCallId: CALL_ID,
    undoneAt: '2026-08-23T12:01:00Z',
  } as const;
  const undoSchema = undoEditsBridgeOutputSchema(request, CALL_ID);
  assert.equal(undoSchema.safeParse(completed).success, true);
  assert.equal(
    undoSchema.safeParse({ ...completed, undoCallId: '55555555-5555-4555-8555-555555555555' }).success,
    false,
  );
  assert.equal(undoSchema.safeParse({ ...completed, undoneEdits: [secondEdit, edit] }).success, false);
  assert.equal(undoSchema.safeParse({ ...completed, undoneEdits: [edit] }).success, false);

  const partial = {
    outcome: 'partial',
    world: 'world',
    undoneEdits: [edit],
    undoCallId: CALL_ID,
    failure: { code: 'internal_error', message: 'Stopped safely.', editId: SECOND_EDIT_ID },
  } as const;
  assert.equal(undoSchema.safeParse(partial).success, true);
  assert.equal(undoSchema.safeParse({ ...partial, failure: { ...partial.failure, editId: EDIT_ID } }).success, false);
  assert.equal(undoSchema.safeParse({ ...partial, undoneEdits: [edit, secondEdit] }).success, false);
});
