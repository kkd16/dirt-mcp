import assert from 'node:assert/strict';
import test from 'node:test';
import { ToolFailure } from '../dist/bridge/errors.js';
import { BlockPositionSchema, BoundsSchema, INT32_MAX, NonBlankStringSchema } from '../dist/tools/common.js';
import {
  DestinationPaletteSchema,
  EditRecordSchema,
  FillRegionOutputSchema,
  GetEditHistoryOutputSchema,
  ReplaceRegionBlocksOutputSchema,
  requireMatchingCallId,
  requireMatchingEditIdentity,
  requireMatchingEditOptions,
  requireMatchingFillVolume,
  requireMatchingSetBlockCount,
  requireMatchingUndoIdentity,
  requireReplaceCountsWithinBounds,
  SetBlocksInputSchema,
  SetBlocksOutputSchema,
  SourceBlockStatePatternsSchema,
} from '../dist/tools/editing.js';
import { GetRegionBlocksInputSchema, ScanOrthographicViewInputSchema } from '../dist/tools/inspection.js';
import {
  EditHistoryConfigurationSchema,
  LoggingConfigurationSchema,
  ServerStatusSchema,
} from '../dist/tools/status.js';
import { MCP_TOOL_NAMES, McpToolConfigurationSchema } from '../dist/tools/configuration.js';
import { requireMatchingWorld } from '../dist/tools/response-validation.js';

const region = {
  world: 'world',
  min: { x: 0, y: 0, z: 0 },
  max: { x: 1, y: 1, z: 1 },
};

const isInvalidBridgeResponse = (error: unknown): boolean =>
  error instanceof ToolFailure && error.code === 'bridge_invalid_response';

test('applies inspection defaults and rejects combined pattern amplification', () => {
  assert.deepEqual(GetRegionBlocksInputSchema.parse(region), {
    ...region,
    includeBlockStatePatterns: [],
    excludeBlockStatePatterns: [],
  });
  assert.equal(
    GetRegionBlocksInputSchema.safeParse({
      ...region,
      includeBlockStatePatterns: Array.from({ length: 33 }, (_, index) => `minecraft:stone[a=${index}]`),
      excludeBlockStatePatterns: Array.from({ length: 32 }, (_, index) => `minecraft:dirt[a=${index}]`),
    }).success,
    false,
  );
  assert.deepEqual(
    ScanOrthographicViewInputSchema.parse({
      world: 'world',
      origin: { x: 0, y: 0, z: 0 },
      direction: 'north',
      horizontalRadius: 0,
      verticalRadius: 0,
      maxDistance: 1,
    }),
    {
      world: 'world',
      origin: { x: 0, y: 0, z: 0 },
      direction: 'north',
      horizontalRadius: 0,
      verticalRadius: 0,
      maxDistance: 1,
      depth: 0,
      format: 'blocks',
    },
  );
});

test('validates distinct source patterns and complete destination weights', () => {
  assert.equal(SourceBlockStatePatternsSchema.safeParse(['minecraft:stone', 'minecraft:stone']).success, false);
  assert.equal(SourceBlockStatePatternsSchema.safeParse(['minecraft:stone', 'minecraft:dirt']).success, true);

  assert.equal(
    DestinationPaletteSchema.safeParse([
      { blockState: 'minecraft:stone', weight: 50 },
      { blockState: 'minecraft:dirt' },
    ]).success,
    false,
  );
  assert.equal(
    DestinationPaletteSchema.safeParse([
      { blockState: 'minecraft:stone', weight: 40 },
      { blockState: 'minecraft:dirt', weight: 50 },
    ]).success,
    false,
  );
  assert.equal(
    DestinationPaletteSchema.safeParse([
      { blockState: 'minecraft:stone', weight: 50 },
      { blockState: 'minecraft:stone', weight: 50 },
    ]).success,
    false,
  );
  assert.equal(
    DestinationPaletteSchema.safeParse([
      { blockState: 'minecraft:stone', weight: 50 },
      { blockState: 'minecraft:dirt', weight: 50 },
    ]).success,
    true,
  );
  assert.equal(
    DestinationPaletteSchema.safeParse([{ blockState: 'minecraft:stone' }, { blockState: 'minecraft:dirt' }]).success,
    true,
  );
});

