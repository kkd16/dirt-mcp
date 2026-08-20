import { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import {
  BlockPositionSchema,
  BoundsSchema,
  INT32_MAX,
  INT32_MIN,
  NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
  NonBlankStringSchema,
  READ_WORLD_ANNOTATIONS,
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

export const EditRecordSchema = z
  .object({
    editId: z.uuidv4().describe('Stable identifier for this retained undoable edit.'),
    callId: z.uuidv4().describe('Bridge call identifier that created this edit.'),
    operation: z
      .enum(['replace_region_blocks', 'fill_region', 'set_blocks'])
      .describe('Dirt edit operation that created this history entry.'),
    world: z.string().min(1).describe('Loaded world name at edit completion.'),
    worldId: z.uuid().describe('Paper world UUID used to scope the retained edit.'),
    bounds: BoundsSchema.describe('Normalized inclusive bounds targeted by the edit.'),
    changedBlockCount: z.number().int().positive().describe('Blocks changed by the retained edit.'),
    completedAt: z.iso
      .datetime({ offset: true })
      .describe('Timestamp at which the edit and history retention completed.'),
    status: z
      .enum(['committed', 'recovery_required'])
      .describe('Whether this is a completed edit or a failed edit retained for recovery undo.'),
  })
  .strict()
  .describe('One retained Dirt edit backed by a live undo record.');

const EditOutcomeSchema = z
  .enum(['preview', 'no_change', 'committed'])
  .describe('Whether the request previewed, made no changes, or committed an undoable edit.');

function hasConsistentEditOutcome(result: {
  readonly changedBlockCount: number;
  readonly edit: z.infer<typeof EditRecordSchema> | null;
  readonly outcome: z.infer<typeof EditOutcomeSchema>;
}): boolean {
  if (result.outcome === 'committed') return result.edit !== null && result.changedBlockCount > 0;
  if (result.outcome === 'no_change') return result.edit === null && result.changedBlockCount === 0;
  return result.edit === null;
}

const EditOutcomeMessage =
  'Committed outcomes require a non-null edit and positive changedBlockCount; preview and no_change outcomes require a null edit.';

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

export const ReplaceRegionBlocksOutputSchema = z
  .object({
    world: z.string().min(1).describe('Edited world name.'),
    bounds: BoundsSchema,
    sourceBlockStatePatterns: SourceBlockStatePatternsSchema,
    destinationPalette: DestinationPaletteSchema,
    seed: SeedSchema.describe('Resolved seed used for per-coordinate palette choices.'),
    outcome: EditOutcomeSchema,
    edit: EditRecordSchema.nullable().describe('Retained edit metadata, present only for a committed outcome.'),
    matchedBlockCount: z.number().int().nonnegative().describe('Blocks matching any sourceBlockStatePatterns entry.'),
    changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
  })
  .strict()
  .refine(hasConsistentEditOutcome, EditOutcomeMessage)
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

export const FillRegionOutputSchema = z
  .object({
    world: z.string().min(1).describe('Edited world name.'),
    bounds: BoundsSchema,
    destinationPalette: DestinationPaletteSchema,
    seed: SeedSchema.describe('Resolved seed used for per-coordinate palette choices.'),
    outcome: EditOutcomeSchema,
    edit: EditRecordSchema.nullable().describe('Retained edit metadata, present only for a committed outcome.'),
    volume: z.number().int().positive().describe('Total blocks in the region.'),
    changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
  })
  .strict()
  .refine(hasConsistentEditOutcome, EditOutcomeMessage)
  .describe('Completed or previewed region fill.');

const SetBlocksPlacementSchema = z
  .array(z.number().int().min(INT32_MIN).max(INT32_MAX))
  .length(4)
  .describe('Exact [paletteIndex, x, y, z] tuple; x, y, and z are signed offsets from the origin.');

const SetBlocksPalettesSchema = z
  .array(DestinationPaletteSchema)
  .min(1)
  .max(64)
  .superRefine((palettes, context) => {
    const entryCount = palettes.reduce((sum, palette) => sum + palette.length, 0);
    if (entryCount > 64) {
      context.addIssue({ code: 'custom', message: 'Palettes may contain at most 64 entries in total.' });
    }
  })
  .describe('One or more weighted block-state palettes referenced by zero-based index; at most 64 entries total.');

export const SetBlocksInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    origin: BlockPositionSchema.describe('Absolute anchor added to every placement offset.'),
    palettes: SetBlocksPalettesSchema,
    placements: z.array(SetBlocksPlacementSchema).min(1).describe('Palette-indexed origin-relative block placements.'),
    seed: SeedSchema.optional().describe(
      'Optional reproducibility seed. Omission generates a fresh seed returned in the result.',
    ),
    dryRun: z
      .boolean()
      .optional()
      .describe('Preview exact counts without mutating the world; omission uses the Paper plugin default.'),
  })
  .strict()
  .superRefine((input, context) => {
    const positions = new Set<string>();
    input.placements.forEach((placement, placementIndex) => {
      const paletteIndex = placement[0]!;
      if (paletteIndex < 0 || paletteIndex >= input.palettes.length) {
        context.addIssue({
          code: 'custom',
          path: ['placements', placementIndex, 0],
          message: 'Palette index must reference an entry in palettes.',
        });
      }
      const resolved = [input.origin.x + placement[1]!, input.origin.y + placement[2]!, input.origin.z + placement[3]!];
      if (resolved.some((coordinate) => coordinate < INT32_MIN || coordinate > INT32_MAX)) {
        context.addIssue({
          code: 'custom',
          path: ['placements', placementIndex],
          message: 'Resolved position must use signed 32-bit coordinates.',
        });
        return;
      }
      const key = resolved.join(',');
      if (positions.has(key)) {
        context.addIssue({
          code: 'custom',
          path: ['placements', placementIndex],
          message: 'Resolved block positions must be distinct.',
        });
      }
      positions.add(key);
    });
  })
  .describe('One undoable weighted-palette block edit at origin-relative offsets.');

