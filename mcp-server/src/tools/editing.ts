import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import type { DirtLogger } from '../logging.ts';
import { ToolFailure } from '../bridge/errors.ts';
import {
  BlockPositionSchema,
  BLOCK_AXES,
  BoundsSchema,
  INT32_MAX,
  INT32_MIN,
  MAX_BLOCK_STATE_ENTRIES,
  NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
  NonBlankStringSchema,
  READ_WORLD_ANNOTATIONS,
  SignedInt32Schema,
} from './common.ts';
import { executeToolCall, successResult } from './execution.ts';
import type { McpToolConfiguration } from './configuration.ts';
import { inclusiveBlockVolume, normalizedBounds, requireMatchingWorld, sameBounds } from './response-validation.ts';

export const SourceBlockStatePatternsSchema = z
  .array(NonBlankStringSchema)
  .min(1)
  .max(MAX_BLOCK_STATE_ENTRIES)
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
  .max(MAX_BLOCK_STATE_ENTRIES)
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

const SeedSchema = SignedInt32Schema.describe('Signed 32-bit seed for reproducible per-coordinate palette choices.');

const EditOptionsInputShape = {
  seed: SeedSchema.optional().describe(
    'Optional reproducibility seed. Omission generates a fresh seed returned in the result.',
  ),
  dryRun: z
    .boolean()
    .optional()
    .describe('True previews without mutation, false executes the edit, and omission uses the plugin default.'),
};

const EditOperationSchema = z.enum(['replace_region_blocks', 'fill_region', 'set_blocks']);

export const EditRecordSchema = z
  .object({
    editId: z.uuidv4().describe('Stable identifier for this edit transaction.'),
    callId: z.uuidv4().describe('Bridge call identifier that created this edit.'),
    operation: EditOperationSchema.describe('Dirt operation that performed this edit.'),
    world: NonBlankStringSchema.describe('Loaded world name associated with this edit.'),
    worldId: z.uuid().describe('Paper world UUID associated with this edit.'),
    bounds: BoundsSchema.describe('Normalized inclusive bounds targeted by the edit.'),
    changedBlockCount: z.number().int().positive().describe('Blocks changed by this edit.'),
    completedAt: z.iso
      .datetime({ offset: true })
      .describe(
        'Timestamp recorded when the original edit completed or entered recovery; later undo attempts do not change it.',
      ),
    status: z
      .enum(['committed', 'recovery_required'])
      .describe(
        'Last retained state represented by this record. A successful undo returns the state immediately before consumption.',
      ),
  })
  .strict()
  .describe('Identity and lifecycle metadata for one Dirt edit transaction.');

const EditOutcomeSchema = z
  .enum(['preview', 'no_change', 'committed'])
  .describe('Whether the request previewed, made no changes, or committed an undoable edit.');

interface EditResultMetadata {
  readonly bounds: z.infer<typeof BoundsSchema>;
  readonly changedBlockCount: number;
  readonly edit: z.infer<typeof EditRecordSchema> | null;
  readonly outcome: z.infer<typeof EditOutcomeSchema>;
  readonly world: string;
}

function hasConsistentEditResult(
  result: EditResultMetadata,
  expectedOperation: z.infer<typeof EditOperationSchema>,
): boolean {
  if (result.outcome === 'committed') {
    const edit = result.edit;
    return (
      edit !== null &&
      result.changedBlockCount > 0 &&
      edit.status === 'committed' &&
      edit.operation === expectedOperation &&
      edit.world === result.world &&
      edit.changedBlockCount === result.changedBlockCount &&
      sameBounds(edit.bounds, result.bounds)
    );
  }
  if (result.outcome === 'no_change') return result.edit === null && result.changedBlockCount === 0;
  return result.edit === null;
}

const EditResultMessage =
  'Committed outcomes require matching committed edit metadata and a positive changedBlockCount; preview and no_change outcomes require a null edit.';

