import assert from 'node:assert/strict';
import test from 'node:test';
import {
  GetPlayerContextInputSchema,
  PlayerContextOutputSchema,
  playerContextBridgeOutputSchema,
} from '../dist/tools/player.js';

test('player context input materializes every include flag', () => {
  const parsed = GetPlayerContextInputSchema.parse({ player: 'Builder' });
  assert.deepEqual(parsed.include, {
    equipment: true,
    inventory: false,
    enderChest: false,
    vitals: false,
    movement: false,
    client: false,
    effects: false,
  });
  assert.equal(GetPlayerContextInputSchema.safeParse({ player: 'x'.repeat(37) }).success, false);
  assert.equal(GetPlayerContextInputSchema.safeParse({ player: '🧱'.repeat(36) }).success, true);
  assert.equal(GetPlayerContextInputSchema.safeParse({ player: '🧱'.repeat(37) }).success, false);
});

test('player context output decodes shape without recomputing Paper positions and vectors', () => {
  const output = {
    capturedAt: '2026-08-23T12:00:00Z',
    player: { name: 'Builder', uuid: '11111111-1111-4111-8111-111111111111' },
    world: 'world',
    worldId: '22222222-2222-4222-8222-222222222222',
    gameMode: 'creative',
    feetPosition: { x: 1.5, y: 64, z: 1.5 },
    blockPosition: { x: 999, y: 999, z: 999 },
    eyePosition: { x: 1.5, y: 65.62, z: 1.5 },
    rotation: { yaw: 0, pitch: 0 },
    lookDirection: { x: 4, y: 5, z: 6 },
    pose: 'standing',
    onGround: true,
    equipment: null,
    inventory: null,
    enderChest: null,
    vitals: null,
    movement: null,
    client: null,
    effects: null,
  } as const;
  assert.equal(PlayerContextOutputSchema.safeParse(output).success, true);
  assert.equal(PlayerContextOutputSchema.safeParse({ ...output, equipment: undefined }).success, false);

  const request = GetPlayerContextInputSchema.parse({ player: 'builder' });
  const bridgeSchema = playerContextBridgeOutputSchema(request);
  const equipment = {
    selectedHotbarSlot: 0,
    mainHand: null,
    offHand: null,
    helmet: null,
    chestplate: null,
    leggings: null,
    boots: null,
  } as const;
  assert.equal(bridgeSchema.safeParse({ ...output, equipment }).success, true);
  assert.equal(bridgeSchema.safeParse(output).success, false);
  assert.equal(
    bridgeSchema.safeParse({
      ...output,
      player: { ...output.player, name: 'Different' },
      equipment,
    }).success,
    false,
  );
  const uuidSchema = playerContextBridgeOutputSchema({ ...request, player: output.player.uuid.toUpperCase() });
  assert.equal(uuidSchema.safeParse({ ...output, equipment }).success, true);
});

test('player collections preserve their documented deterministic ordering', () => {
  const item = {
    type: 'minecraft:diamond_pickaxe',
    amount: 1,
    maxStackSize: 1,
    damage: 0,
    maxDamage: 100,
    unbreakable: false,
    enchantments: [
      { type: 'minecraft:efficiency', level: 5 },
      { type: 'minecraft:unbreaking', level: 3 },
    ],
  } as const;
  const base = {
    capturedAt: '2026-08-23T12:00:00Z',
    player: { name: 'Builder', uuid: '11111111-1111-4111-8111-111111111111' },
    world: 'world',
    worldId: '22222222-2222-4222-8222-222222222222',
    gameMode: 'creative',
    feetPosition: { x: 1.5, y: 64, z: 1.5 },
    blockPosition: { x: 1, y: 64, z: 1 },
    eyePosition: { x: 1.5, y: 65.62, z: 1.5 },
    rotation: { yaw: 0, pitch: 0 },
    lookDirection: { x: 0, y: 0, z: 1 },
    pose: 'standing',
    onGround: true,
    equipment: null,
    inventory: {
      size: 9,
      slots: [
        { slot: 0, item },
        { slot: 8, item },
      ],
    },
    enderChest: null,
    vitals: null,
    movement: null,
    client: null,
    effects: [
      { type: 'minecraft:haste', amplifier: 0, durationTicks: 20, ambient: false, particles: true, icon: true },
      { type: 'minecraft:speed', amplifier: 0, durationTicks: 20, ambient: false, particles: true, icon: true },
    ],
  } as const;
  assert.equal(PlayerContextOutputSchema.safeParse(base).success, true);
  const equipped = {
    ...base,
    equipment: {
      selectedHotbarSlot: 0,
      mainHand: item,
      offHand: null,
      helmet: null,
      chestplate: null,
      leggings: null,
      boots: null,
    },
  } as const;
  assert.equal(PlayerContextOutputSchema.safeParse(equipped).success, true);
  assert.equal(
    PlayerContextOutputSchema.safeParse({ ...equipped, equipment: { ...equipped.equipment, mainHand: null } }).success,
    false,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...equipped,
      equipment: { ...equipped.equipment, selectedHotbarSlot: 1, mainHand: null },
    }).success,
    true,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: { ...base.inventory, slots: [base.inventory.slots[1], base.inventory.slots[0]] },
    }).success,
    false,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: { size: 8, slots: base.inventory.slots },
    }).success,
    false,
  );
  assert.equal(
    PlayerContextOutputSchema.safeParse({
      ...base,
      inventory: { size: 9, slots: [{ slot: 0, item: { ...item, enchantments: item.enchantments.toReversed() } }] },
    }).success,
    false,
  );
  assert.equal(PlayerContextOutputSchema.safeParse({ ...base, effects: base.effects.toReversed() }).success, false);
});
