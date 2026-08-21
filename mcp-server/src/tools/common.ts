import type { ToolAnnotations } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';

export const INT32_MIN = -2_147_483_648;
export const INT32_MAX = 2_147_483_647;
export const MAX_BLOCK_STATE_ENTRIES = 64;
export const BLOCK_AXES = ['x', 'y', 'z'] as const;

const CANONICAL_UUID = /^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$/;

export const SignedInt32Schema = z.number().int().min(INT32_MIN).max(INT32_MAX);

export const NonBlankStringSchema = z
  .string()
  .min(1)
  .refine((value) => value.trim().length > 0, 'Must contain a non-whitespace character.');

export const CanonicalUuidSchema = z.string().regex(CANONICAL_UUID);

export function isCanonicalUuid(value: string): boolean {
  return CANONICAL_UUID.test(value);
}

export const PlayerIdentitySchema = z.object({ name: NonBlankStringSchema, uuid: CanonicalUuidSchema }).strict();

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

export const UnitVectorSchema = ExactPositionSchema.refine(
  (vector) => Math.abs(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z - 1) <= 1e-6,
  'Vector must have unit length.',
);

export const BoundsSchema = z
  .object({
    min: BlockPositionSchema.describe('Inclusive minimum corner after coordinate normalization.'),
    max: BlockPositionSchema.describe('Inclusive maximum corner after coordinate normalization.'),
  })
  .strict()
  .superRefine((bounds, context) => {
    for (const axis of BLOCK_AXES) {
      if (bounds.min[axis] > bounds.max[axis]) {
        context.addIssue({
          code: 'custom',
          path: ['max', axis],
          message: `Normalized maximum ${axis.toUpperCase()} must not be less than minimum ${axis.toUpperCase()}.`,
        });
      }
    }
  })
  .describe('Normalized inclusive region bounds.');

export const DimensionsSchema = z
  .object({
    x: z.number().int().positive().describe('Region size along X in blocks.'),
    y: z.number().int().positive().describe('Region size along Y in blocks.'),
    z: z.number().int().positive().describe('Region size along Z in blocks.'),
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