export function requireMatchingCallId(expected: string, actual: string, editId: string): void {
  if (actual.toLowerCase() !== expected.toLowerCase()) {
    throw new ToolFailure({
      code: 'bridge_invalid_response',
      message: 'Paper bridge response call ID did not match the request.',
      editId,
    });
  }
}

export function requireMatchingEditIdentity(
  expectedWorld: string,
  expectedBounds: z.infer<typeof BoundsSchema>,
  expectedCallId: string,
  actual: EditResultMetadata,
): void {
  const editId = actual.edit?.editId;
  if (actual.world !== expectedWorld || !sameBounds(actual.bounds, expectedBounds)) {
    throw new ToolFailure({
      code: 'bridge_invalid_response',
      message: 'Paper bridge edit result did not match the requested world and bounds.',
      ...(editId === undefined ? {} : { editId }),
    });
  }
  if (actual.edit !== null) requireMatchingCallId(expectedCallId, actual.edit.callId, actual.edit.editId);
}

export function requireMatchingUndoIdentity(
  expectedWorld: string,
  expectedEditId: string,
  actual: z.infer<typeof EditRecordSchema>,
): void {
  if (actual.world !== expectedWorld || actual.editId.toLowerCase() !== expectedEditId.toLowerCase()) {
    throw new ToolFailure({
      code: 'bridge_invalid_response',
      message: 'Paper bridge undo result did not match the requested edit.',
      editId: actual.editId,
    });
  }
}

const ReplaceRegionBlocksInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
    max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
    sourceBlockStatePatterns: SourceBlockStatePatternsSchema,
    destinationPalette: DestinationPaletteSchema,
    ...EditOptionsInputShape,
  })
  .strict()
  .describe('Property-aware block-state replacement with a weighted destination palette.');

export const ReplaceRegionBlocksOutputSchema = z
  .object({
    world: z.string().min(1).describe('Edited world name.'),
    bounds: BoundsSchema.describe('Normalized inclusive bounds from the requested corners.'),
    sourceBlockStatePatterns: SourceBlockStatePatternsSchema,
    destinationPalette: DestinationPaletteSchema,
    seed: SeedSchema.describe('Supplied request seed, or the generated seed when the request omitted one.'),
    outcome: EditOutcomeSchema.describe('Explicit dryRun=true requires preview; false excludes preview.'),
    edit: EditRecordSchema.nullable().describe('Retained edit metadata, present only for a committed outcome.'),
    matchedBlockCount: z
      .number()
      .int()
      .nonnegative()
      .describe('Blocks matching any source pattern; never greater than the inclusive bounds volume.'),
    changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
  })
  .strict()
  .refine((result) => hasConsistentEditResult(result, 'replace_region_blocks'), EditResultMessage)
  .refine(
    (result) => result.changedBlockCount <= result.matchedBlockCount,
    'changedBlockCount must not exceed matchedBlockCount.',
  )
  .describe('Completed or previewed property-aware block-state replacement.');

const FillRegionInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
    max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
    destinationPalette: DestinationPaletteSchema,
    ...EditOptionsInputShape,
  })
  .strict()
  .describe('Weighted block-state palette fill of an inclusive region.');

export const FillRegionOutputSchema = z
  .object({
    world: z.string().min(1).describe('Edited world name.'),
    bounds: BoundsSchema.describe('Normalized inclusive bounds from the requested corners.'),
    destinationPalette: DestinationPaletteSchema,
    seed: SeedSchema.describe('Supplied request seed, or the generated seed when the request omitted one.'),
    outcome: EditOutcomeSchema.describe('Explicit dryRun=true requires preview; false excludes preview.'),
    edit: EditRecordSchema.nullable().describe('Retained edit metadata, present only for a committed outcome.'),
    volume: z.number().int().positive().describe('Exact inclusive volume of the requested normalized bounds.'),
    changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
  })
  .strict()
  .refine((result) => hasConsistentEditResult(result, 'fill_region'), EditResultMessage)
  .refine((result) => result.changedBlockCount <= result.volume, 'changedBlockCount must not exceed volume.')
  .describe('Completed or previewed region fill.');

