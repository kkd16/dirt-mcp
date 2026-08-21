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

const eyePosition = { x: 10.25, y: 65.62, z: -2.5 };
const INT32_MAX = 2_147_483_647;

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

test('applies player context defaults and rejects removed view options', () => {
  assert.deepEqual(input(), {
    player: 'Builder',
    include: {
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
    equipment: true,
    inventory: false,
    enderChest: false,
    vitals: true,
    movement: false,
    client: false,
    effects: false,
  });
  for (const candidate of [
    { player: 'Builder', view: {} },
    { player: 'Builder', include: { view: false } },
    { player: '   ' },
  ]) {
    assert.equal(GetPlayerContextInputSchema.safeParse(candidate).success, false);
  }
});

test('accepts and correlates complete player context', () => {
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

test('accepts all bounded optional player-state sections', () => {
  const request = GetPlayerContextInputSchema.parse({
    player: 'Builder',
    include: {
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
    include: { inventory: true },
  });
  const result = cloneOutput();
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

test('rejects player identity, positioning, section, and direction mismatches', () => {
  const wrongName = cloneOutput();
  wrongName.player.name = 'SomeoneElse';
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongName));

  const wrongBlock = cloneOutput();
  wrongBlock.blockPosition.x++;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongBlock));

  const wrongSection = cloneOutput();
  wrongSection.equipment = null;
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongSection));

  const wrongLookDirection = cloneOutput();
  wrongLookDirection.lookDirection = { x: 1, y: 0, z: 0 };
  assertInvalidResponse(() => requireMatchingPlayerContextResponse(input(), wrongLookDirection));
});

test('validates sparse inventory ordering and bounded item metadata at the wire boundary', () => {
  const base = {
    ...output(),
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
