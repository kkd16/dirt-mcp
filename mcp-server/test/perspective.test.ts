import assert from 'node:assert/strict';
import test from 'node:test';
import { GetPerspectiveViewInputSchema, PerspectiveViewOutputSchema } from '../dist/tools/perspective.js';

test('perspective input materializes fixed MCP defaults', () => {
  const parsed = GetPerspectiveViewInputSchema.parse({ source: { type: 'player', player: 'Builder' } });
  assert.deepEqual(parsed, {
    source: { type: 'player', player: 'Builder' },
    width: 21,
    height: 13,
    verticalFieldOfViewDegrees: 70,
    maxDistance: 32,
    fluidCollision: 'never',
    ignorePassableBlocks: false,
  });
  assert.equal(
    GetPerspectiveViewInputSchema.safeParse({ source: { type: 'player', player: 'Builder' }, width: 2 }).success,
    false,
  );
});

test('perspective output enforces the unique block-state palette', () => {
  const output = {
    capturedAt: '2026-08-23T12:00:00Z',
    source: { type: 'location' },
    world: 'world',
    worldId: '11111111-1111-4111-8111-111111111111',
    cameraPosition: { x: 0.5, y: 64, z: 0.5 },
    rotation: { yaw: 0, pitch: 0 },
    lookDirection: { x: 9, y: 9, z: 9 },
    basis: {
      forward: { x: 0, y: 0, z: 1 },
      right: { x: -1, y: 0, z: 0 },
      up: { x: 0, y: 1, z: 0 },
    },
    viewport: {
      width: 3,
      height: 3,
      verticalFieldOfViewDegrees: 70,
      horizontalFieldOfViewDegrees: 70,
      maxDistance: 32,
      fluidCollision: 'never',
      ignorePassableBlocks: false,
    },
    checkedChunkCount: 1,
    blockStatePalette: ['minecraft:stone'],
    hits: [],
    crosshairHitIndex: 99,
  };
  assert.equal(PerspectiveViewOutputSchema.safeParse(output).success, true);
  assert.equal(
    PerspectiveViewOutputSchema.safeParse({
      ...output,
      blockStatePalette: ['minecraft:stone', 'minecraft:stone'],
    }).success,
    false,
  );
  assert.equal(PerspectiveViewOutputSchema.safeParse({ ...output, source: { type: 'unknown' } }).success, false);
});