const SetBlocksPlacementSchema = z
  .array(SignedInt32Schema)
  .length(4)
  .describe('Exact [paletteIndex, x, y, z] tuple; x, y, and z are signed offsets from the origin.');

const SetBlocksPalettesSchema = z
  .array(DestinationPaletteSchema)
  .min(1)
  .max(MAX_BLOCK_STATE_ENTRIES)
  .superRefine((palettes, context) => {
    const entryCount = palettes.reduce((sum, palette) => sum + palette.length, 0);
    if (entryCount > MAX_BLOCK_STATE_ENTRIES) {
      context.addIssue({
        code: 'custom',
        message: `Palettes may contain at most ${MAX_BLOCK_STATE_ENTRIES} entries in total.`,
      });
    }
  })
  .describe(
    `One or more weighted block-state palettes referenced by zero-based index; at most ${MAX_BLOCK_STATE_ENTRIES} entries total.`,
  );

export const SetBlocksInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    origin: BlockPositionSchema.describe('Absolute anchor added to every placement offset.'),
    palettes: SetBlocksPalettesSchema,
    placements: z.array(SetBlocksPlacementSchema).min(1).describe('Palette-indexed origin-relative block placements.'),
    ...EditOptionsInputShape,
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
    seed: SeedSchema.describe('Supplied request seed, or the generated seed when the request omitted one.'),
    outcome: EditOutcomeSchema.describe('Explicit dryRun=true requires preview; false excludes preview.'),
    edit: EditRecordSchema.nullable().describe('Retained edit metadata, present only for a committed outcome.'),
    blockCount: z.number().int().positive().describe('Number of requested placements.'),
    changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
    unchangedBlockCount: z.number().int().nonnegative().describe('Blocks already in their requested state.'),
  })
  .strict()
  .refine((result) => hasConsistentEditResult(result, 'set_blocks'), EditResultMessage)
  .refine(
    (result) => result.changedBlockCount + result.unchangedBlockCount === result.blockCount,
    'changedBlockCount and unchangedBlockCount must sum to blockCount.',
  )
  .describe('Completed or previewed palette-based block edit.');

const GetEditHistoryInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of the loaded world whose retained edits should be returned.'),
  })
  .strict()
  .describe('Loaded-world edit history lookup.');

const GetEditHistoryOutputShapeSchema = z
  .object({
    world: z.string().min(1).describe('Loaded world whose retained history was returned.'),
    edits: z.array(EditRecordSchema).describe('Retained undoable edits ordered newest first.'),
  })
  .strict();

function hasConsistentHistory(result: z.infer<typeof GetEditHistoryOutputShapeSchema>): boolean {
  const editIds = new Set<string>();
  let worldId: string | undefined;
  for (const edit of result.edits) {
    const normalizedEditId = edit.editId.toLowerCase();
    const normalizedWorldId = edit.worldId.toLowerCase();
    if (
      edit.world !== result.world ||
      editIds.has(normalizedEditId) ||
      (worldId !== undefined && normalizedWorldId !== worldId)
    ) {
      return false;
    }
    editIds.add(normalizedEditId);
    worldId = normalizedWorldId;
  }
  return true;
}

export const GetEditHistoryOutputSchema = GetEditHistoryOutputShapeSchema.refine(
  hasConsistentHistory,
  'History records must belong to one loaded world and have distinct editIds.',
).describe('Current bounded undoable edit history for one loaded world.');

function invalidEditResult(actual: EditResultMetadata, message: string): never {
  const editId = actual.edit?.editId;
  throw new ToolFailure({
    code: 'bridge_invalid_response',
    message,
    ...(editId === undefined ? {} : { editId }),
  });
}

export function requireMatchingEditOptions(
  expectedSeed: number | undefined,
  expectedDryRun: boolean | undefined,
  actual: EditResultMetadata & { readonly seed: number },
): void {
  if (expectedSeed !== undefined && actual.seed !== expectedSeed) {
    invalidEditResult(actual, 'Paper bridge edit result seed did not match the request.');
  }
  if (
    (expectedDryRun === true && actual.outcome !== 'preview') ||
    (expectedDryRun === false && actual.outcome === 'preview')
  ) {
    invalidEditResult(actual, 'Paper bridge edit outcome did not match the explicit dryRun request.');
  }
}

