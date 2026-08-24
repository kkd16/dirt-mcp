import assert from 'node:assert/strict';
import test from 'node:test';
import { GetPlayerContextInputSchema, PlayerContextOutputSchema } from '../dist/tools/player.js';

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
});

test('player context output decodes shape without reproducing Paper state correlations', () => {
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
});
