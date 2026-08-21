import { isDeepStrictEqual } from 'node:util';
import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES, PlayerSelectorSchema as BridgePlayerSelectorSchema } from '../bridge/contract.ts';
import { toolOutputSchema } from '../bridge/errors.ts';
import type { DirtLogger } from '../logging.ts';
import {
  BlockPositionSchema,
  ExactPositionSchema,
  isCanonicalUuid,
  NonBlankStringSchema,
  PlayerIdentitySchema,
  READ_WORLD_ANNOTATIONS,
  RotationSchema,
  SignedInt32Schema,
  UnitVectorSchema,
} from './common.ts';
import type { McpToolConfiguration } from './configuration.ts';
import { executeToolCall, successResult } from './execution.ts';
import { invalidBridgeResponse } from './response-validation.ts';

const DEFAULT_INCLUDE = {
  equipment: true,
  inventory: false,
  enderChest: false,
  vitals: false,
  movement: false,
  client: false,
  effects: false,
} as const;

const NonnegativeInt32Schema = SignedInt32Schema.nonnegative();
const PositiveInt32Schema = SignedInt32Schema.positive();
const PlayerSelectorSchema = BridgePlayerSelectorSchema.describe(
  'Case-insensitive exact online player name (available from get_server_status with include.players=true when enabled) or canonical UUID.',
);

const PlayerContextIncludeOptionsSchema = z
  .object({
    equipment: z
      .boolean()
      .default(DEFAULT_INCLUDE.equipment)
      .describe('Include selected hotbar slot, held items, and armor.'),
    inventory: z
      .boolean()
      .default(DEFAULT_INCLUDE.inventory)
      .describe('Include occupied player storage slots, excluding equipment and off-hand.'),
    enderChest: z.boolean().default(DEFAULT_INCLUDE.enderChest).describe('Include occupied ender-chest slots.'),
    vitals: z
      .boolean()
      .default(DEFAULT_INCLUDE.vitals)
      .describe('Include health, food, air, experience, fire, and freeze state.'),
    movement: z
      .boolean()
      .default(DEFAULT_INCLUDE.movement)
      .describe('Include velocity, flight state, and movement/posture flags.'),
    client: z
      .boolean()
      .default(DEFAULT_INCLUDE.client)
      .describe('Include ping, locale, and client/server view distances.'),
    effects: z.boolean().default(DEFAULT_INCLUDE.effects).describe('Include sorted active potion effects.'),
  })
  .strict()
  .default(DEFAULT_INCLUDE)
  .describe('Optional response sections; exact identity and positioning are always returned.');

export const GetPlayerContextInputSchema = z
  .object({
    player: PlayerSelectorSchema,
    include: PlayerContextIncludeOptionsSchema,
  })
  .strict()
  .describe('Online player selector and optional context sections.');

const PlayerItemStackSchema = z
  .object({
    type: NonBlankStringSchema.describe('Namespaced item type.'),
    amount: PositiveInt32Schema,
    maxStackSize: PositiveInt32Schema,
    damage: NonnegativeInt32Schema.nullable(),
    maxDamage: PositiveInt32Schema.nullable(),
    unbreakable: z.boolean(),
    enchantments: z
      .array(
        z
          .object({ type: NonBlankStringSchema.describe('Namespaced enchantment type.'), level: SignedInt32Schema })
          .strict(),
      )
      .describe('Enchantments sorted by type.'),
  })
  .strict()
  .superRefine((item, context) => {
    for (let index = 1; index < item.enchantments.length; index++) {
      if (item.enchantments[index]!.type <= item.enchantments[index - 1]!.type) {
        context.addIssue({
          code: 'custom',
          path: ['enchantments', index, 'type'],
          message: 'Enchantments must have distinct types in strictly increasing order.',
        });
      }
    }
  })
  .meta({ id: 'PlayerItemStack' });

const PlayerEquipmentSchema = z
  .object({
    selectedHotbarSlot: z.number().int().min(0).max(8),
    mainHand: PlayerItemStackSchema.nullable(),
    offHand: PlayerItemStackSchema.nullable(),
    helmet: PlayerItemStackSchema.nullable(),
    chestplate: PlayerItemStackSchema.nullable(),
    leggings: PlayerItemStackSchema.nullable(),
    boots: PlayerItemStackSchema.nullable(),
  })
  .strict();

