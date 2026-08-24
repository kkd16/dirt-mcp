import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import type { components } from '../generated/openapi.ts';
import type { DirtLogger } from '../logging.ts';
import {
  BlockPositionSchema,
  ExactPositionSchema,
  NonBlankStringSchema,
  PlayerIdentitySchema,
  READ_WORLD_ANNOTATIONS,
  RotationSchema,
  SignedInt32Schema,
  UnitVectorSchema,
} from './common.ts';
import type { McpToolConfiguration } from './configuration.ts';
import { executeToolCall, successResult } from './execution.ts';

const DEFAULT_VIEW = {
  width: 21,
  height: 13,
  verticalFieldOfViewDegrees: 70,
  maxDistance: 32,
  fluidCollision: 'never',
  ignorePassableBlocks: false,
} as const;

const NonnegativeInt32Schema = SignedInt32Schema.nonnegative();
const PositiveInt32Schema = SignedInt32Schema.positive();
const PlayerSelectorSchema = NonBlankStringSchema.max(36).describe(
  'Case-insensitive exact online player name or canonical UUID.',
);

const InputRotationSchema = z
  .object({
    yaw: z.number().describe('Paper yaw in degrees.'),
    pitch: z.number().min(-90).max(90).describe('Paper pitch in degrees; positive values look downward.'),
  })
  .strict();

const PerspectiveSourceSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('player'), player: PlayerSelectorSchema }).strict(),
  z
    .object({
      type: z.literal('location'),
      world: NonBlankStringSchema.describe('Exact name of an already-loaded Paper world.'),
      cameraPosition: ExactPositionSchema.describe('Exact camera and ray origin; no player eye height is added.'),
      rotation: InputRotationSchema,
    })
    .strict(),
]);

const OddViewportDimensionSchema = z
  .number()
  .int()
  .min(1)
  .max(255)
  .refine((value) => value % 2 === 1, 'Must be odd so the viewport has one exact crosshair ray.')
  .meta({ not: { multipleOf: 2 } });

export const GetPerspectiveViewInputSchema = z
  .object({
    source: PerspectiveSourceSchema,
    width: OddViewportDimensionSchema.default(DEFAULT_VIEW.width).describe('Odd left-to-right ray-column count.'),
    height: OddViewportDimensionSchema.default(DEFAULT_VIEW.height).describe('Odd top-to-bottom ray-row count.'),
    verticalFieldOfViewDegrees: z
      .number()
      .int()
      .min(1)
      .max(170)
      .default(DEFAULT_VIEW.verticalFieldOfViewDegrees)
      .describe('Vertical projection angle; Paper cannot observe client FOV.'),
    maxDistance: z
      .number()
      .int()
      .min(1)
      .max(128)
      .default(DEFAULT_VIEW.maxDistance)
      .describe('Maximum Euclidean block-ray distance from cameraPosition.'),
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
  .describe('Real-player or arbitrary camera source and bounded perspective projection options.');

const PerspectiveViewHitSchema = z
  .object({
    row: NonnegativeInt32Schema,
    column: NonnegativeInt32Schema,
    blockStateIndex: PositiveInt32Schema.describe('One-based blockStatePalette index.'),
    blockPosition: BlockPositionSchema,
    hitPosition: ExactPositionSchema,
    face: z.enum(['up', 'down', 'north', 'east', 'south', 'west']).nullable(),
    distance: z.number().nonnegative().describe('Euclidean ray distance from cameraPosition.'),
  })
  .strict();

export const PerspectiveViewOutputSchema = z
  .object({
    capturedAt: z.iso.datetime({ offset: true }),
    source: z.discriminatedUnion('type', [
      z.object({ type: z.literal('player'), player: PlayerIdentitySchema }).strict(),
      z.object({ type: z.literal('location') }).strict(),
    ]),
    world: NonBlankStringSchema,
    worldId: z.uuid(),
    cameraPosition: ExactPositionSchema,
    rotation: RotationSchema,
    lookDirection: UnitVectorSchema,
    basis: z.object({ forward: UnitVectorSchema, right: UnitVectorSchema, up: UnitVectorSchema }).strict(),
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
    checkedChunkCount: NonnegativeInt32Schema,
    blockStatePalette: z
      .array(NonBlankStringSchema)
      .refine((states) => new Set(states).size === states.length, 'Block-state palette entries must be distinct.')
      .meta({ uniqueItems: true }),
    hits: z.array(PerspectiveViewHitSchema),
    crosshairHitIndex: NonnegativeInt32Schema.nullable(),
  })
  .strict()
  .describe('Resolved camera pose and sparse first Paper block-collision hits.') satisfies z.ZodType<
  components['schemas']['GetPerspectiveViewResponse']
>;

export function registerPerspectiveTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  if (toolConfiguration.get_perspective_view) {
    server.registerTool(
      'get_perspective_view',
      {
        title: 'Get perspective view',
        description:
          "Trace a grid of first block-collision hits from an online player's eye pose or a loaded-world camera position.",
        inputSchema: GetPerspectiveViewInputSchema,
        outputSchema: PerspectiveViewOutputSchema,
        annotations: READ_WORLD_ANNOTATIONS,
      },
      async (input, context) =>
        executeToolCall(
          logger,
          {
            operation: 'get_perspective_view',
            context,
            failureContext:
              input.source.type === 'player'
                ? `Could not get perspective for player ${input.source.player}`
                : `Could not get perspective in world ${input.source.world}`,
          },
          async (callId) => {
            const request: components['schemas']['GetPerspectiveViewRequest'] = input;
            const result = await bridge.request(
              BRIDGE_ROUTES.getPerspectiveView,
              callId,
              PerspectiveViewOutputSchema,
              request,
              context.mcpReq.signal,
            );
            return successResult(
              result,
              `${result.source.type === 'player' ? result.source.player.name : 'Camera'} in ${result.world}; ${result.hits.length}/${result.viewport.width * result.viewport.height} rays hit blocks.`,
            );
          },
        ),
    );
  }
}