export function requireReplaceCountsWithinBounds(
  expectedBounds: z.infer<typeof BoundsSchema>,
  actual: EditResultMetadata & { readonly matchedBlockCount: number },
): void {
  if (BigInt(actual.matchedBlockCount) > inclusiveBlockVolume(expectedBounds)) {
    invalidEditResult(actual, 'Paper bridge replacement count exceeded the requested inclusive region volume.');
  }
}

export function requireMatchingFillVolume(
  expectedBounds: z.infer<typeof BoundsSchema>,
  actual: EditResultMetadata & { readonly volume: number },
): void {
  if (BigInt(actual.volume) !== inclusiveBlockVolume(expectedBounds)) {
    invalidEditResult(actual, 'Paper bridge fill volume did not match the requested inclusive region volume.');
  }
}

export function requireMatchingSetBlockCount(
  expectedCount: number,
  actual: EditResultMetadata & {
    readonly blockCount: number;
  },
): void {
  if (actual.blockCount !== expectedCount) {
    invalidEditResult(actual, 'Paper bridge blockCount did not match the number of requested placements.');
  }
}

function setBlocksBounds(input: z.infer<typeof SetBlocksInputSchema>): z.infer<typeof BoundsSchema> {
  const first = input.placements[0]!;
  const initial = {
    x: input.origin.x + first[1]!,
    y: input.origin.y + first[2]!,
    z: input.origin.z + first[3]!,
  };
  const bounds = { min: { ...initial }, max: { ...initial } };
  for (const placement of input.placements.slice(1)) {
    const position = {
      x: input.origin.x + placement[1]!,
      y: input.origin.y + placement[2]!,
      z: input.origin.z + placement[3]!,
    };
    for (const axis of BLOCK_AXES) {
      bounds.min[axis] = Math.min(bounds.min[axis], position[axis]);
      bounds.max[axis] = Math.max(bounds.max[axis], position[axis]);
    }
  }
  return bounds;
}

const UndoEditInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of the loaded world containing the retained edit.'),
    editId: z.uuidv4().describe('Identifier of the newest retained edit to undo.'),
  })
  .strict()
  .describe('Identity-checked undo of one retained Dirt edit.');

export const UndoEditOutputSchema = z
  .object({
    edit: EditRecordSchema.describe('Edit record as retained immediately before the successful undo consumed it.'),
    undoCallId: z.uuidv4().describe('Bridge call identifier that performed the undo.'),
    undoneAt: z.iso.datetime({ offset: true }).describe('Timestamp at which the undo completed.'),
  })
  .strict()
  .describe('Result of undoing and consuming an identified retained Dirt edit.');

