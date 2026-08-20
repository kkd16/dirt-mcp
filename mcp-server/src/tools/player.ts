import { isDeepStrictEqual } from 'node:util';
import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES, PlayerSelectorSchema as BridgePlayerSelectorSchema } from '../bridge/contract.ts';
import { toolOutputSchema } from '../bridge/errors.ts';
import type { DirtLogger } from '../logging.ts';
import { BlockPositionSchema, NonBlankStringSchema, READ_WORLD_ANNOTATIONS, SignedInt32Schema } from './common.ts';
import type { McpToolConfiguration } from './configuration.ts';
import { executeToolCall, successResult } from './execution.ts';
import { invalidBridgeResponse } from './response-validation.ts';

const DEFAULT_INCLUDE = {
  view: true,
  equipment: true,
  inventory: false,
  enderChest: false,
  vitals: false,
  movement: false,
  client: false,
  effects: false,
} as const;

const DEFAULT_VIEW = {
  width: 21,
  height: 13,
  verticalFieldOfViewDegrees: 70,
  maxDistance: 32,
  fluidCollision: 'never',
  ignorePassableBlocks: false,
} as const;

const CANONICAL_UUID_SELECTOR = /^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$/;
const CanonicalUuidSchema = z.string().regex(CANONICAL_UUID_SELECTOR);
const NonnegativeInt32Schema = SignedInt32Schema.nonnegative();
const PositiveInt32Schema = SignedInt32Schema.positive();
const PlayerSelectorSchema = BridgePlayerSelectorSchema.describe(
  'Exact online player name (available from get_server_status when enabled) or canonical UUID.',
);

