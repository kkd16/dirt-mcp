import assert from 'node:assert/strict';
import test from 'node:test';
import type { ScanOrthographicViewBlocksOutput } from '../dist/tools/inspection.js';
import { compactView } from '../dist/tools/view-grid.js';

const requested = { horizontalRadius: 1, verticalRadius: 1, maxDistance: 3, depth: 1 };

function viewFixture(): ScanOrthographicViewBlocksOutput {
  return {
    world: 'world',
    origin: { x: 0, y: 0, z: 0 },
    direction: 'north',
    basis: {
      forward: { x: 0, y: 0, z: -1 },
      horizontal: { x: 1, y: 0, z: 0 },
      vertical: { x: 0, y: 1, z: 0 },
    },
    viewport: requested,
    bounds: { min: { x: -1, y: -1, z: -3 }, max: { x: 1, y: 1, z: -1 } },
    scannedVolume: 27,
    visibleBlockCount: 3,
    format: 'blocks',
    blocks: [
      {
        position: { x: -1, y: 1, z: -1 },
        offset: { horizontal: -1, vertical: 1, distance: 1 },
        blockState: 'minecraft:stone',
      },
      {
        position: { x: 0, y: 0, z: -2 },
        offset: { horizontal: 0, vertical: 0, distance: 2 },
        blockState: 'minecraft:dirt',
      },
      {
        position: { x: 1, y: -1, z: -3 },
        offset: { horizontal: 1, vertical: -1, distance: 3 },
        blockState: 'minecraft:stone',
      },
    ],
  };
}

test('converts a sparse view into deterministic lossless grid rows', () => {
  const result = compactView(viewFixture());
  assert.deepEqual(result.blockStatePalette, ['minecraft:stone', 'minecraft:dirt']);
  assert.deepEqual(result.blockStateIndexRows, [
    [1, 0, 0],
    [0, 2, 0],
    [0, 0, 1],
  ]);
  assert.deepEqual(result.distanceRows, [
    [1, 0, 0],
    [0, 2, 0],
    [0, 0, 3],
  ]);
  assert.equal(result.format, 'grid');
  assert.equal(result.visibleBlockCount, 3);
});

test('represents an empty view with zero-filled rows', () => {
  const view = viewFixture();
  view.blocks = [];
  view.visibleBlockCount = 0;
  const result = compactView(view);
  assert.deepEqual(result.blockStatePalette, []);
  assert.deepEqual(result.blockStateIndexRows, [
    [0, 0, 0],
    [0, 0, 0],
    [0, 0, 0],
  ]);
});
