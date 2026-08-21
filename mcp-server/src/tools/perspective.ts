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
const PlayerSelectorSchema = BridgePlayerSelectorSchema.describe(
  'Case-insensitive exact online player name or canonical UUID.',
);

const InputRotationSchema = z
  .object({
    yaw: z.number().describe('Paper yaw in degrees; the resolved response yaw is normalized to [-180, 180).'),
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
  .refine((value) => value % 2 === 1, 'Must be odd so the viewport has one exact crosshair ray.');

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
      z
        .object({
          type: z.literal('player'),
          player: PlayerIdentitySchema,
        })
        .strict(),
      z.object({ type: z.literal('location') }).strict(),
    ]),
    world: NonBlankStringSchema,
    worldId: z.uuid(),
    cameraPosition: ExactPositionSchema,
    rotation: RotationSchema,
    lookDirection: UnitVectorSchema,
    basis: z
      .object({ forward: UnitVectorSchema, right: UnitVectorSchema, up: UnitVectorSchema })
      .strict()
      .describe('Orthonormal camera basis resolved from rotation.'),
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
    hits: z.array(PerspectiveViewHitSchema).describe('Sparse first hits in strictly increasing row-major order.'),
    crosshairHitIndex: NonnegativeInt32Schema.nullable().describe(
      'Zero-based hits index for the center ray, or null when that ray missed.',
    ),
  })
  .strict()
  .describe('Resolved camera pose and exact sparse first Paper block-collision hits.');

type PerspectiveInput = z.infer<typeof GetPerspectiveViewInputSchema>;
type PerspectiveOutput = z.infer<typeof PerspectiveViewOutputSchema>;

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

function normalizedYaw(yaw: number): number {
  const wrapped = ((yaw % 360) + 360) % 360;
  return wrapped >= 180 ? wrapped - 360 : wrapped;
}

