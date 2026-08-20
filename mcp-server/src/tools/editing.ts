import { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import {
  BlockPositionSchema,
  BoundsSchema,
  INT32_MAX,
  INT32_MIN,
  MUTATION_ANNOTATIONS,
  NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
  NonBlankStringSchema,
} from './common.ts';
import { executeToolCall, successResult } from './execution.ts';

export const SourceBlockStatePatternsSchema = z
  .array(NonBlankStringSchema)
  .min(1)
  .max(64)
  .superRefine((patterns, context) => {
    const seen = new Set<string>();
    patterns.forEach((pattern, index) => {
      if (seen.has(pattern)) {
        context.addIssue({
          code: 'custom',
          path: [index],
          message: 'Source block-state patterns must be distinct.',
        });
      }
      seen.add(pattern);
    });
  })
  .describe('One or more block-state patterns matched as a union. Omitted properties match any value.');

const DestinationPaletteEntrySchema = z
  .object({
    blockState: NonBlankStringSchema.describe('Exact canonical block state to place.'),
    weight: z
      .number()
      .int()
      .min(1)
      .max(100)
      .optional()
      .describe('Whole-number percentage. Supply for every entry or omit from every entry.'),
  })
  .strict()
  .describe('One destination state and its optional probability percentage.');

export const DestinationPaletteSchema = z
  .array(DestinationPaletteEntrySchema)
  .min(1)
  .max(64)
  .superRefine((entries, context) => {
    const weightedCount = entries.filter((entry) => entry.weight !== undefined).length;
    if (weightedCount !== 0 && weightedCount !== entries.length) {
      context.addIssue({
        code: 'custom',
        message: 'Weights must be provided for every destination or omitted from every destination.',
      });
    }
    if (weightedCount === entries.length) {
      const total = entries.reduce((sum, entry) => sum + (entry.weight ?? 0), 0);
      if (total !== 100) {
        context.addIssue({ code: 'custom', message: 'Destination weights must total 100.' });
      }
    }
    const seen = new Set<string>();
    entries.forEach((entry, index) => {
      if (seen.has(entry.blockState)) {
        context.addIssue({
          code: 'custom',
          path: [index, 'blockState'],
          message: 'Destination block states must be distinct.',
        });
      }
      seen.add(entry.blockState);
    });
  })
  .describe('One or more exact destination states. Omitted weights give every entry equal probability.');

const SeedSchema = z
  .number()
  .int()
  .min(INT32_MIN)
  .max(INT32_MAX)
  .describe('Signed 32-bit seed for reproducible per-coordinate palette choices.');

const ReplaceRegionBlocksInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
    max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
    sourceBlockStatePatterns: SourceBlockStatePatternsSchema,
    destinationPalette: DestinationPaletteSchema,
    seed: SeedSchema.optional().describe(
      'Optional reproducibility seed. Omission generates a fresh seed returned in the result.',
    ),
    dryRun: z
      .boolean()
      .optional()
      .describe('Preview counts without mutating the world; omission uses the Paper plugin default.'),
  })
  .strict()
  .describe('Property-aware block-state replacement with a weighted destination palette.');

const ReplaceRegionBlocksOutputSchema = z
  .object({
    world: z.string().min(1).describe('Edited world name.'),
    bounds: BoundsSchema,
    sourceBlockStatePatterns: SourceBlockStatePatternsSchema,
    destinationPalette: DestinationPaletteSchema,
    seed: SeedSchema.describe('Resolved seed used for per-coordinate palette choices.'),
    dryRun: z.boolean().describe('Whether the world was left unchanged.'),
    matchedBlockCount: z.number().int().nonnegative().describe('Blocks matching any sourceBlockStatePatterns entry.'),
    changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
  })
  .strict()
  .describe('Completed or previewed property-aware block-state replacement.');

const FillRegionInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
    max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
    destinationPalette: DestinationPaletteSchema,
    seed: SeedSchema.optional().describe(
      'Optional reproducibility seed. Omission generates a fresh seed returned in the result.',
    ),
    dryRun: z
      .boolean()
      .optional()
      .describe('Preview counts without mutating the world; omission uses the Paper plugin default.'),
  })
  .strict()
  .describe('Weighted block-state palette fill of an inclusive region.');

