import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ExactBlockStructureOutputSchema,
  GetBlocksInputSchema,
  ScanOrthographicViewInputSchema,
  countRegionBlockStatesBridgeOutputSchema,
  getBlocksBridgeOutputSchema,
  scanOrthographicViewBridgeOutputSchema,
} from '../dist/tools/inspection.js';

test('inspection inputs materialize MCP-owned defaults', () => {
  const getBlocks = GetBlocksInputSchema.parse({
    world: 'world',
    min: { x: 0, y: 0, z: 0 },
    max: { x: 1, y: 1, z: 1 },
  });
  assert.deepEqual(getBlocks, {
    world: 'world',
    min: { x: 0, y: 0, z: 0 },
    max: { x: 1, y: 1, z: 1 },
    includeBlockStatePatterns: [],
    excludeBlockStatePatterns: [],
    includeAir: false,
    maxResults: 1_024,
  });

  const scan = ScanOrthographicViewInputSchema.parse({
    world: 'world',
    origin: { x: 0, y: 64, z: 0 },
    direction: 'north',
    horizontalRadius: 2,
    verticalRadius: 1,
    maxDistance: 16,
  });
  assert.equal(scan.depth, 0);
  assert.equal(scan.maxResults, 1_024);
});

test('get-blocks rejects duplicate and over-budget filter lists', () => {
  const base = {
    world: 'world',
    min: { x: 0, y: 0, z: 0 },
    max: { x: 1, y: 1, z: 1 },
  };
  assert.equal(
    GetBlocksInputSchema.safeParse({ ...base, includeBlockStatePatterns: ['stone', 'stone'] }).success,
    false,
  );
  assert.equal(
    GetBlocksInputSchema.safeParse({
      ...base,
      includeBlockStatePatterns: Array.from({ length: 33 }, (_, index) => `stone${index}`),
      excludeBlockStatePatterns: Array.from({ length: 32 }, (_, index) => `dirt${index}`),
    }).success,
    false,
  );
});

test('exact structures enforce their cheap wire invariants without expanding geometry', () => {
  const structurallyValid = {
    world: 'world',
    origin: { x: 0, y: 64, z: 0 },
    palettes: [[{ blockState: 'minecraft:stone' }]],
    placements: [[0, 0, 0, 0]],
    runs: [[0, 1, 0, 0, 2, 0, 0]],
  };
  assert.equal(ExactBlockStructureOutputSchema.safeParse(structurallyValid).success, true);
  assert.equal(
    ExactBlockStructureOutputSchema.safeParse({ ...structurallyValid, placements: [[0, 0, 0]] }).success,
    false,
  );
  assert.equal(
    ExactBlockStructureOutputSchema.safeParse({ ...structurallyValid, placements: [[1, 0, 0, 0]] }).success,
    false,
  );
  assert.equal(
    ExactBlockStructureOutputSchema.safeParse({ ...structurallyValid, runs: [[0, 2, 0, 0, 1, 0, 0]] }).success,
    false,
  );
  assert.equal(ExactBlockStructureOutputSchema.safeParse({ ...structurallyValid, palettes: [] }).success, false);
});

test('inspection bridge responses correlate with their request and exact counts', () => {
  const countRequest = {
    world: 'world',
    min: { x: 1, y: 3, z: 4 },
    max: { x: 0, y: 3, z: 4 },
  } as const;
  const count = {
    world: 'world',
    bounds: { min: { x: 0, y: 3, z: 4 }, max: { x: 1, y: 3, z: 4 } },
    dimensions: { x: 2, y: 1, z: 1 },
    volume: 2,
    blockStateCounts: { 'minecraft:stone': 1, 'minecraft:air': 1 },
  };
  const countSchema = countRegionBlockStatesBridgeOutputSchema(countRequest);
  assert.equal(countSchema.safeParse(count).success, true);
  assert.equal(countSchema.safeParse({ ...count, world: 'other' }).success, false);
  assert.equal(countSchema.safeParse({ ...count, volume: 3 }).success, false);
  assert.equal(countSchema.safeParse({ ...count, blockStateCounts: { 'minecraft:stone': 1 } }).success, false);

  const getRequest = {
    world: 'world',
    min: { x: 1, y: 64, z: 2 },
    max: { x: 0, y: 64, z: 2 },
    includeBlockStatePatterns: [],
    excludeBlockStatePatterns: [],
    includeAir: false,
    maxResults: 1,
  } as const;
  const structure = {
    world: 'world',
    origin: { x: 0, y: 64, z: 2 },
    palettes: [[{ blockState: 'minecraft:stone' }]],
    placements: [[0, 0, 0, 0]],
    runs: [],
  };
  const getSchema = getBlocksBridgeOutputSchema(getRequest);
  assert.equal(getSchema.safeParse(structure).success, true);
  assert.equal(getSchema.safeParse({ ...structure, origin: { x: 1, y: 64, z: 2 } }).success, false);
  assert.equal(
    getSchema.safeParse({
      ...structure,
      placements: [
        [0, 0, 0, 0],
        [0, 1, 0, 0],
      ],
    }).success,
    false,
  );

  const scanSchema = scanOrthographicViewBridgeOutputSchema({
    world: 'world',
    origin: { x: 0, y: 64, z: 0 },
    direction: 'north',
    horizontalRadius: 0,
    verticalRadius: 0,
    maxDistance: 16,
    depth: 0,
    maxResults: 1,
  });
  assert.equal(scanSchema.safeParse(structure).success, true);
  assert.equal(scanSchema.safeParse({ ...structure, world: 'other' }).success, false);
});
