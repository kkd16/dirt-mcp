import type { McpServer } from '@modelcontextprotocol/server';
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
  UnitVectorSchema,
  matchesPlayerSelector,
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

const PlayerInputSelectorSchema = PlayerSelectorSchema.describe(
  'Case-insensitive exact online player name or canonical UUID.',
);

const InputRotationSchema = z
  .object({
    yaw: z.number().describe('Paper yaw in degrees.'),
    pitch: z.number().min(-90).max(90).describe('Paper pitch in degrees; positive values look downward.'),
  })
  .strict();

const PerspectiveSourceSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('player'), player: PlayerInputSelectorSchema }).strict(),
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
  .superRefine((response, context) => {
    let previousRayIndex = -1;
    let highestFirstSeenPaletteIndex = 0;
    for (const [index, hit] of response.hits.entries()) {
      if (hit.row >= response.viewport.height) {
        context.addIssue({
          code: 'custom',
          message: 'Hit row must be inside the resolved viewport.',
          path: ['hits', index, 'row'],
        });
      }
      if (hit.column >= response.viewport.width) {
        context.addIssue({
          code: 'custom',
          message: 'Hit column must be inside the resolved viewport.',
          path: ['hits', index, 'column'],
        });
      }
      const rayIndex = hit.row * response.viewport.width + hit.column;
      if (rayIndex <= previousRayIndex) {
        context.addIssue({
          code: 'custom',
          message: 'Hits must be in strictly increasing row-major order.',
          path: ['hits', index],
        });
      }
      previousRayIndex = rayIndex;

      if (hit.blockStateIndex > response.blockStatePalette.length) {
        context.addIssue({
          code: 'custom',
          message: 'Hit block-state index must reference the response palette.',
          path: ['hits', index, 'blockStateIndex'],
        });
      } else if (hit.blockStateIndex > highestFirstSeenPaletteIndex) {
        if (hit.blockStateIndex !== highestFirstSeenPaletteIndex + 1) {
          context.addIssue({
            code: 'custom',
            message: 'Block-state palette entries must be ordered by first appearance in hits.',
            path: ['hits', index, 'blockStateIndex'],
          });
        }
        highestFirstSeenPaletteIndex = hit.blockStateIndex;
      }
      if (hit.distance > response.viewport.maxDistance) {
        context.addIssue({
          code: 'custom',
          message: 'Hit distance must not exceed the resolved maximum distance.',
          path: ['hits', index, 'distance'],
        });
      }
    }
    if (highestFirstSeenPaletteIndex !== response.blockStatePalette.length) {
      context.addIssue({
        code: 'custom',
        message: 'Every block-state palette entry must be referenced by a hit.',
        path: ['blockStatePalette'],
      });
    }

    const centerRow = Math.floor(response.viewport.height / 2);
    const centerColumn = Math.floor(response.viewport.width / 2);
    const centerHitIndex = response.hits.findIndex((hit) => hit.row === centerRow && hit.column === centerColumn);
    const expectedCrosshairHitIndex = centerHitIndex === -1 ? null : centerHitIndex;
    if (response.crosshairHitIndex !== expectedCrosshairHitIndex) {
      context.addIssue({
        code: 'custom',
        message: 'The crosshair hit index must identify the center-ray hit, or be null when it missed.',
        path: ['crosshairHitIndex'],
      });
    }
  })
  .describe('Resolved camera pose and sparse first Paper block-collision hits.') satisfies z.ZodType<
  components['schemas']['GetPerspectiveViewResponse']
>;

export function perspectiveViewBridgeOutputSchema(request: components['schemas']['GetPerspectiveViewRequest']) {
  return PerspectiveViewOutputSchema.superRefine((response, context) => {
    const viewportFields = [
      'width',
      'height',
      'verticalFieldOfViewDegrees',
      'maxDistance',
      'fluidCollision',
      'ignorePassableBlocks',
    ] as const;
    for (const field of viewportFields) {
      if (response.viewport[field] !== request[field]) {
        context.addIssue({
          code: 'custom',
          message: `The resolved ${field} must match the request.`,
          path: ['viewport', field],
        });
      }
    }

    if (response.source.type !== request.source.type) {
      context.addIssue({
        code: 'custom',
        message: 'The resolved source type must match the request.',
        path: ['source', 'type'],
      });
      return;
    }
    if (request.source.type === 'location') {
      if (response.world !== request.source.world) {
        context.addIssue({
          code: 'custom',
          message: 'The response world must match the camera world.',
          path: ['world'],
        });
      }
      if (
        response.cameraPosition.x !== request.source.cameraPosition.x ||
        response.cameraPosition.y !== request.source.cameraPosition.y ||
        response.cameraPosition.z !== request.source.cameraPosition.z
      ) {
        context.addIssue({
          code: 'custom',
          message: 'The resolved camera position must match the requested camera position.',
          path: ['cameraPosition'],
        });
      }
      const expectedRotation = resolvedLocationRotation(request.source.rotation);
      if (response.rotation.yaw !== expectedRotation.yaw || response.rotation.pitch !== expectedRotation.pitch) {
        context.addIssue({
          code: 'custom',
          message: 'The resolved rotation must match Paper normalization of the requested camera rotation.',
          path: ['rotation'],
        });
      }
      return;
    }

    if (response.source.type === 'player') {
      if (!matchesPlayerSelector(response.source.player, request.source.player)) {
        context.addIssue({
          code: 'custom',
          message: 'The resolved player must match the requested player selector.',
          path: ['source', 'player'],
        });
      }
    }
  });
}

function resolvedLocationRotation(rotation: components['schemas']['Rotation']): z.infer<typeof RotationSchema> {
  let yaw = rotation.yaw % 360;
  if (yaw >= 180) yaw -= 360;
  else if (yaw < -180) yaw += 360;
  yaw = Math.fround(yaw);
  if (yaw >= 180) yaw = -180;
  return { yaw: yaw === 0 ? 0 : yaw, pitch: Math.fround(rotation.pitch) };
}

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
              perspectiveViewBridgeOutputSchema(request),
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