test('keeps common Minecraft wire values strict and bounded', () => {
  assert.equal(NonBlankStringSchema.safeParse('   ').success, false);
  assert.equal(BlockPositionSchema.safeParse({ x: 0, y: 0, z: 0, extra: true }).success, false);
  assert.equal(BlockPositionSchema.safeParse({ x: 2_147_483_648, y: 0, z: 0 }).success, false);
  assert.equal(BlockPositionSchema.safeParse({ x: -2_147_483_648, y: 0, z: 2_147_483_647 }).success, true);
  assert.equal(BoundsSchema.safeParse({ min: { x: 0, y: 0, z: 0 }, max: { x: -1, y: 0, z: 0 } }).success, false);
});

test('validates bounded edit-history configuration relationships', () => {
  const history = {
    maxEntriesPerWorld: 20,
    maxEntriesTotal: 100,
    maxRetainedChangedBlocks: 1_310_720,
  };
  assert.equal(EditHistoryConfigurationSchema.safeParse(history).success, true);
  assert.equal(
    EditHistoryConfigurationSchema.safeParse({
      maxEntriesPerWorld: 2,
      maxEntriesTotal: 1,
      maxRetainedChangedBlocks: 1,
    }).success,
    false,
  );
  for (const field of ['maxEntriesPerWorld', 'maxEntriesTotal', 'maxRetainedChangedBlocks'] as const) {
    assert.equal(EditHistoryConfigurationSchema.safeParse({ ...history, [field]: INT32_MAX + 1 }).success, false);
  }
});

test('validates bounded server limits and their relationships', () => {
  const tools = Object.fromEntries(MCP_TOOL_NAMES.map((name) => [name, true]));
  const status = {
    builds: { minecraft: '26.2', paper: '26.2-112', dirtMcp: 'test', fawe: '2.15.4' },
    performance: { tpsOneMinute: 20, averageTickTimeMillis: 1 },
    players: { online: 0, maximum: 20, entries: [] },
    worlds: [],
    limits: {
      maxRequestBytes: 1_048_576,
      maxRegionVolume: 100,
      maxTouchedChunks: 10,
      maxInspectionTouchedChunks: 5,
      maxBlockStatePatterns: 64,
      maxChangedBlocks: 50,
      maxInspectionVolume: 80,
      defaultInspectionResultLimit: 10,
      maxInspectionResultLimit: 20,
    },
    editHistory: { maxEntriesPerWorld: 1, maxEntriesTotal: 1, maxRetainedChangedBlocks: 50 },
    defaults: { regionBlocksIncludeAir: false, regionBlocksFormat: 'blocks', editDryRun: false },
    logging: { consoleLevel: 'info', detailFileMaxBytes: 1, detailFileRetainedFiles: 2 },
    tools,
  };
  assert.equal(ServerStatusSchema.safeParse(status).success, true);
  assert.equal(
    ServerStatusSchema.safeParse({
      ...status,
      limits: { ...status.limits, maxRequestBytes: INT32_MAX - 1 },
    }).success,
    true,
  );
  for (const field of Object.keys(status.limits) as (keyof typeof status.limits)[]) {
    assert.equal(ServerStatusSchema.safeParse({ ...status, limits: { ...status.limits, [field]: 0 } }).success, false);
    assert.equal(
      ServerStatusSchema.safeParse({ ...status, limits: { ...status.limits, [field]: INT32_MAX + 1 } }).success,
      false,
    );
  }
  const invalidLimits = [
    { maxRequestBytes: INT32_MAX },
    { maxInspectionTouchedChunks: 11 },
    { maxBlockStatePatterns: 65 },
    { maxChangedBlocks: 101 },
    { maxInspectionVolume: 101 },
    { defaultInspectionResultLimit: 21 },
    { maxInspectionResultLimit: 81 },
  ];
  for (const limits of invalidLimits) {
    assert.equal(ServerStatusSchema.safeParse({ ...status, limits: { ...status.limits, ...limits } }).success, false);
  }
  assert.equal(
    ServerStatusSchema.safeParse({
      ...status,
      editHistory: { ...status.editHistory, maxRetainedChangedBlocks: 49 },
    }).success,
    false,
  );
  assert.equal(McpToolConfigurationSchema.safeParse(tools).success, true);
  assert.equal(McpToolConfigurationSchema.safeParse({ ...tools, undo_edit: undefined }).success, false);
  assert.equal(McpToolConfigurationSchema.safeParse({ ...tools, unknown_tool: false }).success, false);
});