export function registerEditingTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  const replaceRegionBlocks = server.registerTool(
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
        logger,
        {
          operation: 'replace_region_blocks',
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
          const bounds = normalizedBounds(input.min, input.max);
          requireMatchingEditIdentity(input.world, bounds, callId, result);
          requireMatchingEditOptions(input.seed, input.dryRun, result);
          requireReplaceCountsWithinBounds(bounds, result);
          const verb = result.outcome === 'preview' ? 'Would change' : 'Changed';
          const editSummary = result.edit === null ? '' : ` Edit ID: ${result.edit.editId}.`;
          return successResult(
            result,
            `${verb} ${result.changedBlockCount} of ${result.matchedBlockCount} matching blocks in ${result.world} using seed ${result.seed}.${editSummary}`,
          );
        },
      ),
  );
  if (!toolConfiguration.replace_region_blocks) replaceRegionBlocks.disable();

  const fillRegion = server.registerTool(
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
        logger,
        { operation: 'fill_region', world: input.world, context, failureContext: 'Could not fill the region' },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.fillRegion, callId, FillRegionOutputSchema, input);
          const bounds = normalizedBounds(input.min, input.max);
          requireMatchingEditIdentity(input.world, bounds, callId, result);
          requireMatchingEditOptions(input.seed, input.dryRun, result);
          requireMatchingFillVolume(bounds, result);
          const verb = result.outcome === 'preview' ? 'Would change' : 'Changed';
          const editSummary = result.edit === null ? '' : ` Edit ID: ${result.edit.editId}.`;
          return successResult(
            result,
            `${verb} ${result.changedBlockCount} of ${result.volume} blocks in ${result.world} using seed ${result.seed}.${editSummary}`,
          );
        },
      ),
  );
  if (!toolConfiguration.fill_region) fillRegion.disable();

  const setBlocks = server.registerTool(
    'set_blocks',
    {
      title: 'Set blocks',
      description:
        'Place blocks from weighted palettes at distinct origin-relative positions, using one FAWE edit and one retained Dirt history entry. Each placement is [paletteIndex, x, y, z], where paletteIndex is zero-based. Omit every weight in a palette for equal probability, or provide whole percentages totaling 100. Reuse the returned seed to replay a preview. All states and resolved positions are validated before mutation. Keep palettes and the encoded request within the active configured limits.' +
        (toolConfiguration.get_server_status ? ' Those limits are reported by get_server_status.' : '') +
        ' Placement does not trigger Minecraft neighbor physics. Set dryRun=true to preview exact counts. Every committed non-empty edit returns retained edit metadata including its edit ID.',
      inputSchema: SetBlocksInputSchema,
      outputSchema: SetBlocksOutputSchema,
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        logger,
        { operation: 'set_blocks', world: input.world, context, failureContext: 'Could not set blocks' },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.setBlocks, callId, SetBlocksOutputSchema, input);
          requireMatchingEditIdentity(input.world, setBlocksBounds(input), callId, result);
          requireMatchingEditOptions(input.seed, input.dryRun, result);
          requireMatchingSetBlockCount(input.placements.length, result);
          const verb = result.outcome === 'preview' ? 'Would change' : 'Changed';
          const editSummary = result.edit === null ? '' : ` Edit ID: ${result.edit.editId}.`;
          return successResult(
            result,
            `${verb} ${result.changedBlockCount} of ${result.blockCount} requested blocks in ${result.world} using seed ${result.seed}.${editSummary}`,
          );
        },
      ),
  );
  if (!toolConfiguration.set_blocks) setBlocks.disable();

  const getEditHistory = server.registerTool(
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
        logger,
        {
          operation: 'get_edit_history',
          world: input.world,
          context,
          failureContext: 'Could not get edit history',
        },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.getEditHistory, callId, GetEditHistoryOutputSchema, input);
          requireMatchingWorld(input.world, result.world);
          const noun = result.edits.length === 1 ? 'edit' : 'edits';
          return successResult(result, `Found ${result.edits.length} retained undoable ${noun} in ${result.world}.`);
        },
      ),
  );
  if (!toolConfiguration.get_edit_history) getEditHistory.disable();

  const undoEdit = server.registerTool(
    'undo_edit',
    {
      title: 'Undo an edit',
      description:
        'Undo the retained Dirt edit identified by editId in one loaded world. The edit must still be retained and must be the newest retained entry, preventing an intervening edit from being undone accidentally.' +
        (toolConfiguration.get_edit_history ? ' Use get_edit_history to identify that entry.' : ''),
      inputSchema: UndoEditInputSchema,
      outputSchema: UndoEditOutputSchema,
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        logger,
        {
          operation: 'undo_edit',
          world: input.world,
          context,
          failureContext: 'Could not undo the edit',
        },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.undoEdit, callId, UndoEditOutputSchema, input);
          requireMatchingCallId(callId, result.undoCallId, result.edit.editId);
          requireMatchingUndoIdentity(input.world, input.editId, result.edit);
          return successResult(
            result,
            `Undid edit ${result.edit.editId} in ${result.edit.world}, restoring ${result.edit.changedBlockCount} blocks.`,
          );
        },
      ),
  );
  if (!toolConfiguration.undo_edit) undoEdit.disable();
}
