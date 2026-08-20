import assert from 'node:assert/strict';
import test from 'node:test';
import { ToolFailure } from '../dist/bridge/errors.js';
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
    scannedVolume: 20,
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

function assertInvalid(view: ScanOrthographicViewBlocksOutput, viewport = requested): void {
  assert.throws(
    () => compactView(view, viewport),
    (error: unknown) => error instanceof ToolFailure && error.code === 'bridge_invalid_response',
  );
}

test('converts a sparse view into deterministic lossless grid rows', () => {
  const result = compactView(viewFixture(), requested);
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
  view.scannedVolume = 27;
  const result = compactView(view, requested);
  assert.deepEqual(result.blockStatePalette, []);
  assert.deepEqual(result.blockStateIndexRows, [
    [0, 0, 0],
    [0, 0, 0],
    [0, 0, 0],
  ]);
});

test('rejects inconsistent or resource-amplifying bridge views before returning a grid', () => {
  const mismatchedViewport = viewFixture();
  mismatchedViewport.viewport = { ...requested, maxDistance: 2 };
  assertInvalid(mismatchedViewport);

  const mismatchedDepth = viewFixture();
  mismatchedDepth.viewport = { ...requested, depth: 0 };
  assertInvalid(mismatchedDepth);

  const mismatchedCount = viewFixture();
  mismatchedCount.visibleBlockCount = 2;
  assertInvalid(mismatchedCount);

  const invalidVolume = viewFixture();
  invalidVolume.scannedVolume = 8;
  assertInvalid(invalidVolume);

  const outsideViewport = viewFixture();
  const outsideBlock = outsideViewport.blocks[0];
  assert.ok(outsideBlock);
  outsideBlock.offset.horizontal = 2;
  assertInvalid(outsideViewport);

  const excessiveDistance = viewFixture();
  const distantBlock = excessiveDistance.blocks[0];
  assert.ok(distantBlock);
  distantBlock.offset.distance = 4;
  assertInvalid(excessiveDistance);

  const duplicateCell = viewFixture();
  const duplicate = duplicateCell.blocks[1];
  assert.ok(duplicate);
  duplicate.offset = { horizontal: -1, vertical: 1, distance: 2 };
  assertInvalid(duplicateCell);

  const unsafeDimensions = viewFixture();
  unsafeDimensions.viewport = {
    horizontalRadius: 2_147_483_647,
    verticalRadius: 2_147_483_647,
    maxDistance: 2_147_483_647,
    depth: 1,
  };
  assertInvalid(unsafeDimensions, unsafeDimensions.viewport);
});