const PlayerContextIncludeOptionsSchema = z
  .object({
    view: z.boolean().default(DEFAULT_INCLUDE.view).describe('Include the perspective first-block-hit grid.'),
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

const OddViewportDimensionSchema = z
  .number()
  .int()
  .min(1)
  .max(255)
  .refine((value) => value % 2 === 1, 'Must be odd so the viewport has one exact crosshair ray.');

const PlayerViewOptionsSchema = z
  .object({
    width: OddViewportDimensionSchema.default(DEFAULT_VIEW.width).describe('Odd left-to-right ray-column count.'),
    height: OddViewportDimensionSchema.default(DEFAULT_VIEW.height).describe('Odd top-to-bottom ray-row count.'),
    verticalFieldOfViewDegrees: z
      .number()
      .int()
      .min(1)
      .max(170)
      .default(DEFAULT_VIEW.verticalFieldOfViewDegrees)
      .describe('Vertical projection angle; Paper cannot observe the client FOV.'),
    maxDistance: z
      .number()
      .int()
      .min(1)
      .max(128)
      .default(DEFAULT_VIEW.maxDistance)
      .describe('Maximum Euclidean block-ray distance from the eye position.'),
    fluidCollision: z
      .enum(['never', 'source_only', 'always'])
      .default(DEFAULT_VIEW.fluidCollision)
      .describe('Which fluids participate in Paper block collision ray tracing.'),
    ignorePassableBlocks: z
      .boolean()
      .default(DEFAULT_VIEW.ignorePassableBlocks)
      .describe('Whether passable but collidable blocks are ignored.'),
  })
  .strict()
  .describe('Perspective block-collision projection options.');

export const GetPlayerContextInputSchema = z
  .object({
    player: PlayerSelectorSchema,
    include: PlayerContextIncludeOptionsSchema,
    view: PlayerViewOptionsSchema.optional(),
  })
  .strict()
  .superRefine((input, context) => {
    if (!input.include.view && input.view !== undefined) {
      context.addIssue({
        code: 'custom',
        path: ['view'],
        message: 'view must be omitted when include.view is false.',
      });
    }
  })
  .describe('Online player selector, optional sections, and bounded perspective-view options.');

const ExactPositionSchema = z
  .object({ x: z.number(), y: z.number(), z: z.number() })
  .strict()
  .describe('Exact world-space position or vector.');

const UnitVectorSchema = ExactPositionSchema.refine(
  (vector) => Math.abs(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z - 1) <= 1e-6,
  'Vector must have unit length.',
);

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

const PlayerViewHitSchema = z
  .object({
    row: NonnegativeInt32Schema,
    column: NonnegativeInt32Schema,
    blockStateIndex: PositiveInt32Schema.describe('One-based blockStatePalette index.'),
    blockPosition: BlockPositionSchema,
    hitPosition: ExactPositionSchema,
    face: z.enum(['up', 'down', 'north', 'east', 'south', 'west']).nullable(),
    distance: z.number().nonnegative().describe('Euclidean ray distance from eyePosition.'),
  })
  .strict();

const PlayerPerspectiveViewSchema = z
  .object({
    basis: z
      .object({ forward: UnitVectorSchema, right: UnitVectorSchema, up: UnitVectorSchema })
      .strict()
      .describe("Orthonormal camera basis captured from the player's eye pose."),
    viewport: z
      .object({
        width: OddViewportDimensionSchema,
        height: OddViewportDimensionSchema,
        verticalFieldOfViewDegrees: z.number().int().min(1).max(170),
        horizontalFieldOfViewDegrees: z.number().gt(0).lt(180),
        maxDistance: z.number().int().min(1).max(128),
        fluidCollision: z.enum(['never', 'source_only', 'always']),
        ignorePassableBlocks: z.boolean(),
      })
      .strict(),
    checkedChunkCount: NonnegativeInt32Schema.describe(
      'Exact size of the conservative loaded-chunk preflight for the sampled rays.',
    ),
    blockStatePalette: z
      .array(NonBlankStringSchema)
      .refine((states) => new Set(states).size === states.length)
      .describe('Unique block states ordered by first appearance in hits.'),
    hits: z.array(PlayerViewHitSchema).describe('Sparse first hits in strictly increasing row-major order.'),
    crosshairHitIndex: NonnegativeInt32Schema.nullable().describe(
      'Zero-based hits index for the center ray, or null when that ray missed.',
    ),
  })
  .strict()
  .describe(
    'Exact first Paper block-collision hits for viewport width times height sampled rays; misses are omitted; not a client framebuffer.',
  );

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
    player: z.object({ name: NonBlankStringSchema, uuid: CanonicalUuidSchema }).strict(),
    world: NonBlankStringSchema,
    worldId: z.uuid(),
    gameMode: z.enum(['survival', 'creative', 'adventure', 'spectator']),
    feetPosition: ExactPositionSchema,
    blockPosition: BlockPositionSchema,
    eyePosition: ExactPositionSchema,
    rotation: z.object({ yaw: z.number(), pitch: z.number() }).strict(),
    lookDirection: UnitVectorSchema,
    pose: z.enum(PLAYER_POSES),
    onGround: z.boolean(),
    view: PlayerPerspectiveViewSchema.nullable(),
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
type PlayerViewOptions = z.infer<typeof PlayerViewOptionsSchema>;

function resolvedView(input: GetPlayerContextInput): PlayerViewOptions {
  return PlayerViewOptionsSchema.parse(input.view ?? {});
}

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

function normalized(vector: { readonly x: number; readonly y: number; readonly z: number }) {
  const length = Math.hypot(vector.x, vector.y, vector.z);
  return { x: vector.x / length, y: vector.y / length, z: vector.z / length };
}

export function requireMatchingPlayerContextResponse(
  expected: GetPlayerContextInput,
  actual: PlayerContextOutput,
): void {
  const selectedByUuid = CANONICAL_UUID_SELECTOR.test(expected.player);
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

  for (const section of [
    'view',
    'equipment',
    'inventory',
    'enderChest',
    'vitals',
    'movement',
    'client',
    'effects',
  ] as const) {
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
  if (actual.view === null) return;

  const requestedView = resolvedView(expected);
  const viewport = actual.view.viewport;
  if (
    viewport.width !== requestedView.width ||
    viewport.height !== requestedView.height ||
    viewport.verticalFieldOfViewDegrees !== requestedView.verticalFieldOfViewDegrees ||
    viewport.maxDistance !== requestedView.maxDistance ||
    viewport.fluidCollision !== requestedView.fluidCollision ||
    viewport.ignorePassableBlocks !== requestedView.ignorePassableBlocks
  ) {
    invalidBridgeResponse('Paper bridge player viewport did not match the requested view options.');
  }

  const expectedHorizontalFov =
    (2 *
      Math.atan(
        Math.tan((requestedView.verticalFieldOfViewDegrees * Math.PI) / 360) * (viewport.width / viewport.height),
      ) *
      180) /
    Math.PI;
  if (Math.abs(viewport.horizontalFieldOfViewDegrees - expectedHorizontalFov) > 1e-9) {
    invalidBridgeResponse('Paper bridge returned an inconsistent horizontal field of view.');
  }
  const expectedRight = { x: -Math.cos(yawRadians), y: 0, z: -Math.sin(yawRadians) };
  const expectedUp = normalized({
    x: expectedRight.y * actual.lookDirection.z - expectedRight.z * actual.lookDirection.y,
    y: expectedRight.z * actual.lookDirection.x - expectedRight.x * actual.lookDirection.z,
    z: expectedRight.x * actual.lookDirection.y - expectedRight.y * actual.lookDirection.x,
  });
  if (
    !closeVector(actual.lookDirection, actual.view.basis.forward) ||
    !closeVector(expectedRight, actual.view.basis.right) ||
    !closeVector(expectedUp, actual.view.basis.up)
  ) {
    invalidBridgeResponse('Paper bridge view basis did not match the captured player rotation.');
  }

  const expectedRayCount = viewport.width * viewport.height;
  if (actual.view.hits.length > expectedRayCount) {
    invalidBridgeResponse('Paper bridge returned more player-view hits than viewport cells.');
  }

  let previousCell = -1;
  let nextPaletteIndex = 1;
  for (const hit of actual.view.hits) {
    const coordinateScale = Math.max(
      1,
      Math.abs(actual.eyePosition.x),
      Math.abs(actual.eyePosition.y),
      Math.abs(actual.eyePosition.z),
      Math.abs(hit.hitPosition.x),
      Math.abs(hit.hitPosition.y),
      Math.abs(hit.hitPosition.z),
    );
    const coordinateRoundingTolerance = 8 * Number.EPSILON * coordinateScale;
    const distanceTolerance = Math.max(
      1e-9 * Math.max(1, viewport.maxDistance, hit.distance),
      coordinateRoundingTolerance,
    );
    if (
      hit.row >= viewport.height ||
      hit.column >= viewport.width ||
      hit.distance > viewport.maxDistance + distanceTolerance ||
      hit.blockStateIndex > actual.view.blockStatePalette.length
    ) {
      invalidBridgeResponse('Paper bridge returned a player-view hit outside its viewport or palette.');
    }
    const cell = hit.row * viewport.width + hit.column;
    if (cell <= previousCell) {
      invalidBridgeResponse('Paper bridge player-view hits were not in distinct row-major order.');
    }
    previousCell = cell;
    if (hit.blockStateIndex === nextPaletteIndex) nextPaletteIndex++;
    else if (hit.blockStateIndex >= nextPaletteIndex) {
      invalidBridgeResponse('Paper bridge player-view palette was not ordered by first appearance.');
    }

    const horizontalScale = (2 * (hit.column + 0.5)) / viewport.width - 1;
    const verticalScale = 1 - (2 * (hit.row + 0.5)) / viewport.height;
    const tangent = Math.tan((viewport.verticalFieldOfViewDegrees * Math.PI) / 360);
    const ray = normalized({
      x:
        actual.view.basis.forward.x +
        actual.view.basis.right.x * horizontalScale * (viewport.width / viewport.height) * tangent +
        actual.view.basis.up.x * verticalScale * tangent,
      y:
        actual.view.basis.forward.y +
        actual.view.basis.right.y * horizontalScale * (viewport.width / viewport.height) * tangent +
        actual.view.basis.up.y * verticalScale * tangent,
      z:
        actual.view.basis.forward.z +
        actual.view.basis.right.z * horizontalScale * (viewport.width / viewport.height) * tangent +
        actual.view.basis.up.z * verticalScale * tangent,
    });
    const expectedHitPosition = {
      x: actual.eyePosition.x + ray.x * hit.distance,
      y: actual.eyePosition.y + ray.y * hit.distance,
      z: actual.eyePosition.z + ray.z * hit.distance,
    };
    const measuredDistance = Math.hypot(
      hit.hitPosition.x - actual.eyePosition.x,
      hit.hitPosition.y - actual.eyePosition.y,
      hit.hitPosition.z - actual.eyePosition.z,
    );
    const hitPositionTolerance = Math.max(1e-7, coordinateRoundingTolerance);
    if (
      !close(measuredDistance, hit.distance, distanceTolerance) ||
      !closeVector(hit.hitPosition, expectedHitPosition, hitPositionTolerance)
    ) {
      invalidBridgeResponse('Paper bridge player-view hit geometry did not match its ray.');
    }
  }
  if (nextPaletteIndex - 1 !== actual.view.blockStatePalette.length) {
    invalidBridgeResponse('Paper bridge player-view palette contained an unused state.');
  }

  const centerRow = Math.floor(viewport.height / 2);
  const centerColumn = Math.floor(viewport.width / 2);
  const centerIndex = actual.view.hits.findIndex((hit) => hit.row === centerRow && hit.column === centerColumn);
  if (actual.view.crosshairHitIndex !== (centerIndex < 0 ? null : centerIndex)) {
    invalidBridgeResponse('Paper bridge crosshair hit did not identify the center ray.');
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
        "Capture an online player's exact feet and eye positions, orientation, pose, and optional equipment, inventories, vitals, movement, client settings, effects, and bounded sampled first-block perspective view. The default view traces Paper block collisions from the captured eye pose; it is not a client framebuffer and excludes entities and client-only presentation or camera state.",
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
          const viewSummary =
            result.view === null
              ? 'view omitted'
              : `${result.view.hits.length}/${result.view.viewport.width * result.view.viewport.height} view rays hit blocks`;
          return successResult(
            result,
            `${result.player.name} in ${result.world} at ${result.feetPosition.x}, ${result.feetPosition.y}, ${result.feetPosition.z}; ${viewSummary}.`,
          );
        },
      ),
  );
  if (!toolConfiguration.get_player_context) getPlayerContext.disable();
}