const PlayerInventorySchema = z
  .object({
    size: PositiveInt32Schema,
    slots: z.array(z.object({ slot: NonnegativeInt32Schema, item: PlayerItemStackSchema }).strict()),
  })
  .strict()
  .superRefine((inventory, context) => {
    let previousSlot = -1;
    for (const [index, entry] of inventory.slots.entries()) {
      if (entry.slot >= inventory.size) {
        context.addIssue({ code: 'custom', path: ['slots', index, 'slot'], message: 'Slot must be below size.' });
      }
      if (entry.slot <= previousSlot) {
        context.addIssue({
          code: 'custom',
          path: ['slots', index, 'slot'],
          message: 'Occupied slots must be distinct and strictly increasing.',
        });
      }
      previousSlot = entry.slot;
    }
  })
  .meta({ id: 'PlayerInventory' });

const PlayerVitalsSchema = z
  .object({
    health: z.number().nonnegative(),
    maxHealth: z.number().positive(),
    absorptionAmount: z.number().nonnegative(),
    foodLevel: SignedInt32Schema,
    saturation: z.number(),
    exhaustion: z.number(),
    remainingAir: SignedInt32Schema,
    maximumAir: SignedInt32Schema,
    experienceLevel: NonnegativeInt32Schema,
    experienceProgress: z.number().min(0).max(1),
    calculatedExperiencePoints: SignedInt32Schema.describe(
      'Total points calculated from the current experience level and progress.',
    ),
    fireTicks: SignedInt32Schema,
    freezeTicks: NonnegativeInt32Schema,
  })
  .strict();

const PlayerMovementSchema = z
  .object({
    velocity: ExactPositionSchema,
    fallDistance: z.number(),
    allowFlight: z.boolean(),
    flying: z.boolean(),
    sneaking: z.boolean(),
    sprinting: z.boolean(),
    swimming: z.boolean(),
    gliding: z.boolean(),
    sleeping: z.boolean(),
    blocking: z.boolean(),
    riptiding: z.boolean(),
  })
  .strict();

const PlayerClientSchema = z
  .object({
    pingMillis: SignedInt32Schema,
    locale: NonBlankStringSchema,
    clientViewDistance: NonnegativeInt32Schema,
    viewDistance: SignedInt32Schema.describe('Effective Paper chunk load distance for this player.'),
    sendViewDistance: SignedInt32Schema.describe('Effective Paper chunk send distance for this player.'),
  })
  .strict();

const PlayerEffectSchema = z
  .object({
    type: NonBlankStringSchema.describe('Namespaced effect type.'),
    amplifier: SignedInt32Schema,
    durationTicks: SignedInt32Schema.describe(
      "Remaining ticks; -1 is Paper's infinite-duration sentinel and plugin values may otherwise be signed.",
    ),
    ambient: z.boolean(),
    particles: z.boolean(),
    icon: z.boolean(),
  })
  .strict();

const PlayerEffectsSchema = z.array(PlayerEffectSchema).superRefine((effects, context) => {
  for (let index = 1; index < effects.length; index++) {
    if (effects[index]!.type <= effects[index - 1]!.type) {
      context.addIssue({
        code: 'custom',
        path: [index, 'type'],
        message: 'Effects must have distinct types in strictly increasing order.',
      });
    }
  }
});

const PLAYER_POSES = [
  'standing',
  'fall_flying',
  'sleeping',
  'swimming',
  'spin_attack',
  'sneaking',
  'long_jumping',
  'dying',
  'croaking',
  'using_tongue',
  'sitting',
  'roaring',
  'sniffing',
  'emerging',
  'digging',
  'sliding',
  'shooting',
  'inhaling',
] as const;

export const PlayerContextOutputSchema = z
  .object({
    capturedAt: z.iso.datetime({ offset: true }),
    player: PlayerIdentitySchema,
    world: NonBlankStringSchema,
    worldId: z.uuid(),
    gameMode: z.enum(['survival', 'creative', 'adventure', 'spectator']),
    feetPosition: ExactPositionSchema,
    blockPosition: BlockPositionSchema,
    eyePosition: ExactPositionSchema,
    rotation: RotationSchema,
    lookDirection: UnitVectorSchema,
    pose: z.enum(PLAYER_POSES),
    onGround: z.boolean(),
    equipment: PlayerEquipmentSchema.nullable(),
    inventory: PlayerInventorySchema.nullable(),
    enderChest: PlayerInventorySchema.nullable(),
    vitals: PlayerVitalsSchema.nullable(),
    movement: PlayerMovementSchema.nullable(),
    client: PlayerClientSchema.nullable(),
    effects: PlayerEffectsSchema.nullable(),
  })
  .strict()
  .describe('One coherent Paper main-thread capture; requested sections are non-null and excluded sections are null.');

