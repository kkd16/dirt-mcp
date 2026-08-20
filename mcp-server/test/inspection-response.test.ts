import assert from 'node:assert/strict';
import test from 'node:test';
import { ToolFailure } from '../dist/bridge/errors.js';
import {
  GetRegionBlocksInputSchema,
  requireMatchingCountRegionResponse,
  requireMatchingGetRegionResponse,
  requireMatchingScanResponse,
  ScanOrthographicViewInputSchema,
} from '../dist/tools/inspection.js';

type CountInput = Parameters<typeof requireMatchingCountRegionResponse>[0];
type CountOutput = Parameters<typeof requireMatchingCountRegionResponse>[1];
type GetInput = Parameters<typeof requireMatchingGetRegionResponse>[0];
type GetOutput = Parameters<typeof requireMatchingGetRegionResponse>[1];
type ScanOutput = Parameters<typeof requireMatchingScanResponse>[1];
type BlocksOutput = Extract<GetOutput, { format: 'blocks' }>;
type RunsOutput = Extract<GetOutput, { format: 'runs' }>;

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

const getInput = GetRegionBlocksInputSchema.parse({
  world: 'world',
  min: { x: 2, y: 0, z: 0 },
  max: { x: 0, y: 0, z: 0 },
  format: 'blocks',
  maxResults: 2,
});

function blocksOutput(): BlocksOutput {
  return {
    world: 'world',
    bounds: { min: { x: 0, y: 0, z: 0 }, max: { x: 2, y: 0, z: 0 } },
    volume: 3,
    matchedBlockCount: 2,
    format: 'blocks',
    blocks: [
      { position: { x: 0, y: 0, z: 0 }, blockState: 'minecraft:stone' },
      { position: { x: 2, y: 0, z: 0 }, blockState: 'minecraft:dirt' },
    ],
  };
}

function runsOutput(): RunsOutput {
  return {
    world: 'world',
    bounds: { min: { x: 0, y: 0, z: 0 }, max: { x: 2, y: 0, z: 0 } },
    volume: 3,
    matchedBlockCount: 3,
    format: 'runs',
    runs: [
      {
        blockState: 'minecraft:stone',
        from: { x: 0, y: 0, z: 0 },
        to: { x: 2, y: 0, z: 0 },
      },
    ],
  };
}

test('correlates explicit region blocks and rejects duplicate or out-of-bounds positions', () => {
  assert.doesNotThrow(() => requireMatchingGetRegionResponse(getInput, blocksOutput()));
  const defaults = GetRegionBlocksInputSchema.parse({
    world: 'world',
    min: { x: 0, y: 0, z: 0 },
    max: { x: 2, y: 0, z: 0 },
  });
  assert.doesNotThrow(() => requireMatchingGetRegionResponse(defaults, blocksOutput()));

  const wrongBounds = blocksOutput();
  wrongBounds.bounds.max.x = 1;
  assertInvalid(() => requireMatchingGetRegionResponse(getInput, wrongBounds));

  const wrongVolume = blocksOutput();
  wrongVolume.volume = 2;
  assertInvalid(() => requireMatchingGetRegionResponse(getInput, wrongVolume));

  assertInvalid(() => requireMatchingGetRegionResponse(getInput, runsOutput()));

  const tooManyMatches = blocksOutput();
  tooManyMatches.matchedBlockCount = 4;
  assertInvalid(() => requireMatchingGetRegionResponse(getInput, tooManyMatches));

  assertInvalid(() => requireMatchingGetRegionResponse({ ...getInput, maxResults: 1 }, blocksOutput()));

  const wrongCount = blocksOutput();
  wrongCount.matchedBlockCount = 1;
  assertInvalid(() => requireMatchingGetRegionResponse(getInput, wrongCount));

  const outside = blocksOutput();
  outside.blocks[0]!.position.x = -1;
  assertInvalid(() => requireMatchingGetRegionResponse(getInput, outside));

  const duplicate = blocksOutput();
  duplicate.blocks[1]!.position = { ...duplicate.blocks[0]!.position };
  assertInvalid(() => requireMatchingGetRegionResponse(getInput, duplicate));
});

test('requires region runs to be forward axis-aligned and represent the exact match count', () => {
  const input: GetInput = { ...getInput, format: 'runs', maxResults: 1 };
  assert.doesNotThrow(() => requireMatchingGetRegionResponse(input, runsOutput()));

  const outside = runsOutput();
  outside.runs[0]!.to.x = 3;
  assertInvalid(() => requireMatchingGetRegionResponse(input, outside));

  const diagonal = runsOutput();
  diagonal.runs[0]!.to.y = 1;
  assertInvalid(() => requireMatchingGetRegionResponse(input, diagonal));

  const reversed = runsOutput();
  reversed.runs[0]!.from.x = 2;
  reversed.runs[0]!.to.x = 0;
  assertInvalid(() => requireMatchingGetRegionResponse(input, reversed));

  const wrongCount = runsOutput();
  wrongCount.matchedBlockCount = 2;
  assertInvalid(() => requireMatchingGetRegionResponse(input, wrongCount));
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

  const wrongOrder = scanOutput();
  [wrongOrder.blocks[0], wrongOrder.blocks[1]] = [wrongOrder.blocks[1]!, wrongOrder.blocks[0]!];
  assertInvalid(() => requireMatchingScanResponse(scanInput, wrongOrder));
});
