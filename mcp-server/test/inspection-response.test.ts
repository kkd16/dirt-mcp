import assert from 'node:assert/strict';
import test from 'node:test';
import { ToolFailure } from '../dist/bridge/errors.js';
import {
  GetBlocksInputSchema,
  requireMatchingCountRegionResponse,
  requireMatchingGetBlocksResponse,
  requireMatchingScanResponse,
  ScanOrthographicViewInputSchema,
} from '../dist/tools/inspection.js';

type CountInput = Parameters<typeof requireMatchingCountRegionResponse>[0];
type CountOutput = Parameters<typeof requireMatchingCountRegionResponse>[1];
type GetInput = Parameters<typeof requireMatchingGetBlocksResponse>[0];
type GetOutput = Parameters<typeof requireMatchingGetBlocksResponse>[1];
type ScanOutput = Parameters<typeof requireMatchingScanResponse>[1];

const isInvalidBridgeResponse = (error: unknown): boolean =>
  error instanceof ToolFailure && error.code === 'bridge_invalid_response';

function assertInvalid(action: () => void): void {
  assert.throws(action, isInvalidBridgeResponse);
}

const countInput: CountInput = {
  world: 'world',
  min: { x: 2, y: 4, z: 6 },
  max: { x: 0, y: 3, z: 6 },
};

function countOutput(): CountOutput {
  return {
    world: 'world',
    bounds: { min: { x: 0, y: 3, z: 6 }, max: { x: 2, y: 4, z: 6 } },
    dimensions: { x: 3, y: 2, z: 1 },
    volume: 6,
    blockStateCounts: { 'minecraft:stone': 4, 'minecraft:air': 2 },
  };
}

test('correlates count-region bounds, dimensions, volume, and histogram totals', () => {
  assert.doesNotThrow(() => requireMatchingCountRegionResponse(countInput, countOutput()));

  const wrongWorld = countOutput();
  wrongWorld.world = 'other_world';
  assertInvalid(() => requireMatchingCountRegionResponse(countInput, wrongWorld));

  const wrongBounds = countOutput();
  wrongBounds.bounds.max.x = 1;
  assertInvalid(() => requireMatchingCountRegionResponse(countInput, wrongBounds));

  const wrongDimensions = countOutput();
  wrongDimensions.dimensions.y = 1;
  assertInvalid(() => requireMatchingCountRegionResponse(countInput, wrongDimensions));

  const wrongVolume = countOutput();
  wrongVolume.volume = 5;
  assertInvalid(() => requireMatchingCountRegionResponse(countInput, wrongVolume));

  const wrongHistogram = countOutput();
  wrongHistogram.blockStateCounts['minecraft:stone'] = 3;
  assertInvalid(() => requireMatchingCountRegionResponse(countInput, wrongHistogram));
});

const getInput = GetBlocksInputSchema.parse({
  world: 'world',
  min: { x: 2, y: 0, z: 0 },
  max: { x: 0, y: 0, z: 0 },
  maxResults: 2,
});

function blocksOutput(): GetOutput {
  return {
    world: 'world',
    origin: { x: 0, y: 0, z: 0 },
    palettes: [[{ blockState: 'minecraft:stone' }], [{ blockState: 'minecraft:dirt' }]],
    placements: [
      [0, 0, 0, 0],
      [1, 2, 0, 0],
    ],
    runs: [],
  };
}

function runsOutput(): GetOutput {
  return {
    world: 'world',
    origin: { x: 0, y: 0, z: 0 },
    palettes: [[{ blockState: 'minecraft:stone' }]],
    placements: [],
    runs: [[0, 0, 0, 0, 2, 0, 0]],
  };
}

test('correlates exact block structures and rejects invalid placements', () => {
  assert.doesNotThrow(() => requireMatchingGetBlocksResponse(getInput, blocksOutput()));
  const defaults = GetBlocksInputSchema.parse({
    world: 'world',
    min: { x: 0, y: 0, z: 0 },
    max: { x: 2, y: 0, z: 0 },
  });
  assert.doesNotThrow(() => requireMatchingGetBlocksResponse(defaults, blocksOutput()));

  const wrongOrigin = blocksOutput();
  wrongOrigin.origin.x = 1;
  assertInvalid(() => requireMatchingGetBlocksResponse(getInput, wrongOrigin));

  const missingPalette = blocksOutput();
  missingPalette.placements[1]![0] = 2;
  assertInvalid(() => requireMatchingGetBlocksResponse(getInput, missingPalette));

  const unusedPalette = blocksOutput();
  unusedPalette.palettes.push([{ blockState: 'minecraft:lantern' }]);
  assertInvalid(() => requireMatchingGetBlocksResponse(getInput, unusedPalette));

  assertInvalid(() => requireMatchingGetBlocksResponse({ ...getInput, maxResults: 1 }, blocksOutput()));

  const outside = blocksOutput();
  outside.placements[0]![1] = -1;
  assertInvalid(() => requireMatchingGetBlocksResponse(getInput, outside));

  const duplicate = blocksOutput();
  duplicate.placements[1] = [1, 0, 0, 0];
  assertInvalid(() => requireMatchingGetBlocksResponse(getInput, duplicate));
});