const FillRegionOutputSchema = z
  .object({
    world: z.string().min(1).describe('Edited world name.'),
    bounds: BoundsSchema,
    destinationPalette: DestinationPaletteSchema,
    seed: SeedSchema.describe('Resolved seed used for per-coordinate palette choices.'),
    dryRun: z.boolean().describe('Whether the world was left unchanged.'),
    volume: z.number().int().positive().describe('Total blocks in the region.'),
    changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
  })
  .strict()
  .describe('Completed or previewed region fill.');

const BlockOffsetSchema = z
  .tuple([
    z.number().int().min(INT32_MIN).max(INT32_MAX),
    z.number().int().min(INT32_MIN).max(INT32_MAX),
    z.number().int().min(INT32_MIN).max(INT32_MAX),
  ])
  .describe('Signed [x, y, z] offset from the origin.');

const SetBlocksPaletteSchema = z
  .array(NonBlankStringSchema)
  .min(1)
  .max(64)
  .superRefine((palette, context) => {
    const seen = new Set<string>();
    palette.forEach((blockState, index) => {
      if (seen.has(blockState)) {
        context.addIssue({
          code: 'custom',
          path: [index],
          message: 'Palette block states must be distinct.',
        });
      }
      seen.add(blockState);
    });
  })
  .describe('One or more distinct exact block states referenced by zero-based index.');

export const SetBlocksInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    origin: BlockPositionSchema.describe('Absolute anchor added to every placement offset.'),
    palette: SetBlocksPaletteSchema,
    placements: z
      .array(
        z
          .object({
            paletteIndex: z.number().int().min(0).max(INT32_MAX).describe('Zero-based index into palette.'),
            offsets: z
              .array(BlockOffsetSchema)
              .min(1)
              .describe('Origin-relative positions that receive this palette state.'),
          })
          .strict(),
      )
      .min(1)
      .describe('Palette-indexed groups of origin-relative block offsets.'),
    dryRun: z
      .boolean()
      .optional()
      .describe('Preview exact counts without mutating the world; omission uses the Paper plugin default.'),
  })
  .strict()
  .superRefine((input, context) => {
    const positions = new Set<string>();
    input.placements.forEach((placement, placementIndex) => {
      if (placement.paletteIndex >= input.palette.length) {
        context.addIssue({
          code: 'custom',
          path: ['placements', placementIndex, 'paletteIndex'],
          message: 'Palette index must reference an entry in palette.',
        });
      }
      placement.offsets.forEach((offset, offsetIndex) => {
        const resolved = [input.origin.x + offset[0], input.origin.y + offset[1], input.origin.z + offset[2]];
        if (resolved.some((coordinate) => coordinate < INT32_MIN || coordinate > INT32_MAX)) {
          context.addIssue({
            code: 'custom',
            path: ['placements', placementIndex, 'offsets', offsetIndex],
            message: 'Resolved position must use signed 32-bit coordinates.',
          });
          return;
        }
        const key = resolved.join(',');
        if (positions.has(key)) {
          context.addIssue({
            code: 'custom',
            path: ['placements', placementIndex, 'offsets', offsetIndex],
            message: 'Resolved block positions must be distinct.',
          });
        }
        positions.add(key);
      });
    });
  })
  .describe('One undoable palette-based block edit at origin-relative offsets.');

const SetBlocksOutputSchema = z
  .object({
    world: z.string().min(1).describe('Edited world name.'),
    dryRun: z.boolean().describe('Whether the world was left unchanged.'),
    blockCount: z.number().int().positive().describe('Distinct positions in the request.'),
    changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
    unchangedBlockCount: z.number().int().nonnegative().describe('Blocks already in their requested state.'),
  })
  .strict()
  .describe('Completed or previewed palette-based block edit.');

const UndoLastDirtEditInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of the loaded world whose Dirt edit should be undone.'),
  })
  .strict()
  .describe('World-scoped Dirt edit history lookup.');

