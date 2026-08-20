import type { ToolAnnotations } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';

export const INT32_MIN = -2_147_483_648;
export const INT32_MAX = 2_147_483_647;

export const NonBlankStringSchema = z
  .string()
  .min(1)
  .refine((value) => value.trim().length > 0, 'Must contain a non-whitespace character.');

export const EmptyInputSchema = z.object({}).strict().describe('No arguments.');

export const BlockPositionSchema = z
  .object({
    x: z.number().int().min(INT32_MIN).max(INT32_MAX).describe('World X block coordinate.'),
    y: z.number().int().min(INT32_MIN).max(INT32_MAX).describe('World Y block coordinate.'),
    z: z.number().int().min(INT32_MIN).max(INT32_MAX).describe('World Z block coordinate.'),
  })
  .strict()
  .describe('An absolute Minecraft block position.');

export const BoundsSchema = z
  .object({
    min: BlockPositionSchema.describe('Inclusive minimum corner after coordinate normalization.'),
    max: BlockPositionSchema.describe('Inclusive maximum corner after coordinate normalization.'),
  })
  .strict()
  .superRefine((bounds, context) => {
    for (const axis of ['x', 'y', 'z'] as const) {
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