test('strictly validates active Paper logging configuration', () => {
  const logging = { consoleLevel: 'info', detailFileMaxBytes: 10_485_760, detailFileRetainedFiles: 5 };
  assert.equal(LoggingConfigurationSchema.safeParse(logging).success, true);
  assert.equal(LoggingConfigurationSchema.safeParse({ ...logging, consoleLevel: 'debug' }).success, false);
  assert.equal(LoggingConfigurationSchema.safeParse({ ...logging, detailFileMaxBytes: 0 }).success, false);
  assert.equal(LoggingConfigurationSchema.safeParse({ ...logging, detailFileMaxBytes: INT32_MAX + 1 }).success, false);
  assert.equal(LoggingConfigurationSchema.safeParse({ ...logging, detailFileRetainedFiles: 101 }).success, false);
  assert.equal(LoggingConfigurationSchema.safeParse({ ...logging, detailFileRetainedFiles: 1 }).success, false);
  assert.equal(LoggingConfigurationSchema.safeParse({ ...logging, unknown: true }).success, false);
});

test('validates weighted set-block palettes and compact placements', () => {
  const input = {
    world: 'world',
    origin: { x: 10, y: 20, z: 30 },
    palettes: [
      [
        { blockState: 'minecraft:stone', weight: 75 },
        { blockState: 'minecraft:andesite', weight: 25 },
      ],
      [{ blockState: 'minecraft:snow_block' }],
    ],
    placements: [
      [0, 0, 0, 0],
      [0, 1, 0, 0],
      [1, 0, 1, 0],
    ],
    seed: 42,
  };

  assert.equal(SetBlocksInputSchema.safeParse(input).success, true);
  assert.equal(
    SetBlocksInputSchema.safeParse({
      ...input,
      placements: [['0', '0', '0', '0']],
    }).success,
    false,
  );
  assert.equal(
    SetBlocksInputSchema.safeParse({
      ...input,
      placements: [[0, 0, 0]],
    }).success,
    false,
  );
  assert.equal(SetBlocksInputSchema.safeParse({ ...input, unknownProperty: true }).success, false);
  assert.equal(
    SetBlocksInputSchema.safeParse({
      ...input,
      placements: [[2, 0, 0, 0]],
    }).success,
    false,
  );
  assert.equal(
    SetBlocksInputSchema.safeParse({
      ...input,
      placements: [
        [0, 0, 0, 0],
        [1, 0, 0, 0],
      ],
    }).success,
    false,
  );
  assert.equal(
    SetBlocksInputSchema.safeParse({
      ...input,
      origin: { x: 2_147_483_647, y: 20, z: 30 },
      placements: [[0, 1, 0, 0]],
    }).success,
    false,
  );
  assert.equal(
    SetBlocksInputSchema.safeParse({
      ...input,
      palettes: [[{ blockState: 'minecraft:stone', weight: 60 }, { blockState: 'minecraft:dirt' }]],
    }).success,
    false,
  );
  assert.equal(
    SetBlocksInputSchema.safeParse({
      ...input,
      palettes: [
        [
          { blockState: 'minecraft:stone', weight: 60 },
          { blockState: 'minecraft:dirt', weight: 30 },
        ],
      ],
    }).success,
    false,
  );
});