type GetPlayerContextInput = z.infer<typeof GetPlayerContextInputSchema>;
type PlayerContextOutput = z.infer<typeof PlayerContextOutputSchema>;

function close(left: number, right: number, tolerance = 1e-9): boolean {
  return Math.abs(left - right) <= tolerance;
}

function closeVector(
  left: { readonly x: number; readonly y: number; readonly z: number },
  right: { readonly x: number; readonly y: number; readonly z: number },
  tolerance = 1e-9,
): boolean {
  return close(left.x, right.x, tolerance) && close(left.y, right.y, tolerance) && close(left.z, right.z, tolerance);
}

export function requireMatchingPlayerContextResponse(
  expected: GetPlayerContextInput,
  actual: PlayerContextOutput,
): void {
  const selectedByUuid = isCanonicalUuid(expected.player);
  if (
    (selectedByUuid && actual.player.uuid.toLowerCase() !== expected.player.toLowerCase()) ||
    (!selectedByUuid && actual.player.name.toLowerCase() !== expected.player.toLowerCase())
  ) {
    invalidBridgeResponse('Paper bridge player identity did not match the requested selector.');
  }
  if (
    actual.blockPosition.x !== Math.floor(actual.feetPosition.x) ||
    actual.blockPosition.y !== Math.floor(actual.feetPosition.y) ||
    actual.blockPosition.z !== Math.floor(actual.feetPosition.z)
  ) {
    invalidBridgeResponse('Paper bridge player block position did not match the exact feet position.');
  }
  const yawRadians = (actual.rotation.yaw / 180) * Math.PI;
  const pitchRadians = (actual.rotation.pitch / 180) * Math.PI;
  const expectedLookDirection = {
    x: -Math.cos(pitchRadians) * Math.sin(yawRadians),
    y: -Math.sin(pitchRadians),
    z: Math.cos(pitchRadians) * Math.cos(yawRadians),
  };
  if (!closeVector(expectedLookDirection, actual.lookDirection)) {
    invalidBridgeResponse('Paper bridge player look direction did not match the captured rotation.');
  }

  for (const section of ['equipment', 'inventory', 'enderChest', 'vitals', 'movement', 'client', 'effects'] as const) {
    if ((actual[section] !== null) !== expected.include[section]) {
      invalidBridgeResponse('Paper bridge player sections did not match the requested include flags.');
    }
  }
  if (actual.equipment !== null && actual.inventory !== null) {
    const equipment = actual.equipment;
    const selectedSlotItem =
      actual.inventory.slots.find((entry) => entry.slot === equipment.selectedHotbarSlot)?.item ?? null;
    if (!isDeepStrictEqual(equipment.mainHand, selectedSlotItem)) {
      invalidBridgeResponse('Paper bridge main-hand equipment did not match the selected inventory slot.');
    }
  }
}

export function registerPlayerTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  const getPlayerContext = server.registerTool(
    'get_player_context',
    {
      title: 'Get player context',
      description:
        "Capture an online player's exact feet and eye positions, orientation, pose, and optional equipment, inventories, vitals, movement, client settings, and effects. Player names are matched exactly but case-insensitively; UUID selectors are also accepted.",
      inputSchema: GetPlayerContextInputSchema,
      outputSchema: toolOutputSchema(PlayerContextOutputSchema),
      annotations: READ_WORLD_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        logger,
        {
          operation: 'get_player_context',
          context,
          failureContext: `Could not get context for player ${input.player}`,
        },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.getPlayerContext, callId, PlayerContextOutputSchema, input);
          requireMatchingPlayerContextResponse(input, result);
          return successResult(
            result,
            `${result.player.name} in ${result.world} at ${result.feetPosition.x}, ${result.feetPosition.y}, ${result.feetPosition.z}.`,
          );
        },
      ),
  );
  if (!toolConfiguration.get_player_context) getPlayerContext.disable();
}