test('requires runs to be forward, in bounds, and disjoint', () => {
  const input: GetInput = { ...getInput, maxResults: 1 };
  assert.doesNotThrow(() => requireMatchingGetBlocksResponse(input, runsOutput()));

  const outside = runsOutput();
  outside.runs[0]![4] = 3;
  assertInvalid(() => requireMatchingGetBlocksResponse(input, outside));

  const tooManyBlocks = runsOutput();
  tooManyBlocks.runs[0]![5] = 1;
  assertInvalid(() => requireMatchingGetBlocksResponse(input, tooManyBlocks));

  const reversed = runsOutput();
  reversed.runs[0]![1] = 2;
  reversed.runs[0]![4] = 0;
  assertInvalid(() => requireMatchingGetBlocksResponse(input, reversed));

  const overlapping = runsOutput();
  overlapping.placements.push([0, 1, 0, 0]);
  assertInvalid(() => requireMatchingGetBlocksResponse({ ...input, maxResults: 2 }, overlapping));
});

const scanInput = ScanOrthographicViewInputSchema.parse({
  world: 'world',
  origin: { x: 1, y: 2, z: 4 },
  direction: 'north',
  horizontalRadius: 1,
  verticalRadius: 1,
  maxDistance: 3,
  depth: 1,
  maxResults: 3,
});

function scanOutput(): ScanOutput {
  return {
    world: 'world',
    origin: { x: 1, y: 2, z: 4 },
    direction: 'north',
    format: 'blocks',
    basis: {
      forward: { x: 0, y: 0, z: -1 },
      horizontal: { x: 1, y: 0, z: 0 },
      vertical: { x: 0, y: 1, z: 0 },
    },
    viewport: { horizontalRadius: 1, verticalRadius: 1, maxDistance: 3, depth: 1 },
    bounds: { min: { x: 0, y: 1, z: 1 }, max: { x: 2, y: 3, z: 3 } },
    scannedVolume: 27,
    visibleBlockCount: 3,
    blocks: [
      {
        position: { x: 0, y: 3, z: 2 },
        offset: { horizontal: -1, vertical: 1, distance: 2 },
        blockState: 'minecraft:stone',
      },
      {
        position: { x: 2, y: 3, z: 3 },
        offset: { horizontal: 1, vertical: 1, distance: 1 },
        blockState: 'minecraft:dirt',
      },
      {
        position: { x: 1, y: 2, z: 1 },
        offset: { horizontal: 0, vertical: 0, distance: 3 },
        blockState: 'minecraft:gold_block',
      },
    ],
  };
}

test('correlates orthographic metadata, geometry, counts, offsets, and positions', () => {
  assert.doesNotThrow(() => requireMatchingScanResponse(scanInput, scanOutput()));
  assert.doesNotThrow(() => requireMatchingScanResponse({ ...scanInput, maxResults: undefined }, scanOutput()));

  const wrongWorld = scanOutput();
  wrongWorld.world = 'other_world';
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongWorld));

  const wrongOrigin = scanOutput();
  wrongOrigin.origin.x = 0;
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongOrigin));

  const wrongDirection = scanOutput();
  wrongDirection.direction = 'south';
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongDirection));

  const wrongBasis = scanOutput();
  wrongBasis.basis.vertical = { x: 0, y: -1, z: 0 };
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongBasis));

  for (const viewport of [{ horizontalRadius: 0 }, { verticalRadius: 0 }, { maxDistance: 2 }, { depth: 0 }]) {
    const wrongViewport = scanOutput();
    wrongViewport.viewport = { ...wrongViewport.viewport, ...viewport };
    assertInvalid(() => requireMatchingScanResponse(scanInput, wrongViewport));
  }

  const wrongVolume = scanOutput();
  wrongVolume.scannedVolume = 26;
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongVolume));

  const wrongBounds = scanOutput();
  wrongBounds.bounds.min.z = 0;
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongBounds));

  const wrongCount = scanOutput();
  wrongCount.visibleBlockCount = 2;
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongCount));

  assertInvalid(() => requireMatchingScanResponse({ ...scanInput, maxResults: 2 }, scanOutput()));

  for (const offset of [{ horizontal: 2 }, { vertical: 2 }, { distance: 4 }]) {
    const outside = scanOutput();
    outside.blocks[0]!.offset = { ...outside.blocks[0]!.offset, ...offset };
    assertInvalid(() => requireMatchingScanResponse(scanInput, outside));
  }

  const wrongPosition = scanOutput();
  wrongPosition.blocks[0]!.position.z = 3;
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongPosition));

  const duplicateCell = scanOutput();
  duplicateCell.blocks[1]!.offset = { ...duplicateCell.blocks[0]!.offset };
  duplicateCell.blocks[1]!.position = { ...duplicateCell.blocks[0]!.position };
  assertInvalid(() => requireMatchingScanResponse(scanInput, duplicateCell));

  const wrongOrder = scanOutput();
  [wrongOrder.blocks[0], wrongOrder.blocks[1]] = [wrongOrder.blocks[1]!, wrongOrder.blocks[0]!];
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongOrder));
});