export function requireMatchingPerspectiveViewResponse(expected: PerspectiveInput, actual: PerspectiveOutput): void {
  if (expected.source.type === 'player') {
    if (actual.source.type !== 'player') {
      invalidBridgeResponse('Paper bridge perspective source type did not match the request.');
    }
    const selectedByUuid = isCanonicalUuid(expected.source.player);
    if (
      (selectedByUuid && actual.source.player.uuid.toLowerCase() !== expected.source.player.toLowerCase()) ||
      (!selectedByUuid && actual.source.player.name.toLowerCase() !== expected.source.player.toLowerCase())
    ) {
      invalidBridgeResponse('Paper bridge perspective player identity did not match the requested selector.');
    }
  } else {
    if (actual.source.type !== 'location') {
      invalidBridgeResponse('Paper bridge perspective source type did not match the request.');
    }
    if (actual.world !== expected.source.world || !closeVector(actual.cameraPosition, expected.source.cameraPosition)) {
      invalidBridgeResponse('Paper bridge perspective location did not match the requested camera source.');
    }
    if (
      !close(actual.rotation.yaw, normalizedYaw(expected.source.rotation.yaw), 1e-4) ||
      !close(actual.rotation.pitch, expected.source.rotation.pitch, 1e-4)
    ) {
      invalidBridgeResponse('Paper bridge perspective rotation did not match the requested camera source.');
    }
  }

  const yawRadians = (actual.rotation.yaw / 180) * Math.PI;
  const pitchRadians = (actual.rotation.pitch / 180) * Math.PI;
  const expectedLookDirection = {
    x: -Math.cos(pitchRadians) * Math.sin(yawRadians),
    y: -Math.sin(pitchRadians),
    z: Math.cos(pitchRadians) * Math.cos(yawRadians),
  };
  if (!closeVector(expectedLookDirection, actual.lookDirection)) {
    invalidBridgeResponse('Paper bridge perspective look direction did not match its rotation.');
  }

  const viewport = actual.viewport;
  if (
    viewport.width !== expected.width ||
    viewport.height !== expected.height ||
    viewport.verticalFieldOfViewDegrees !== expected.verticalFieldOfViewDegrees ||
    viewport.maxDistance !== expected.maxDistance ||
    viewport.fluidCollision !== expected.fluidCollision ||
    viewport.ignorePassableBlocks !== expected.ignorePassableBlocks
  ) {
    invalidBridgeResponse('Paper bridge perspective viewport did not match the requested options.');
  }
  const expectedHorizontalFov =
    (2 *
      Math.atan(Math.tan((expected.verticalFieldOfViewDegrees * Math.PI) / 360) * (viewport.width / viewport.height)) *
      180) /
    Math.PI;
  if (!close(viewport.horizontalFieldOfViewDegrees, expectedHorizontalFov)) {
    invalidBridgeResponse('Paper bridge returned an inconsistent horizontal field of view.');
  }

  const expectedRight = { x: -Math.cos(yawRadians), y: 0, z: -Math.sin(yawRadians) };
  const expectedUp = normalized({
    x: expectedRight.y * actual.lookDirection.z - expectedRight.z * actual.lookDirection.y,
    y: expectedRight.z * actual.lookDirection.x - expectedRight.x * actual.lookDirection.z,
    z: expectedRight.x * actual.lookDirection.y - expectedRight.y * actual.lookDirection.x,
  });
  if (
    !closeVector(actual.lookDirection, actual.basis.forward) ||
    !closeVector(expectedRight, actual.basis.right) ||
    !closeVector(expectedUp, actual.basis.up)
  ) {
    invalidBridgeResponse('Paper bridge perspective basis did not match its camera rotation.');
  }

  if (actual.hits.length > viewport.width * viewport.height) {
    invalidBridgeResponse('Paper bridge returned more perspective hits than viewport cells.');
  }
  let previousCell = -1;
  let nextPaletteIndex = 1;
  for (const hit of actual.hits) {
    const coordinateScale = Math.max(
      1,
      Math.abs(actual.cameraPosition.x),
      Math.abs(actual.cameraPosition.y),
      Math.abs(actual.cameraPosition.z),
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
      hit.blockStateIndex > actual.blockStatePalette.length
    ) {
      invalidBridgeResponse('Paper bridge returned a perspective hit outside its viewport or palette.');
    }
    const cell = hit.row * viewport.width + hit.column;
    if (cell <= previousCell) {
      invalidBridgeResponse('Paper bridge perspective hits were not in distinct row-major order.');
    }
    previousCell = cell;
    if (hit.blockStateIndex === nextPaletteIndex) nextPaletteIndex++;
    else if (hit.blockStateIndex >= nextPaletteIndex) {
      invalidBridgeResponse('Paper bridge perspective palette was not ordered by first appearance.');
    }

    const horizontalScale = (2 * (hit.column + 0.5)) / viewport.width - 1;
    const verticalScale = 1 - (2 * (hit.row + 0.5)) / viewport.height;
    const tangent = Math.tan((viewport.verticalFieldOfViewDegrees * Math.PI) / 360);
    const ray = normalized({
      x:
        actual.basis.forward.x +
        actual.basis.right.x * horizontalScale * (viewport.width / viewport.height) * tangent +
        actual.basis.up.x * verticalScale * tangent,
      y:
        actual.basis.forward.y +
        actual.basis.right.y * horizontalScale * (viewport.width / viewport.height) * tangent +
        actual.basis.up.y * verticalScale * tangent,
      z:
        actual.basis.forward.z +
        actual.basis.right.z * horizontalScale * (viewport.width / viewport.height) * tangent +
        actual.basis.up.z * verticalScale * tangent,
    });
    const expectedHitPosition = {
      x: actual.cameraPosition.x + ray.x * hit.distance,
      y: actual.cameraPosition.y + ray.y * hit.distance,
      z: actual.cameraPosition.z + ray.z * hit.distance,
    };
    const measuredDistance = Math.hypot(
      hit.hitPosition.x - actual.cameraPosition.x,
      hit.hitPosition.y - actual.cameraPosition.y,
      hit.hitPosition.z - actual.cameraPosition.z,
    );
    const hitPositionTolerance = Math.max(1e-7, coordinateRoundingTolerance);
    if (
      !close(measuredDistance, hit.distance, distanceTolerance) ||
      !closeVector(hit.hitPosition, expectedHitPosition, hitPositionTolerance)
    ) {
      invalidBridgeResponse('Paper bridge perspective hit geometry did not match its ray.');
    }
  }
  if (nextPaletteIndex - 1 !== actual.blockStatePalette.length) {
    invalidBridgeResponse('Paper bridge perspective palette contained an unused state.');
  }
  const centerRow = Math.floor(viewport.height / 2);
  const centerColumn = Math.floor(viewport.width / 2);
  const centerIndex = actual.hits.findIndex((hit) => hit.row === centerRow && hit.column === centerColumn);
  if (actual.crosshairHitIndex !== (centerIndex < 0 ? null : centerIndex)) {
    invalidBridgeResponse('Paper bridge crosshair hit did not identify the center ray.');
  }
}

export function registerPerspectiveTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  const getPerspectiveView = server.registerTool(
    'get_perspective_view',
    {
      title: 'Get perspective view',
      description:
        "Trace an odd-sized grid of exact first Paper block-collision hits from either an online player's current eye pose or an arbitrary loaded-world camera position and yaw/pitch. Player names are exact but case-insensitive. Returns the resolved camera pose, direction and basis; it excludes entities and client-only rendering or camera state.",
      inputSchema: GetPerspectiveViewInputSchema,
      outputSchema: toolOutputSchema(PerspectiveViewOutputSchema),
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
          const result = await bridge.request(
            BRIDGE_ROUTES.getPerspectiveView,
            callId,
            PerspectiveViewOutputSchema,
            input,
          );
          requireMatchingPerspectiveViewResponse(input, result);
          return successResult(
            result,
            `${result.source.type === 'player' ? result.source.player.name : 'Camera'} in ${result.world} at ${result.cameraPosition.x}, ${result.cameraPosition.y}, ${result.cameraPosition.z}; ${result.hits.length}/${result.viewport.width * result.viewport.height} rays hit blocks.`,
          );
        },
      ),
  );
  if (!toolConfiguration.get_perspective_view) getPerspectiveView.disable();
}