const UndoLastDirtEditOutputSchema = z
  .object({
    world: z.string().min(1).describe('World in which the edit was undone.'),
    changedBlockCount: z.number().int().positive().describe('Blocks restored by the undo.'),
  })
  .strict()
  .describe('Result of undoing the newest successful Dirt edit in this world.');

export function registerEditingTools(server: McpServer, bridge: BridgeClient): void {
  server.registerTool(
    'replace_region_blocks',
    {
      title: 'Replace region blocks',
      description:
        'Replace blocks matching any source pattern throughout an inclusive region. Omitted source properties match any value. Destination entries are exact states; omit every weight for equal probability or provide whole percentages totaling 100. Reuse the returned seed to reproduce a preview. Set dryRun=true to preview without mutation.',
      inputSchema: ReplaceRegionBlocksInputSchema,
      outputSchema: ReplaceRegionBlocksOutputSchema,
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        {
          tool: 'replace_region_blocks',
          world: input.world,
          context,
          failureContext: 'Could not replace region blocks',
        },
        async (callId) => {
          const result = await bridge.request(
            BRIDGE_ROUTES.replaceRegionBlocks,
            callId,
            ReplaceRegionBlocksOutputSchema,
            input,
          );
          const verb = result.dryRun ? 'Would change' : 'Changed';
          return successResult(
            result,
            `${verb} ${result.changedBlockCount} of ${result.matchedBlockCount} matching blocks in ${result.world} using seed ${result.seed}.`,
          );
        },
      ),
  );

  server.registerTool(
    'fill_region',
    {
      title: 'Fill a region',
      description:
        'Fill an inclusive region from a destination palette of exact block states. Omit every weight for equal probability or provide whole percentages totaling 100. Reuse the returned seed to reproduce a preview. Set dryRun=true to preview without mutation.',
      inputSchema: FillRegionInputSchema,
      outputSchema: FillRegionOutputSchema,
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        { tool: 'fill_region', world: input.world, context, failureContext: 'Could not fill the region' },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.fillRegion, callId, FillRegionOutputSchema, input);
          const verb = result.dryRun ? 'Would change' : 'Changed';
          return successResult(
            result,
            `${verb} ${result.changedBlockCount} of ${result.volume} blocks in ${result.world} using seed ${result.seed}.`,
          );
        },
      ),
  );

  server.registerTool(
    'set_blocks',
    {
      title: 'Set blocks',
      description:
        'Place exact block states from a palette at distinct [x, y, z] offsets from one origin, using one FAWE edit and one Dirt undo entry. paletteIndex is zero-based. All states and resolved positions are validated before mutation. Keep the encoded request within get_server_status.limits.maxRequestBytes. Placement does not trigger Minecraft neighbor physics. Set dryRun=true to preview exact counts.',
      inputSchema: SetBlocksInputSchema,
      outputSchema: SetBlocksOutputSchema,
      annotations: MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        { tool: 'set_blocks', world: input.world, context, failureContext: 'Could not set blocks' },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.setBlocks, callId, SetBlocksOutputSchema, input);
          const verb = result.dryRun ? 'Would change' : 'Changed';
          return successResult(
            result,
            `${verb} ${result.changedBlockCount} of ${result.blockCount} requested blocks in ${result.world}.`,
          );
        },
      ),
  );

  server.registerTool(
    'undo_last_dirt_edit',
    {
      title: 'Undo the last Dirt edit',
      description:
        'Undo the newest successful Dirt replace, fill, or palette-based set in one loaded world. History is in-memory and scoped per world.',
      inputSchema: UndoLastDirtEditInputSchema,
      outputSchema: UndoLastDirtEditOutputSchema,
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        {
          tool: 'undo_last_dirt_edit',
          world: input.world,
          context,
          failureContext: 'Could not undo the last Dirt edit',
        },
        async (callId) => {
          const result = await bridge.request(
            BRIDGE_ROUTES.undoLastDirtEdit,
            callId,
            UndoLastDirtEditOutputSchema,
            input,
          );
          return successResult(
            result,
            `Undid the last Dirt edit in ${result.world}, restoring ${result.changedBlockCount} blocks.`,
          );
        },
      ),
  );
}
