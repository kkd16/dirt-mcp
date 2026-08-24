import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ExactBlockStructureOutputSchema,
  GetBlocksInputSchema,
  ScanOrthographicViewInputSchema,
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

test('exact structures are decoded structurally without recomputing Paper geometry', () => {
  const structurallyValid = {
    world: 'world',
    origin: { x: 0, y: 64, z: 0 },
    palettes: [[{ blockState: 'minecraft:stone' }]],
    placements: [[0, 0, 0, 0]],
    runs: [[0, 2, 0, 0, 1, 0, 0]],
  };
  assert.equal(ExactBlockStructureOutputSchema.safeParse(structurallyValid).success, true);
  assert.equal(
    ExactBlockStructureOutputSchema.safeParse({ ...structurallyValid, placements: [[0, 0, 0]] }).success,
    false,
  );
});