test('validates retained edit metadata and edit-result outcome invariants', () => {
  const edit = {
    editId: '11111111-1111-4111-8111-111111111111',
    callId: '22222222-2222-4222-8222-222222222222',
    operation: 'replace_region_blocks',
    world: 'world',
    worldId: '33333333-3333-4333-8333-333333333333',
    bounds: { min: { x: 0, y: 0, z: 0 }, max: { x: 1, y: 1, z: 1 } },
    changedBlockCount: 1,
    completedAt: '2026-08-19T12:34:56Z',
    status: 'committed',
  };
  assert.equal(EditRecordSchema.safeParse(edit).success, true);
  assert.equal(EditRecordSchema.safeParse({ ...edit, callId: 'not-a-uuid' }).success, false);
  assert.equal(EditRecordSchema.safeParse({ ...edit, world: '   ' }).success, false);
  assert.equal(EditRecordSchema.safeParse({ ...edit, changedBlockCount: 0 }).success, false);
  assert.equal(EditRecordSchema.safeParse({ ...edit, completedAt: 'yesterday' }).success, false);

  const result = {
    world: 'world',
    bounds: edit.bounds,
    sourceBlockStatePatterns: ['minecraft:stone'],
    destinationPalette: [{ blockState: 'minecraft:dirt' }],
    seed: 42,
    matchedBlockCount: 1,
    changedBlockCount: 1,
  };
  assert.equal(ReplaceRegionBlocksOutputSchema.safeParse({ ...result, outcome: 'preview', edit: null }).success, true);
  assert.equal(ReplaceRegionBlocksOutputSchema.safeParse({ ...result, outcome: 'committed', edit }).success, true);
  assert.equal(
    ReplaceRegionBlocksOutputSchema.safeParse({ ...result, outcome: 'committed', edit: null }).success,
    false,
  );
  assert.equal(ReplaceRegionBlocksOutputSchema.safeParse({ ...result, outcome: 'preview', edit }).success, false);
  assert.equal(
    ReplaceRegionBlocksOutputSchema.safeParse({ ...result, outcome: 'no_change', edit: null }).success,
    false,
  );
  assert.equal(
    ReplaceRegionBlocksOutputSchema.safeParse({
      ...result,
      outcome: 'no_change',
      edit: null,
      changedBlockCount: 0,
    }).success,
    true,
  );

  for (const inconsistentEdit of [
    { ...edit, status: 'recovery_required' },
    { ...edit, operation: 'fill_region' },
    { ...edit, world: 'other_world' },
    { ...edit, bounds: { ...edit.bounds, max: { x: 2, y: 1, z: 1 } } },
    { ...edit, changedBlockCount: 2 },
  ]) {
    assert.equal(
      ReplaceRegionBlocksOutputSchema.safeParse({ ...result, outcome: 'committed', edit: inconsistentEdit }).success,
      false,
    );
  }

  assert.equal(
    ReplaceRegionBlocksOutputSchema.safeParse({
      ...result,
      outcome: 'preview',
      edit: null,
      matchedBlockCount: 0,
    }).success,
    false,
  );
  assert.equal(
    FillRegionOutputSchema.safeParse({
      world: 'world',
      bounds: edit.bounds,
      destinationPalette: [{ blockState: 'minecraft:dirt' }],
      seed: 42,
      outcome: 'preview',
      edit: null,
      volume: 1,
      changedBlockCount: 2,
    }).success,
    false,
  );
  assert.equal(
    SetBlocksOutputSchema.safeParse({
      world: 'world',
      bounds: edit.bounds,
      palettes: [[{ blockState: 'minecraft:dirt' }]],
      seed: 42,
      outcome: 'preview',
      edit: null,
      blockCount: 2,
      changedBlockCount: 1,
      unchangedBlockCount: 2,
    }).success,
    false,
  );

  const secondEdit = { ...edit, editId: '44444444-4444-4444-8444-444444444444' };
  assert.equal(GetEditHistoryOutputSchema.safeParse({ world: 'world', edits: [secondEdit, edit] }).success, true);
  assert.equal(GetEditHistoryOutputSchema.safeParse({ world: 'world', edits: [edit, edit] }).success, false);
  assert.equal(
    GetEditHistoryOutputSchema.safeParse({
      world: 'world',
      edits: [edit, { ...edit, editId: edit.editId.toUpperCase() }],
    }).success,
    false,
  );
  assert.equal(
    GetEditHistoryOutputSchema.safeParse({ world: 'world', edits: [{ ...edit, world: 'other_world' }] }).success,
    false,
  );
  assert.equal(
    GetEditHistoryOutputSchema.safeParse({
      world: 'world',
      edits: [edit, { ...secondEdit, worldId: '55555555-5555-4555-8555-555555555555' }],
    }).success,
    false,
  );
  assert.equal(
    GetEditHistoryOutputSchema.safeParse({
      world: 'world',
      edits: [edit, { ...secondEdit, worldId: edit.worldId.toUpperCase() }],
    }).success,
    true,
  );
});

