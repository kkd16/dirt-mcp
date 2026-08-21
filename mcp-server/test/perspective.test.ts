import assert from 'node:assert/strict';
import test from 'node:test';
import { ToolFailure } from '../dist/bridge/errors.js';
import {
  GetPerspectiveViewInputSchema,
  PerspectiveViewOutputSchema,
  requireMatchingPerspectiveViewResponse,
} from '../dist/tools/perspective.js';

type PerspectiveInput = Parameters<typeof requireMatchingPerspectiveViewResponse>[0];
type PerspectiveOutput = Parameters<typeof requireMatchingPerspectiveViewResponse>[1];

const cameraPosition = { x: 10.25, y: 65.62, z: -2.5 };
const horizontalFov = (2 * Math.atan(Math.tan((70 * Math.PI) / 360) * (21 / 13)) * 180) / Math.PI;

function hit(row: number, column: number, blockStateIndex: number, distance: number) {
  const horizontal = ((2 * (column + 0.5)) / 21 - 1) * (21 / 13) * Math.tan((70 * Math.PI) / 360);
  const vertical = (1 - (2 * (row + 0.5)) / 13) * Math.tan((70 * Math.PI) / 360);
  const length = Math.hypot(-horizontal, vertical, 1);
  const hitPosition = {
    x: cameraPosition.x - (horizontal / length) * distance,
    y: cameraPosition.y + (vertical / length) * distance,
    z: cameraPosition.z + (1 / length) * distance,
  };
  return {
    row,
    column,
    blockStateIndex,
    blockPosition: {
      x: Math.floor(hitPosition.x),
      y: Math.floor(hitPosition.y),
      z: Math.floor(hitPosition.z),
    },
    hitPosition,
    face: null,
    distance,
  };
}

function playerInput(player = 'Builder'): PerspectiveInput {
  return GetPerspectiveViewInputSchema.parse({ source: { type: 'player', player } });
}

function output(): PerspectiveOutput {
  return PerspectiveViewOutputSchema.parse({
    capturedAt: '2026-08-20T20:15:30Z',
    source: {
      type: 'player',
      player: { name: 'Builder', uuid: '123e4567-e89b-42d3-a456-426614174000' },
    },
    world: 'world',
    worldId: '123e4567-e89b-32d3-a456-426614174001',
    cameraPosition,
    rotation: { yaw: 0, pitch: 0 },
    lookDirection: { x: 0, y: 0, z: 1 },
    basis: {
      forward: { x: 0, y: 0, z: 1 },
      right: { x: -1, y: 0, z: 0 },
      up: { x: 0, y: 1, z: 0 },
    },
    viewport: {
      width: 21,
      height: 13,
      verticalFieldOfViewDegrees: 70,
      horizontalFieldOfViewDegrees: horizontalFov,
      maxDistance: 32,
      fluidCollision: 'never',
      ignorePassableBlocks: false,
    },
    checkedChunkCount: 4,
    blockStatePalette: ['minecraft:stone', 'minecraft:oak_planks'],
    hits: [hit(0, 0, 1, 10), hit(6, 10, 2, 7.5)],
    crosshairHitIndex: 1,
  });
}

function invalid(action: () => void): void {
  assert.throws(action, (error) => error instanceof ToolFailure && error.code === 'bridge_invalid_response');
}

test('accepts case-insensitive players and applies bounded projection defaults', () => {
  assert.deepEqual(playerInput('builder'), {
    source: { type: 'player', player: 'builder' },
    width: 21,
    height: 13,
    verticalFieldOfViewDegrees: 70,
    maxDistance: 32,
    fluidCollision: 'never',
    ignorePassableBlocks: false,
  });
  assert.doesNotThrow(() => requireMatchingPerspectiveViewResponse(playerInput('builder'), output()));
  assert.equal(
    GetPerspectiveViewInputSchema.safeParse({ source: { type: 'player', player: 'x'.repeat(37) } }).success,
    false,
  );
  assert.equal(
    GetPerspectiveViewInputSchema.safeParse({ source: { type: 'player', player: 'Builder' }, width: 20 }).success,
    false,
  );
});

test('accepts a fully synthetic camera source and correlates its resolved pose', () => {
  const request = GetPerspectiveViewInputSchema.parse({
    source: {
      type: 'location',
      world: 'world',
      cameraPosition,
      rotation: { yaw: 0, pitch: 0 },
    },
  });
  const result = PerspectiveViewOutputSchema.parse({ ...output(), source: { type: 'location' } });
  assert.doesNotThrow(() => requireMatchingPerspectiveViewResponse(request, result));

  const wrappedYawRequest = GetPerspectiveViewInputSchema.parse({
    source: {
      type: 'location',
      world: 'world',
      cameraPosition,
      rotation: { yaw: 360, pitch: 0 },
    },
  });
  assert.doesNotThrow(() => requireMatchingPerspectiveViewResponse(wrappedYawRequest, result));

  const wrongPosition = structuredClone(result);
  wrongPosition.cameraPosition.x++;
  invalid(() => requireMatchingPerspectiveViewResponse(request, wrongPosition));

  assert.equal(
    GetPerspectiveViewInputSchema.safeParse({
      source: { type: 'location', world: 'world', cameraPosition, rotation: { yaw: 0, pitch: 91 } },
    }).success,
    false,
  );
});

test('rejects inconsistent viewport, basis, palette, hits, and crosshair metadata', () => {
  const wrongViewport = structuredClone(output());
  wrongViewport.viewport.maxDistance--;
  invalid(() => requireMatchingPerspectiveViewResponse(playerInput(), wrongViewport));

  const wrongBasis = structuredClone(output());
  wrongBasis.basis.right = { x: 1, y: 0, z: 0 };
  invalid(() => requireMatchingPerspectiveViewResponse(playerInput(), wrongBasis));

  const fabricatedHit = structuredClone(output());
  fabricatedHit.hits[0]!.hitPosition.x += 0.01;
  invalid(() => requireMatchingPerspectiveViewResponse(playerInput(), fabricatedHit));

  const wrongOrder = structuredClone(output());
  wrongOrder.hits.reverse();
  invalid(() => requireMatchingPerspectiveViewResponse(playerInput(), wrongOrder));

  const skippedPalette = structuredClone(output());
  skippedPalette.hits[0]!.blockStateIndex = 2;
  invalid(() => requireMatchingPerspectiveViewResponse(playerInput(), skippedPalette));

  const wrongCrosshair = structuredClone(output());
  wrongCrosshair.crosshairHitIndex = 0;
  invalid(() => requireMatchingPerspectiveViewResponse(playerInput(), wrongCrosshair));
});

test('accepts an empty preflight and collision hits outside the owning block cube', () => {
  const empty = structuredClone(output());
  empty.checkedChunkCount = 0;
  empty.blockStatePalette = [];
  empty.hits = [];
  empty.crosshairHitIndex = null;
  assert.doesNotThrow(() => requireMatchingPerspectiveViewResponse(playerInput(), empty));

  const extendedShape = structuredClone(output());
  extendedShape.hits[0] = hit(0, 0, 1, 9);
  extendedShape.hits[0]!.blockPosition.y--;
  assert.doesNotThrow(() => requireMatchingPerspectiveViewResponse(playerInput(), extendedShape));
});
