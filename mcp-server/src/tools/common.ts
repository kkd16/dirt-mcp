import type { ToolAnnotations } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';

const INT32_MIN = -2_147_483_648;
export const INT32_MAX = 2_147_483_647;
export const MAX_BLOCK_STATE_PATTERNS = 64;
export const MAX_PALETTE_ENTRIES = 256;
const CANONICAL_UUID = /^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$/;

export const SignedInt32Schema = z.number().int().min(INT32_MIN).max(INT32_MAX);
export const NonnegativeInt32Schema = SignedInt32Schema.nonnegative();
export const PositiveInt32Schema = SignedInt32Schema.positive();
export const PalettePlacementSchema = z
  .tuple([NonnegativeInt32Schema, SignedInt32Schema, SignedInt32Schema, SignedInt32Schema])
  .meta({ minItems: 4, maxItems: 4, items: false })
  .describe('Exact [paletteIndex, x, y, z] tuple with origin-relative coordinates.');

export const PaletteRunSchema = z
  .tuple([
    NonnegativeInt32Schema,
    SignedInt32Schema,
    SignedInt32Schema,
    SignedInt32Schema,
    SignedInt32Schema,
    SignedInt32Schema,
    SignedInt32Schema,
  ])
  .refine(([, x, y, zCoordinate, toX, toY, toZ]) => x <= toX && y <= toY && zCoordinate <= toZ, {
    message: 'Run coordinates must form a component-wise forward cuboid.',
  })
  .meta({ minItems: 7, maxItems: 7, items: false })
  .describe('Exact [paletteIndex, x, y, z, toX, toY, toZ] inclusive origin-relative cuboid tuple.');

export const NonBlankStringSchema = z
  .string()
  .min(1)
  .refine((value) => value.trim().length > 0, 'Must contain a non-whitespace character.')
  .meta({ pattern: '.*\\S.*' });

const CanonicalUuidSchema = z.string().regex(CANONICAL_UUID);

export const PlayerIdentitySchema = z.object({ name: NonBlankStringSchema, uuid: CanonicalUuidSchema }).strict();

export function matchesPlayerSelector(player: z.infer<typeof PlayerIdentitySchema>, selector: string): boolean {
  return CanonicalUuidSchema.safeParse(selector).success
    ? player.uuid.toLowerCase() === selector.toLowerCase()
    : player.name.toLowerCase() === selector.toLowerCase();
}

export const EmptyInputSchema = z.object({}).strict().describe('No arguments.');

export const BlockPositionSchema = z
  .object({
    x: SignedInt32Schema.describe('World X block coordinate.'),
    y: SignedInt32Schema.describe('World Y block coordinate.'),
    z: SignedInt32Schema.describe('World Z block coordinate.'),
  })
  .strict()
  .describe('An absolute Minecraft block position.');

export const ExactPositionSchema = z
  .object({ x: z.number(), y: z.number(), z: z.number() })
  .strict()
  .describe('An exact finite world-space position or vector.');

export const RotationSchema = z.object({ yaw: z.number(), pitch: z.number() }).strict();

export const UnitVectorSchema = ExactPositionSchema.describe('Unit vector calculated by Paper.');

export const BoundsSchema = z
  .object({
    min: BlockPositionSchema.describe('Inclusive minimum corner after coordinate normalization.'),
    max: BlockPositionSchema.describe('Inclusive maximum corner after coordinate normalization.'),
  })
  .strict()
  .refine(
    ({ min, max }) => min.x <= max.x && min.y <= max.y && min.z <= max.z,
    'Every minimum coordinate must be less than or equal to its maximum coordinate.',
  )
  .describe('Normalized inclusive region bounds.');

type BlockPosition = z.infer<typeof BlockPositionSchema>;
export type Bounds = z.infer<typeof BoundsSchema>;

export function sameBlockPosition(left: BlockPosition, right: BlockPosition): boolean {
  return left.x === right.x && left.y === right.y && left.z === right.z;
}

export function sameBounds(left: Bounds, right: Bounds): boolean {
  return sameBlockPosition(left.min, right.min) && sameBlockPosition(left.max, right.max);
}

export function normalizedBounds(min: BlockPosition, max: BlockPosition): Bounds {
  return {
    min: { x: Math.min(min.x, max.x), y: Math.min(min.y, max.y), z: Math.min(min.z, max.z) },
    max: { x: Math.max(min.x, max.x), y: Math.max(min.y, max.y), z: Math.max(min.z, max.z) },
  };
}

export const DimensionsSchema = z
  .object({
    x: z.number().int().positive().max(Number.MAX_SAFE_INTEGER).describe('Region size along X in blocks.'),
    y: z.number().int().positive().max(Number.MAX_SAFE_INTEGER).describe('Region size along Y in blocks.'),
    z: z.number().int().positive().max(Number.MAX_SAFE_INTEGER).describe('Region size along Z in blocks.'),
  })
  .strict()
  .describe('Inclusive region dimensions in blocks.');

export const READ_WORLD_ANNOTATIONS: ToolAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: true,
};

export const NON_IDEMPOTENT_MUTATION_ANNOTATIONS: ToolAnnotations = {
  readOnlyHint: false,
  destructiveHint: true,
  idempotentHint: false,
  openWorldHint: true,
};
