import assert from 'node:assert/strict';
import test from 'node:test';
import { ToolFailure } from '../dist/bridge/errors.js';
import {
  GetPlayerContextInputSchema,
  PlayerContextOutputSchema,
  requireMatchingPlayerContextResponse,
} from '../dist/tools/player.js';

type PlayerInput = Parameters<typeof requireMatchingPlayerContextResponse>[0];
type PlayerOutput = Parameters<typeof requireMatchingPlayerContextResponse>[1];

const horizontalFov = (2 * Math.atan(Math.tan((70 * Math.PI) / 360) * (21 / 13)) * 180) / Math.PI;
const eyePosition = { x: 10.25, y: 65.62, z: -2.5 };
const INT32_MAX = 2_147_483_647;

function perspectiveHit(row: number, column: number, blockStateIndex: number, distance: number) {
  const horizontal = ((2 * (column + 0.5)) / 21 - 1) * (21 / 13) * Math.tan((70 * Math.PI) / 360);
  const vertical = (1 - (2 * (row + 0.5)) / 13) * Math.tan((70 * Math.PI) / 360);
  const length = Math.hypot(-horizontal, vertical, 1);
  const hitPosition = {
    x: eyePosition.x - (horizontal / length) * distance,
    y: eyePosition.y + (vertical / length) * distance,
    z: eyePosition.z + (1 / length) * distance,
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

function input(player = 'Builder'): PlayerInput {
  return GetPlayerContextInputSchema.parse({ player });
}

function output(): PlayerOutput {
  return PlayerContextOutputSchema.parse({
    capturedAt: '2026-08-20T20:15:30Z',
    player: { name: 'Builder', uuid: '123e4567-e89b-42d3-a456-426614174000' },
    world: 'world',
    worldId: '123e4567-e89b-32d3-a456-426614174001',
    gameMode: 'creative',
    feetPosition: { x: 10.25, y: 64, z: -2.5 },
    blockPosition: { x: 10, y: 64, z: -3 },
    eyePosition,
    rotation: { yaw: 0, pitch: 0 },
    lookDirection: { x: 0, y: 0, z: 1 },
    pose: 'standing',
    onGround: true,
    view: {
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
      hits: [perspectiveHit(0, 0, 1, 10), perspectiveHit(6, 10, 2, 7.5)],
      crosshairHitIndex: 1,
    },
    equipment: {
      selectedHotbarSlot: 2,
      mainHand: {
        type: 'minecraft:diamond_pickaxe',
        amount: 1,
        maxStackSize: 1,
        damage: 12,
        maxDamage: 1_561,
        unbreakable: false,
        enchantments: [{ type: 'minecraft:efficiency', level: 5 }],
      },
      offHand: null,
      helmet: null,
      chestplate: null,
      leggings: null,
      boots: null,
    },
    inventory: null,
    enderChest: null,
    vitals: null,
    movement: null,
    client: null,
    effects: null,
  });
}

function cloneOutput(): PlayerOutput {
  return structuredClone(output());
}

function assertInvalidResponse(action: () => void): void {
  assert.throws(action, (error) => error instanceof ToolFailure && error.code === 'bridge_invalid_response');
}

test('applies player context defaults and enforces dependent view options', () => {
  assert.deepEqual(input(), {
    player: 'Builder',
    include: {
      view: true,
      equipment: true,
      inventory: false,
      enderChest: false,
      vitals: false,
      movement: false,
      client: false,
      effects: false,
    },
  });
  assert.deepEqual(GetPlayerContextInputSchema.parse({ player: '123e4567-e89b-32d3-a456-426614174001' }), {
    player: '123e4567-e89b-32d3-a456-426614174001',
    include: {
      view: true,
      equipment: true,
      inventory: false,
      enderChest: false,
      vitals: false,
      movement: false,
      client: false,
      effects: false,
    },
  });
  assert.equal(GetPlayerContextInputSchema.safeParse({ player: '123E4567-E89B-0000-0000-426614174001' }).success, true);
  assert.equal(GetPlayerContextInputSchema.safeParse({ player: 'Proxy Player!' }).success, true);
  assert.equal(GetPlayerContextInputSchema.safeParse({ player: 'x'.repeat(36) }).success, true);
  assert.equal(GetPlayerContextInputSchema.safeParse({ player: 'x'.repeat(37) }).success, false);

  assert.deepEqual(GetPlayerContextInputSchema.parse({ player: 'Builder', include: { vitals: true } }).include, {
    view: true,
    equipment: true,
    inventory: false,
    enderChest: false,
    vitals: true,
    movement: false,
    client: false,
    effects: false,
  });
  assert.equal(
    GetPlayerContextInputSchema.safeParse({ player: 'Builder', view: { verticalFieldOfViewDegrees: 1 } }).success,
    true,
  );
  assert.equal(
    GetPlayerContextInputSchema.safeParse({
      player: 'Builder',
      view: { fluidCollision: 'always', ignorePassableBlocks: true },
    }).success,
    true,
  );

  for (const candidate of [
    { player: 'Builder', view: { width: 20 } },
    { player: 'Builder', view: { height: 12 } },
    { player: 'Builder', include: { view: false }, view: {} },
    { player: 'Builder', view: { verticalFieldOfViewDegrees: 0 } },
    { player: '   ' },
  ]) {
    assert.equal(GetPlayerContextInputSchema.safeParse(candidate).success, false);
  }
});

test('accepts and correlates a complete default player view', () => {
  const result = output();
  assert.doesNotThrow(() => requireMatchingPlayerContextResponse(input('builder'), result));
  assert.doesNotThrow(() =>
    requireMatchingPlayerContextResponse(input('123e4567-e89b-42d3-a456-426614174000'), result),
  );
  const arbitraryUuid = '123e4567-e89b-0000-0000-426614174001';
  const arbitraryUuidResult = PlayerContextOutputSchema.parse({
    ...result,
    player: { ...result.player, uuid: arbitraryUuid },
  });
  assert.doesNotThrow(() => requireMatchingPlayerContextResponse(input(arbitraryUuid), arbitraryUuidResult));
});

test('accepts a view whose build-height preflight checks no chunks', () => {
  const candidate = cloneOutput();
  candidate.view!.checkedChunkCount = 0;
  candidate.view!.blockStatePalette = [];
  candidate.view!.hits = [];
  candidate.view!.crosshairHitIndex = null;

  const result = PlayerContextOutputSchema.parse(candidate);
  assert.doesNotThrow(() => requireMatchingPlayerContextResponse(input(), result));

  candidate.view!.checkedChunkCount = -1;
  assert.equal(PlayerContextOutputSchema.safeParse(candidate).success, false);
});

test('correlates player-view hit geometry near the signed-int32 coordinate boundary', () => {
  const result = cloneOutput();
  result.view!.hits[0] = perspectiveHit(0, 0, 1, result.view!.viewport.maxDistance);
  const xOffset = INT32_MAX - 64 - result.eyePosition.x;
  result.feetPosition.x += xOffset;
  result.blockPosition.x = Math.floor(result.feetPosition.x);
  result.eyePosition.x += xOffset;
  for (const hit of result.view!.hits) {
    hit.hitPosition.x += xOffset;
    hit.blockPosition.x = Math.floor(hit.hitPosition.x);
  }

  const boundaryHit = result.view!.hits[0]!;
  boundaryHit.hitPosition.x +=
    Number.EPSILON * Math.max(Math.abs(result.eyePosition.x), Math.abs(boundaryHit.hitPosition.x));
  boundaryHit.blockPosition.x = Math.floor(boundaryHit.hitPosition.x);
  boundaryHit.distance = Math.hypot(
    boundaryHit.hitPosition.x - result.eyePosition.x,
    boundaryHit.hitPosition.y - result.eyePosition.y,
    boundaryHit.hitPosition.z - result.eyePosition.z,
  );
  assert.ok(boundaryHit.distance > result.view!.viewport.maxDistance);
  assert.doesNotThrow(() => requireMatchingPlayerContextResponse(input(), result));
});

test('accepts collision hits outside the owning block cube', () => {
  const result = cloneOutput();
  const extendedShapeHit = perspectiveHit(0, 0, 1, 9);
  extendedShapeHit.blockPosition.y--;
  result.view!.hits[0] = extendedShapeHit;
  assert.ok(extendedShapeHit.hitPosition.y > extendedShapeHit.blockPosition.y + 1);
  assert.ok(extendedShapeHit.hitPosition.y <= extendedShapeHit.blockPosition.y + 1.5);
  assert.doesNotThrow(() => requireMatchingPlayerContextResponse(input(), result));
});

test('accepts excluded view and all bounded optional player-state sections', () => {
  const request = GetPlayerContextInputSchema.parse({
    player: 'Builder',
    include: {
      view: false,
      equipment: false,
      inventory: true,
      enderChest: true,
      vitals: true,
      movement: true,
      client: true,
      effects: true,
    },
  });
  const result = PlayerContextOutputSchema.parse({
    ...output(),
    view: null,
    equipment: null,
    inventory: {
      size: 36,
      slots: [
        {
          slot: 8,
          item: {
            type: 'minecraft:stone',
            amount: 64,
            maxStackSize: 64,
            damage: null,
            maxDamage: null,
            unbreakable: false,
            enchantments: [],
          },
        },
      ],
    },
    enderChest: {
      size: 27,
      slots: [],
    },
    vitals: {
      health: 20,
      maxHealth: 20,
      absorptionAmount: 0,
      foodLevel: 20,
      saturation: 5,
      exhaustion: 0,
      remainingAir: 300,
      maximumAir: 300,
      experienceLevel: 7,
      experienceProgress: 0.5,
      calculatedExperiencePoints: -1,
      fireTicks: -1,
      freezeTicks: 0,
    },
    movement: {
      velocity: { x: 0, y: 0, z: 0 },
      fallDistance: 0,
      allowFlight: true,
      flying: false,
      sneaking: false,
      sprinting: false,
      swimming: false,
      gliding: false,
      sleeping: false,
      blocking: false,
      riptiding: false,
    },
    client: {
      pingMillis: 25,
      locale: 'en_us',
      clientViewDistance: 12,
      viewDistance: 10,
      sendViewDistance: 10,
    },
    effects: [
      {
        type: 'minecraft:night_vision',
        amplifier: -2,
        durationTicks: -1,
        ambient: false,
        particles: true,
        icon: true,
      },
    ],
  });
  assert.doesNotThrow(() => requireMatchingPlayerContextResponse(request, result));
});

test('correlates main-hand equipment with the selected hotbar inventory slot', () => {
  const request = GetPlayerContextInputSchema.parse({
    player: 'Builder',
    include: { view: false, inventory: true },
  });
  const result = cloneOutput();
  result.view = null;
  assert.ok(result.equipment);
  assert.ok(result.equipment.mainHand);
  result.inventory = {
    size: 36,
    slots: [{ slot: result.equipment.selectedHotbarSlot, item: structuredClone(result.equipment.mainHand) }],
  };
  assert.doesNotThrow(() => requireMatchingPlayerContextResponse(request, result));

  const contradictory = structuredClone(result);
  assert.ok(contradictory.inventory);
  assert.ok(contradictory.inventory.slots[0]);
  contradictory.inventory.slots[0].item.amount++;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(request, contradictory));

  const omitted = structuredClone(result);
  assert.ok(omitted.inventory);
  assert.ok(omitted.equipment);
  omitted.inventory.slots = [];
  omitted.equipment.mainHand = null;
  assert.doesNotThrow(() => requireMatchingPlayerContextResponse(request, omitted));
});

test('rejects player identity, positioning, section, and viewport mismatches', () => {
  const wrongName = cloneOutput();
  wrongName.player.name = 'SomeoneElse';
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongName));

  const wrongBlock = cloneOutput();
  wrongBlock.blockPosition.x++;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongBlock));

  const wrongSection = cloneOutput();
  wrongSection.equipment = null;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongSection));

  const wrongViewport = cloneOutput();
  wrongViewport.view!.viewport.maxDistance--;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongViewport));

  const wrongHorizontalFov = cloneOutput();
  wrongHorizontalFov.view!.viewport.horizontalFieldOfViewDegrees++;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongHorizontalFov));

  const wrongLookDirection = cloneOutput();
  wrongLookDirection.lookDirection = { x: 1, y: 0, z: 0 };
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongLookDirection));

  const wrongForward = cloneOutput();
  wrongForward.view!.basis.forward = { x: 1, y: 0, z: 0 };
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongForward));

  const wrongRight = cloneOutput();
  wrongRight.view!.basis.right = { x: 1, y: 0, z: 0 };
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongRight));
});

