import assert from 'node:assert/strict';
import test from 'node:test';
import {
  GetPerspectiveViewInputSchema,
  PerspectiveViewOutputSchema,
  perspectiveViewBridgeOutputSchema,
} from '../dist/tools/perspective.js';

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
  assert.equal(
    GetPerspectiveViewInputSchema.safeParse({ source: { type: 'player', player: '🧱'.repeat(36) } }).success,
    true,
  );
  assert.equal(
    GetPerspectiveViewInputSchema.safeParse({ source: { type: 'player', player: '🧱'.repeat(37) } }).success,
    false,
  );
});

test('perspective output enforces palette, hit-order, and crosshair invariants', () => {
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
    blockStatePalette: [],
    hits: [],
    crosshairHitIndex: null,
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

  const hit = {
    row: 1,
    column: 1,
    blockStateIndex: 1,
    blockPosition: { x: 0, y: 64, z: 2 },
    hitPosition: { x: 0.5, y: 64.5, z: 2 },
    face: 'north',
    distance: 1.5,
  } as const;
  const withHit = {
    ...output,
    blockStatePalette: ['minecraft:stone'],
    hits: [hit],
    crosshairHitIndex: 0,
  };
  assert.equal(PerspectiveViewOutputSchema.safeParse(withHit).success, true);
  assert.equal(PerspectiveViewOutputSchema.safeParse({ ...withHit, crosshairHitIndex: 1 }).success, false);
  assert.equal(PerspectiveViewOutputSchema.safeParse({ ...withHit, hits: [{ ...hit, row: 3 }] }).success, false);
  assert.equal(
    PerspectiveViewOutputSchema.safeParse({ ...withHit, hits: [{ ...hit, blockStateIndex: 2 }] }).success,
    false,
  );
  assert.equal(
    PerspectiveViewOutputSchema.safeParse({
      ...withHit,
      hits: [hit, { ...hit, column: 0 }],
      crosshairHitIndex: 0,
    }).success,
    false,
  );
});

test('perspective bridge responses preserve the requested source and viewport', () => {
  const cameraPosition = { x: 0.5, y: 64, z: 0.5 } as const;
  const request = GetPerspectiveViewInputSchema.parse({
    source: {
      type: 'location',
      world: 'world',
      cameraPosition,
      rotation: { yaw: 0, pitch: 0 },
    },
    width: 3,
    height: 3,
  });
  const output = {
    capturedAt: '2026-08-23T12:00:00Z',
    source: { type: 'location' },
    world: 'world',
    worldId: '11111111-1111-4111-8111-111111111111',
    cameraPosition,
    rotation: { yaw: 0, pitch: 0 },
    lookDirection: { x: 0, y: 0, z: 1 },
    basis: {
      forward: { x: 0, y: 0, z: 1 },
      right: { x: -1, y: 0, z: 0 },
      up: { x: 0, y: 1, z: 0 },
    },
    viewport: {
      width: request.width,
      height: request.height,
      verticalFieldOfViewDegrees: request.verticalFieldOfViewDegrees,
      horizontalFieldOfViewDegrees: 70,
      maxDistance: request.maxDistance,
      fluidCollision: request.fluidCollision,
      ignorePassableBlocks: request.ignorePassableBlocks,
    },
    checkedChunkCount: 1,
    blockStatePalette: [],
    hits: [],
    crosshairHitIndex: null,
  } as const;
  const schema = perspectiveViewBridgeOutputSchema(request);
  assert.equal(schema.safeParse(output).success, true);
  assert.equal(schema.safeParse({ ...output, world: 'other' }).success, false);
  assert.equal(schema.safeParse({ ...output, viewport: { ...output.viewport, width: 5 } }).success, false);
  assert.equal(schema.safeParse({ ...output, cameraPosition: { x: 1, y: 64, z: 0.5 } }).success, false);
  assert.equal(schema.safeParse({ ...output, rotation: { yaw: 1, pitch: 0 } }).success, false);

  const requestedRotation = { yaw: 540, pitch: 12.345 };
  const normalizedRequest = GetPerspectiveViewInputSchema.parse({
    ...request,
    source: { ...request.source, rotation: requestedRotation },
  });
  const normalizedSchema = perspectiveViewBridgeOutputSchema(normalizedRequest);
  assert.equal(
    normalizedSchema.safeParse({
      ...output,
      rotation: { yaw: -180, pitch: Math.fround(12.345) },
    }).success,
    true,
  );
  assert.equal(normalizedSchema.safeParse({ ...output, rotation: requestedRotation }).success, false);
});
