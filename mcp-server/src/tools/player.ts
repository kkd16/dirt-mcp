import type { McpServer } from '@modelcontextprotocol/server';
import { isDeepStrictEqual } from 'node:util';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES, PlayerSelectorSchema } from '../bridge/contract.ts';
import type { components } from '../generated/openapi.ts';
import type { DirtLogger } from '../logging.ts';
import {
  BlockPositionSchema,
  ExactPositionSchema,
  NonnegativeInt32Schema,
  NonBlankStringSchema,
  PlayerIdentitySchema,
  PositiveInt32Schema,
  READ_WORLD_ANNOTATIONS,
  RotationSchema,
  SignedInt32Schema,
  UnitVectorSchema,
  matchesPlayerSelector,
} from './common.ts';
import type { McpToolConfiguration } from './configuration.ts';
import { executeToolCall, successResult } from './execution.ts';

const DEFAULT_INCLUDE = {
  equipment: true,
  inventory: false,
  enderChest: false,
  vitals: false,
  movement: false,
  client: false,
  effects: false,
} as const;

const PlayerInputSelectorSchema = PlayerSelectorSchema.describe(
  'Case-insensitive exact online player name (available from get_server_status with include.players=true when enabled) or canonical UUID.',
);

function hasStrictlyIncreasingTypes(entries: readonly { readonly type: string }[]): boolean {
  return entries.every((entry, index) => index === 0 || entries[index - 1]!.type < entry.type);
}

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
    player: PlayerInputSelectorSchema,
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
      .refine(hasStrictlyIncreasingTypes, 'Enchantments must have distinct types in strictly increasing order.')
      .describe('Enchantments sorted by type.'),
  })
  .strict()
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
  .superRefine(({ size, slots }, context) => {
    for (const [index, entry] of slots.entries()) {
      if (entry.slot >= size) {
        context.addIssue({
          code: 'custom',
          message: 'Inventory slot must be less than the container size.',
          path: ['slots', index, 'slot'],
        });
      }
      if (index > 0 && slots[index - 1]!.slot >= entry.slot) {
        context.addIssue({
          code: 'custom',
          message: 'Inventory slots must be distinct and strictly increasing.',
          path: ['slots', index, 'slot'],
        });
      }
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

const PlayerEffectsSchema = z
  .array(PlayerEffectSchema)
  .refine(hasStrictlyIncreasingTypes, 'Effects must have distinct types in strictly increasing order.');

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
  .superRefine(({ equipment, inventory }, context) => {
    if (equipment === null || inventory === null) return;
    const selectedItem = inventory.slots.find(({ slot }) => slot === equipment.selectedHotbarSlot)?.item ?? null;
    if (!isDeepStrictEqual(equipment.mainHand, selectedItem)) {
      context.addIssue({
        code: 'custom',
        message: 'Main-hand equipment must equal the selected inventory item.',
        path: ['equipment', 'mainHand'],
      });
    }
  })
  .describe(
    'One coherent Paper main-thread capture; requested sections are non-null and excluded sections are null.',
  ) satisfies z.ZodType<components['schemas']['GetPlayerContextResponse']>;

export function playerContextBridgeOutputSchema(request: components['schemas']['GetPlayerContextRequest']) {
  return PlayerContextOutputSchema.superRefine((response, context) => {
    if (!matchesPlayerSelector(response.player, request.player)) {
      context.addIssue({
        code: 'custom',
        message: 'The response player must match the requested player selector.',
        path: ['player'],
      });
    }

    const sections = [
      ['equipment', request.include.equipment],
      ['inventory', request.include.inventory],
      ['enderChest', request.include.enderChest],
      ['vitals', request.include.vitals],
      ['movement', request.include.movement],
      ['client', request.include.client],
      ['effects', request.include.effects],
    ] as const;
    for (const [section, included] of sections) {
      if ((response[section] !== null) !== included) {
        context.addIssue({
          code: 'custom',
          message: `${section} must be non-null exactly when requested.`,
          path: [section],
        });
      }
    }
  });
}

export function registerPlayerTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  if (toolConfiguration.get_player_context) {
    server.registerTool(
      'get_player_context',
      {
        title: 'Get player context',
        description:
          "Capture an online player's position, orientation, pose, and selected optional state. Player names are matched exactly but case-insensitively; UUID selectors are also accepted.",
        inputSchema: GetPlayerContextInputSchema,
        outputSchema: PlayerContextOutputSchema,
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
            const request: components['schemas']['GetPlayerContextRequest'] = input;
            const result = await bridge.request(
              BRIDGE_ROUTES.getPlayerContext,
              callId,
              playerContextBridgeOutputSchema(request),
              request,
              context.mcpReq.signal,
            );
            return successResult(
              result,
              `${result.player.name} in ${result.world} at ${result.feetPosition.x}, ${result.feetPosition.y}, ${result.feetPosition.z}.`,
            );
          },
        ),
    );
  }
}