test('rejects inconsistent player-view hits, palette, order, and crosshair metadata', () => {
  const outsideHit = cloneOutput();
  outsideHit.view!.hits[0]!.column = 21;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), outsideHit));

  const fabricatedHit = cloneOutput();
  fabricatedHit.view!.hits[0]!.hitPosition.x += 0.01;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), fabricatedHit));

  const wrongOrder = cloneOutput();
  wrongOrder.view!.hits.reverse();
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongOrder));

  const skippedPalette = cloneOutput();
  skippedPalette.view!.hits[0]!.blockStateIndex = 2;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), skippedPalette));

  const unusedPalette = cloneOutput();
  unusedPalette.view!.hits[1]!.blockStateIndex = 1;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), unusedPalette));

  const wrongCrosshair = cloneOutput();
  wrongCrosshair.view!.crosshairHitIndex = 0;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongCrosshair));
});

test('validates sparse inventory ordering and bounded item metadata at the wire boundary', () => {
  const base = {
    ...output(),
    view: null,
    equipment: null,
    enderChest: null,
    vitals: null,
    movement: null,
    client: null,
    effects: null,
  };
  const item = {
    type: 'minecraft:stone',
    amount: 1,
    maxStackSize: 64,
    damage: null,
    maxDamage: null,
    unbreakable: false,
    enchantments: [],
  };
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: {
        size: 9,
        slots: [
          { slot: 2, item },
          { slot: 2, item },
        ],
      },
    }).success,
    false,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: { size: 9, slots: [{ slot: 9, item }] },
    }).success,
    false,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: { size: INT32_MAX + 1, slots: [] },
    }).success,
    false,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: { size: 9, slots: [{ slot: 0, item: { ...item, amount: INT32_MAX + 1 } }] },
    }).success,
    false,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: { size: 9, slots: [{ slot: 0, item: { ...item, damage: 1, maxDamage: null } }] },
    }).success,
    true,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: { size: 9, slots: [{ slot: 0, item: { ...item, damage: -1 } }] },
    }).success,
    false,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: { size: 9, slots: [{ slot: 0, item: { ...item, maxDamage: 0 } }] },
    }).success,
    false,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: {
        size: 9,
        slots: [
          {
            slot: 0,
            item: {
              ...item,
              enchantments: [
                { type: 'minecraft:unbreaking', level: 1 },
                { type: 'minecraft:efficiency', level: 1 },
              ],
            },
          },
        ],
      },
    }).success,
    false,
  );
});

test('rejects unsorted or duplicate active effects at the wire boundary', () => {
  const base = {
    ...output(),
    view: null,
    equipment: null,
    effects: [
      {
        type: 'minecraft:speed',
        amplifier: 0,
        durationTicks: 100,
        ambient: false,
        particles: true,
        icon: true,
      },
      {
        type: 'minecraft:night_vision',
        amplifier: 0,
        durationTicks: 100,
        ambient: false,
        particles: true,
        icon: true,
      },
    ],
  };
  assert.equal(PlayerContextOutputSchema.safeParse(base).success, false);
  base.effects[1]!.type = 'minecraft:speed';
  assert.equal(PlayerContextOutputSchema.safeParse(base).success, false);
});
