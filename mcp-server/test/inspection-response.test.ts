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
    origin: { x: 0, y: 1, z: 1 },
    palettes: [
      [{ blockState: 'minecraft:gold_block' }],
      [{ blockState: 'minecraft:stone' }],
      [{ blockState: 'minecraft:dirt' }],
    ],
    placements: [
      [0, 1, 1, 0],
      [1, 0, 2, 1],
      [2, 2, 2, 2],
    ],
    runs: [],
  };
}

test('correlates orthographic exact geometry with its viewport', () => {
  assert.doesNotThrow(() => requireMatchingScanResponse(scanInput, scanOutput()));
  assert.doesNotThrow(() => requireMatchingScanResponse({ ...scanInput, maxResults: undefined }, scanOutput()));

  const wrongWorld = scanOutput();
  wrongWorld.world = 'other_world';
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongWorld));

  const wrongOrigin = scanOutput();
  wrongOrigin.origin.x = 1;
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongOrigin));

  assertInvalid(() => requireMatchingScanResponse({ ...scanInput, maxResults: 2 }, scanOutput()));

  const outsidePalette = scanOutput();
  outsidePalette.placements[0]![0] = 3;
  assertInvalid(() => requireMatchingScanResponse(scanInput, outsidePalette));

  const unusedPaletteEntry = scanOutput();
  unusedPaletteEntry.palettes.push([{ blockState: 'minecraft:diamond_block' }]);
  assertInvalid(() => requireMatchingScanResponse(scanInput, unusedPaletteEntry));

  const duplicateSightline = scanOutput();
  duplicateSightline.placements.push([0, 1, 1, 1]);
  assertInvalid(() => requireMatchingScanResponse({ ...scanInput, maxResults: 4 }, duplicateSightline));

  const horizontalRun = scanOutput();
  horizontalRun.palettes = [[{ blockState: 'minecraft:stone' }]];
  horizontalRun.placements = [];
  horizontalRun.runs = [[0, 0, 1, 0, 2, 1, 0]];
  assert.doesNotThrow(() => requireMatchingScanResponse({ ...scanInput, maxResults: 1 }, horizontalRun));

  const forwardRun = scanOutput();
  forwardRun.palettes = [[{ blockState: 'minecraft:stone' }]];
  forwardRun.placements = [];
  forwardRun.runs = [[0, 1, 1, 0, 1, 1, 1]];
  assertInvalid(() => requireMatchingScanResponse({ ...scanInput, maxResults: 1 }, forwardRun));

  const hugeInput = ScanOrthographicViewInputSchema.parse({
    world: 'world',
    origin: { x: 0, y: 0, z: 1_000_000_001 },
    direction: 'north',
    horizontalRadius: 0,
    verticalRadius: 0,
    maxDistance: 1_000_000_000,
    maxResults: 1,
  });
  const hugeForwardRun: ScanOutput = {
    world: 'world',
    origin: { x: 0, y: 0, z: 1 },
    palettes: [[{ blockState: 'minecraft:stone' }]],
    placements: [],
    runs: [[0, 0, 0, 0, 0, 0, 999_999_999]],
  };
  assertInvalid(() => requireMatchingScanResponse(hugeInput, hugeForwardRun));
});
