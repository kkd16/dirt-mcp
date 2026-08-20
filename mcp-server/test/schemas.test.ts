import assert from 'node:assert/strict';
import test from 'node:test';
import { BlockPositionSchema, NonBlankStringSchema } from '../dist/tools/common.js';
import {
  DestinationPaletteSchema,
  EditRecordSchema,
  ReplaceRegionBlocksOutputSchema,
  SetBlocksInputSchema,
  SourceBlockStatePatternsSchema,
} from '../dist/tools/editing.js';
import { GetRegionBlocksInputSchema, ScanOrthographicViewInputSchema } from '../dist/tools/inspection.js';

const region = {
  world: 'world',
  min: { x: 0, y: 0, z: 0 },
  max: { x: 1, y: 1, z: 1 },
};

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
  assert.equal(
    SetBlocksInputSchema.safeParse({
      world: 'world',
      origin: input.origin,
      palette: ['minecraft:stone'],
      placements: [{ paletteIndex: 0, offsets: [[0, 0, 0]] }],
    }).success,
    false,
  );
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
});
