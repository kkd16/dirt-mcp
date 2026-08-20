import assert from 'node:assert/strict';
import test from 'node:test';
import { BlockPositionSchema, NonBlankStringSchema } from '../dist/tools/common.js';
import {
  DestinationPaletteSchema,
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