export const SetBlocksOutputSchema = z
  .object({
    world: z.string().min(1).describe('Edited world name.'),
    bounds: BoundsSchema.describe('Smallest inclusive bounds containing every requested position.'),
    palettes: SetBlocksPalettesSchema.describe('Canonical palettes used by the edit.'),
    seed: SeedSchema.describe('Resolved seed used for per-coordinate palette choices.'),
    outcome: EditOutcomeSchema,
    edit: EditRecordSchema.nullable().describe('Retained edit metadata, present only for a committed outcome.'),
    blockCount: z.number().int().positive().describe('Distinct positions in the request.'),
    changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
    unchangedBlockCount: z.number().int().nonnegative().describe('Blocks already in their requested state.'),
  })
  .strict()
  .refine(hasConsistentEditOutcome, EditOutcomeMessage)
  .describe('Completed or previewed palette-based block edit.');

const GetEditHistoryInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of the loaded world whose retained edits should be returned.'),
  })
  .strict()
  .describe('Loaded-world edit history lookup.');

export const GetEditHistoryOutputSchema = z
  .object({
    world: z.string().min(1).describe('Loaded world whose retained history was returned.'),
    edits: z.array(EditRecordSchema).describe('Retained undoable edits ordered newest first.'),
  })
  .strict()
  .describe('Current bounded undoable edit history for one loaded world.');

const UndoEditInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of the loaded world containing the retained edit.'),
    editId: z.uuidv4().describe('Identifier of the newest retained edit to undo.'),
  })
  .strict()
  .describe('Identity-checked undo of one retained Dirt edit.');

export const UndoEditOutputSchema = z
  .object({
    edit: EditRecordSchema.describe('Retained edit that was successfully undone and consumed.'),
    undoCallId: z.uuidv4().describe('Bridge call identifier that performed the undo.'),
    undoneAt: z.iso.datetime({ offset: true }).describe('Timestamp at which the undo completed.'),
  })
  .strict()
  .describe('Result of undoing and consuming an identified retained Dirt edit.');