test('rejects successful edit and undo responses that do not match their requests', () => {
  const expectedCallId = '11111111-1111-4111-8111-111111111111';
  const actualCallId = '22222222-2222-4222-8222-222222222222';
  const editId = '33333333-3333-4333-8333-333333333333';

  assert.doesNotThrow(() => requireMatchingCallId(expectedCallId, expectedCallId, editId));
  assert.doesNotThrow(() => requireMatchingCallId(expectedCallId, expectedCallId.toUpperCase(), editId));
  assert.throws(
    () => requireMatchingCallId(expectedCallId, actualCallId, editId),
    (error: unknown) =>
      error instanceof ToolFailure &&
      error.code === 'bridge_invalid_response' &&
      error.editId === editId &&
      error.message === 'Paper bridge response call ID did not match the request.',
  );

  const edit = EditRecordSchema.parse({
    editId,
    callId: expectedCallId,
    operation: 'set_blocks',
    world: 'world',
    worldId: '44444444-4444-4444-8444-444444444444',
    bounds: { min: { x: 0, y: 0, z: 0 }, max: { x: 0, y: 0, z: 0 } },
    changedBlockCount: 1,
    completedAt: '2026-08-19T12:34:56Z',
    status: 'committed',
  });
  const editResult = {
    world: 'world',
    bounds: edit.bounds,
    changedBlockCount: 1,
    outcome: 'committed' as const,
    edit,
  };
  assert.doesNotThrow(() => requireMatchingEditIdentity('world', edit.bounds, expectedCallId, editResult));
  for (const [world, bounds] of [
    ['other_world', edit.bounds],
    ['world', { ...edit.bounds, max: { x: 1, y: 0, z: 0 } }],
  ] as const) {
    assert.throws(
      () => requireMatchingEditIdentity(world, bounds, expectedCallId, editResult),
      (error: unknown) =>
        error instanceof ToolFailure && error.code === 'bridge_invalid_response' && error.editId === editId,
    );
  }
  assert.doesNotThrow(() => requireMatchingWorld('world', 'world'));
  assert.throws(
    () => requireMatchingWorld('world', 'other_world'),
    (error: unknown) =>
      error instanceof ToolFailure && error.code === 'bridge_invalid_response' && error.editId === undefined,
  );
  assert.doesNotThrow(() => requireMatchingUndoIdentity('world', editId, edit));
  assert.doesNotThrow(() => requireMatchingUndoIdentity('world', editId.toUpperCase(), edit));
  for (const [world, requestedEditId] of [
    ['other_world', editId],
    ['world', '55555555-5555-4555-8555-555555555555'],
  ] as const) {
    assert.throws(
      () => requireMatchingUndoIdentity(world, requestedEditId, edit),
      (error: unknown) =>
        error instanceof ToolFailure && error.code === 'bridge_invalid_response' && error.editId === editId,
    );
  }
});

test('correlates edit options and counts with the originating request', () => {
  const bounds = { min: { x: 0, y: 0, z: 0 }, max: { x: 1, y: 1, z: 1 } };
  const result = {
    world: 'world',
    bounds,
    changedBlockCount: 0,
    outcome: 'preview' as const,
    edit: null,
    seed: 42,
  };
  assert.doesNotThrow(() => requireMatchingEditOptions(42, true, result));
  assert.doesNotThrow(() => requireMatchingEditOptions(undefined, undefined, result));
  assert.throws(() => requireMatchingEditOptions(41, true, result), isInvalidBridgeResponse);
  assert.throws(() => requireMatchingEditOptions(42, false, result), isInvalidBridgeResponse);
  assert.throws(
    () => requireMatchingEditOptions(42, true, { ...result, outcome: 'no_change' }),
    isInvalidBridgeResponse,
  );
  assert.doesNotThrow(() => requireMatchingEditOptions(42, false, { ...result, outcome: 'no_change' }));

  assert.doesNotThrow(() => requireReplaceCountsWithinBounds(bounds, { ...result, matchedBlockCount: 8 }));
  assert.throws(
    () => requireReplaceCountsWithinBounds(bounds, { ...result, matchedBlockCount: 9 }),
    isInvalidBridgeResponse,
  );
  assert.doesNotThrow(() =>
    requireReplaceCountsWithinBounds(
      {
        min: { x: -2_147_483_648, y: -2_147_483_648, z: -2_147_483_648 },
        max: { x: 2_147_483_647, y: 2_147_483_647, z: 2_147_483_647 },
      },
      { ...result, matchedBlockCount: Number.MAX_SAFE_INTEGER },
    ),
  );

  assert.doesNotThrow(() => requireMatchingFillVolume(bounds, { ...result, volume: 8 }));
  assert.throws(() => requireMatchingFillVolume(bounds, { ...result, volume: 7 }), isInvalidBridgeResponse);

  assert.doesNotThrow(() => requireMatchingSetBlockCount(2, { ...result, blockCount: 2 }));
  assert.throws(() => requireMatchingSetBlockCount(2, { ...result, blockCount: 3 }), isInvalidBridgeResponse);
});