export function registerEditingTools(server: McpServer, bridge: BridgeClient): void {
  server.registerTool(
    'replace_region_blocks',
    {
      title: 'Replace region blocks',
      description:
        'Replace blocks matching any source pattern throughout an inclusive region. Omitted source properties match any value. Destination entries are exact states; omit every weight for equal probability or provide whole percentages totaling 100. Reuse the returned seed to reproduce a preview. Set dryRun=true to preview without mutation. Every committed non-empty edit returns retained edit metadata including its edit ID.',
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
          const verb = result.outcome === 'preview' ? 'Would change' : 'Changed';
          const editSummary = result.edit === null ? '' : ` Edit ID: ${result.edit.editId}.`;
          return successResult(
            result,
            `${verb} ${result.changedBlockCount} of ${result.matchedBlockCount} matching blocks in ${result.world} using seed ${result.seed}.${editSummary}`,
          );
        },
      ),
  );

  server.registerTool(
    'fill_region',
    {
      title: 'Fill a region',
      description:
        'Fill an inclusive region from a destination palette of exact block states. Omit every weight for equal probability or provide whole percentages totaling 100. Reuse the returned seed to reproduce a preview. Set dryRun=true to preview without mutation. Every committed non-empty edit returns retained edit metadata including its edit ID.',
      inputSchema: FillRegionInputSchema,
      outputSchema: FillRegionOutputSchema,
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        { tool: 'fill_region', world: input.world, context, failureContext: 'Could not fill the region' },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.fillRegion, callId, FillRegionOutputSchema, input);
          const verb = result.outcome === 'preview' ? 'Would change' : 'Changed';
          const editSummary = result.edit === null ? '' : ` Edit ID: ${result.edit.editId}.`;
          return successResult(
            result,
            `${verb} ${result.changedBlockCount} of ${result.volume} blocks in ${result.world} using seed ${result.seed}.${editSummary}`,
          );
        },
      ),
  );

  server.registerTool(
    'set_blocks',
    {
      title: 'Set blocks',
      description:
        'Place blocks from weighted palettes at distinct origin-relative positions, using one FAWE edit and one retained Dirt history entry. Each placement is [paletteIndex, x, y, z], where paletteIndex is zero-based. Omit every weight in a palette for equal probability, or provide whole percentages totaling 100. Reuse the returned seed to replay a preview. All states and resolved positions are validated before mutation. Keep palettes within get_server_status.limits.maxBlockStatePatterns and the encoded request within maxRequestBytes. Placement does not trigger Minecraft neighbor physics. Set dryRun=true to preview exact counts. Every committed non-empty edit returns retained edit metadata including its edit ID.',
      inputSchema: SetBlocksInputSchema,
      outputSchema: SetBlocksOutputSchema,
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        { tool: 'set_blocks', world: input.world, context, failureContext: 'Could not set blocks' },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.setBlocks, callId, SetBlocksOutputSchema, input);
          const verb = result.outcome === 'preview' ? 'Would change' : 'Changed';
          const editSummary = result.edit === null ? '' : ` Edit ID: ${result.edit.editId}.`;
          return successResult(
            result,
            `${verb} ${result.changedBlockCount} of ${result.blockCount} requested blocks in ${result.world} using seed ${result.seed}.${editSummary}`,
          );
        },
      ),
  );

  server.registerTool(
    'get_edit_history',
    {
      title: 'Get edit history',
      description:
        'Return every currently retained and undoable Dirt edit for one loaded world, ordered newest first. Dry runs, no-ops, consumed edits, and evicted edits are not included.',
      inputSchema: GetEditHistoryInputSchema,
      outputSchema: GetEditHistoryOutputSchema,
      annotations: READ_WORLD_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        {
          tool: 'get_edit_history',
          world: input.world,
          context,
          failureContext: 'Could not get edit history',
        },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.getEditHistory, callId, GetEditHistoryOutputSchema, input);
          const noun = result.edits.length === 1 ? 'edit' : 'edits';
          return successResult(result, `Found ${result.edits.length} retained undoable ${noun} in ${result.world}.`);
        },
      ),
  );

  server.registerTool(
    'undo_edit',
    {
      title: 'Undo an edit',
      description:
        'Undo the retained Dirt edit identified by editId in one loaded world. The edit must still be retained and must be the newest entry returned by get_edit_history, preventing an intervening edit from being undone accidentally.',
      inputSchema: UndoEditInputSchema,
      outputSchema: UndoEditOutputSchema,
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        {
          tool: 'undo_edit',
          world: input.world,
          context,
          failureContext: 'Could not undo the edit',
        },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.undoEdit, callId, UndoEditOutputSchema, input);
          return successResult(
            result,
            `Undid edit ${result.edit.editId} in ${result.edit.world}, restoring ${result.edit.changedBlockCount} blocks.`,
          );
        },
      ),
  );
}
