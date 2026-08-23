import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES, BridgeErrorSchema } from '../bridge/contract.ts';
import type { DirtLogger } from '../logging.ts';
import { ToolFailure, toolOutputSchema } from '../bridge/errors.ts';
import {
  BlockPositionSchema,
  BoundsSchema,
  MAX_BLOCK_STATE_ENTRIES,
  NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
  NonBlankStringSchema,
  PalettePlacementSchema,
  PaletteRunSchema,
  READ_WORLD_ANNOTATIONS,
  SignedInt32Schema,
} from './common.ts';
import {
  isForwardRun,
  resolveOffset,
  runContains,
  runsOverlap,
  structureBlockCount,
  structureBounds,
} from './block-structure.ts';
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
const PositiveInt32Schema = SignedInt32Schema.positive();
// oxlint-disable-next-line eslint/no-control-regex -- These are precisely the control characters labels forbid.
const FORBIDDEN_EDIT_LABEL_CHARACTER = /[\u0000-\u001F\u007F-\u009F\u2028\u2029]/u;

export const EditLabelSchema = z
  .string()
  .min(1)
  .superRefine((label, context) => {
    // oxlint-disable-next-line typescript/no-misused-spread -- String iteration intentionally counts Unicode code points.
    if ([...label].length > 120) {
      context.addIssue({ code: 'custom', message: 'Label must contain between 1 and 120 Unicode code points.' });
    }
    if (label !== label.trim()) {
      context.addIssue({ code: 'custom', message: 'Label must not have leading or trailing whitespace.' });
    }
    if (FORBIDDEN_EDIT_LABEL_CHARACTER.test(label)) {
      context.addIssue({
        code: 'custom',
        message: 'Label must be one line and must not contain control characters or Unicode line separators.',
      });
    }
  })
  .describe(
    'Required concise description of one reversible edit intent: 1-120 Unicode code points, already trimmed, single-line, and free of control characters.',
  )
  .meta({
    maxLength: 120,
    pattern: '^(?!\\s)(?!.*\\s$)[^\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]+$',
  });

const EditOptionsInputShape = {
  label: EditLabelSchema,
  seed: SeedSchema.optional().describe(
    'Optional reproducibility seed. Omission generates a fresh seed returned in the result.',
  ),
  maxChangedBlocks: PositiveInt32Schema.optional().describe(
    'Optional per-call changed-block ceiling. The effective ceiling is the lower of this value and the server limit.',
  ),
  dryRun: z
    .boolean()
    .optional()
    .describe('True previews without mutation, false executes the edit, and omission uses the plugin default.'),
};

const EditOperationSchema = z.enum(['replace_region_blocks', 'set_blocks']);

export const EditRecordSchema = z
  .object({
    editId: z.uuidv4().describe('Stable identifier for this edit transaction.'),
    callId: z.uuidv4().describe('Bridge call identifier that created this edit.'),
    label: EditLabelSchema,
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
  readonly bounds: z.infer<typeof BoundsSchema> | null;
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
      result.bounds !== null &&
      sameBounds(edit.bounds, result.bounds)
    );
  }
  if (result.outcome === 'no_change') return result.edit === null && result.changedBlockCount === 0;
  return result.edit === null;
}

const EditResultMessage =
  'Committed outcomes require matching committed edit metadata and a positive changedBlockCount; preview and no_change outcomes require a null edit.';

export function requireMatchingCallId(expected: string, actual: string, editId?: string): void {
  if (actual.toLowerCase() !== expected.toLowerCase()) {
    throw new ToolFailure({
      code: 'bridge_invalid_response',
      message: 'Paper bridge response call ID did not match the request.',
      ...(editId === undefined ? {} : { editId }),
    });
  }
}

export function requireMatchingEditIdentity(
  expectedWorld: string,
  expectedBounds: z.infer<typeof BoundsSchema>,
  expectedLabel: string,
  expectedCallId: string,
  actual: EditResultMetadata,
): void {
  const editId = actual.edit?.editId;
  if (actual.world !== expectedWorld || actual.bounds === null || !sameBounds(actual.bounds, expectedBounds)) {
    throw new ToolFailure({
      code: 'bridge_invalid_response',
      message: 'Paper bridge edit result did not match the requested world and bounds.',
      ...(editId === undefined ? {} : { editId }),
    });
  }
  if (actual.edit !== null) {
    requireMatchingCallId(expectedCallId, actual.edit.callId, actual.edit.editId);
    if (actual.edit.label !== expectedLabel) {
      throw new ToolFailure({
        code: 'bridge_invalid_response',
        message: 'Paper bridge edit label did not match the request.',
        editId: actual.edit.editId,
      });
    }
  }
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

export const ReplaceRegionBlocksInputSchema = z
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

const SetBlocksPalettesSchema = z
  .array(DestinationPaletteSchema)
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
    `Weighted block-state palettes referenced by zero-based index; empty only for empty geometry, with at most ${MAX_BLOCK_STATE_ENTRIES} entries total.`,
  );

export const SetBlocksInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    origin: BlockPositionSchema.describe('Absolute anchor added to every placement offset.'),
    palettes: SetBlocksPalettesSchema,
    placements: z.array(PalettePlacementSchema).describe('Palette-indexed origin-relative block placements.'),
    runs: z.array(PaletteRunSchema).describe('Palette-indexed origin-relative inclusive cuboids.'),
    ...EditOptionsInputShape,
  })
  .strict()
  .superRefine((input, context) => {
    const empty = input.placements.length === 0 && input.runs.length === 0;
    if (empty && input.palettes.length !== 0) {
      context.addIssue({
        code: 'custom',
        path: ['palettes'],
        message: 'Palettes must be empty when placements and runs are empty.',
      });
      return;
    }
    if (!empty && input.palettes.length === 0) {
      context.addIssue({
        code: 'custom',
        path: ['palettes'],
        message: 'At least one palette is required for non-empty geometry.',
      });
      return;
    }

    const positions = new Set<string>();
    for (const [placementIndex, placement] of input.placements.entries()) {
      const paletteIndex = placement[0];
      if (paletteIndex >= input.palettes.length) {
        context.addIssue({
          code: 'custom',
          path: ['placements', placementIndex, 0],
          message: 'Palette index must reference an entry in palettes.',
        });
        return;
      }
      const resolved = resolveOffset(input.origin, placement[1], placement[2], placement[3]);
      if (resolved === undefined) {
        context.addIssue({
          code: 'custom',
          path: ['placements', placementIndex],
          message: 'Resolved position must use signed 32-bit coordinates.',
        });
        return;
      }
      const key = `${resolved.x},${resolved.y},${resolved.z}`;
      if (positions.has(key)) {
        context.addIssue({
          code: 'custom',
          path: ['placements', placementIndex],
          message: 'Resolved block positions must be distinct.',
        });
        return;
      }
      positions.add(key);
    }

    for (const [runIndex, run] of input.runs.entries()) {
      const paletteIndex = run[0];
      if (paletteIndex >= input.palettes.length) {
        context.addIssue({
          code: 'custom',
          path: ['runs', runIndex, 0],
          message: 'Palette index must reference an entry in palettes.',
        });
        return;
      }
      if (!isForwardRun(run)) {
        context.addIssue({
          code: 'custom',
          path: ['runs', runIndex],
          message: 'Run must use component-wise forward inclusive corners.',
        });
        return;
      }
      if (
        resolveOffset(input.origin, run[1], run[2], run[3]) === undefined ||
        resolveOffset(input.origin, run[4], run[5], run[6]) === undefined
      ) {
        context.addIssue({
          code: 'custom',
          path: ['runs', runIndex],
          message: 'Resolved run corners must use signed 32-bit coordinates.',
        });
        return;
      }
      for (const [placementIndex, placement] of input.placements.entries()) {
        if (runContains(run, placement[1], placement[2], placement[3])) {
          context.addIssue({
            code: 'custom',
            path: ['runs', runIndex],
            message: `Run overlaps placements[${placementIndex}].`,
          });
          return;
        }
      }
      for (const [otherIndex, other] of input.runs.entries()) {
        if (otherIndex >= runIndex) break;
        if (runsOverlap(run, other)) {
          context.addIssue({
            code: 'custom',
            path: ['runs', runIndex],
            message: `Run overlaps runs[${otherIndex}].`,
          });
          return;
        }
      }
    }
  })
  .describe('One undoable weighted-palette edit from origin-relative placements and cuboids.');

export const SetBlocksOutputSchema = z
  .object({
    world: z.string().min(1).describe('Edited world name.'),
    bounds: BoundsSchema.nullable().describe(
      'Smallest inclusive bounds containing every requested block, or null when empty.',
    ),
    palettes: SetBlocksPalettesSchema.describe('Canonical palettes used by the edit.'),
    seed: SeedSchema.describe('Supplied request seed, or the generated seed when the request omitted one.'),
    outcome: EditOutcomeSchema.describe('Explicit dryRun=true requires preview; false excludes preview.'),
    edit: EditRecordSchema.nullable().describe('Retained edit metadata, present only for a committed outcome.'),
    blockCount: z.number().int().nonnegative().describe('Number of expanded unique requested blocks.'),
    changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
    unchangedBlockCount: z.number().int().nonnegative().describe('Blocks already in their requested state.'),
  })
  .strict()
  .refine(
    (result) =>
      result.blockCount === 0
        ? result.bounds === null &&
          result.palettes.length === 0 &&
          result.outcome === 'no_change' &&
          result.edit === null &&
          result.changedBlockCount === 0 &&
          result.unchangedBlockCount === 0
        : result.bounds !== null && result.palettes.length > 0 && hasConsistentEditResult(result, 'set_blocks'),
    EditResultMessage,
  )
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
  expectedMaxChangedBlocks: number | undefined,
  actual: EditResultMetadata & { readonly seed: number },
): void {
  requireMatchingSeed(expectedSeed, actual);
  if (
    (expectedDryRun === true && actual.outcome !== 'preview') ||
    (expectedDryRun === false && actual.outcome === 'preview')
  ) {
    invalidEditResult(actual, 'Paper bridge edit outcome did not match the explicit dryRun request.');
  }
  if (expectedMaxChangedBlocks !== undefined && actual.changedBlockCount > expectedMaxChangedBlocks) {
    invalidEditResult(actual, 'Paper bridge edit result exceeded the requested maxChangedBlocks ceiling.');
  }
}

function requireMatchingSeed(
  expectedSeed: number | undefined,
  actual: EditResultMetadata & { readonly seed: number },
): void {
  if (expectedSeed !== undefined && actual.seed !== expectedSeed) {
    invalidEditResult(actual, 'Paper bridge edit result seed did not match the request.');
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

export function requireMatchingSetBlockCount(
  expectedCount: bigint,
  actual: EditResultMetadata & {
    readonly blockCount: number;
  },
): void {
  if (BigInt(actual.blockCount) !== expectedCount) {
    invalidEditResult(actual, 'Paper bridge blockCount did not match the expanded requested geometry.');
  }
}

export const UndoEditsInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of the loaded world containing the retained edits.'),
    editIds: z
      .array(z.uuidv4())
      .min(1)
      .superRefine((editIds, context) => {
        const seen = new Set<string>();
        editIds.forEach((editId, index) => {
          const normalized = editId.toLowerCase();
          if (seen.has(normalized)) {
            context.addIssue({
              code: 'custom',
              path: [index],
              message: 'Edit IDs must be case-insensitively unique.',
            });
          }
          seen.add(normalized);
        });
      })
      .meta({ uniqueItems: true })
      .describe('Exact newest-first prefix of retained edit IDs to undo.'),
  })
  .strict()
  .describe('Identity-checked batch undo of a newest-first retained-history prefix.');

export const UndoEditsOutputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Loaded world whose edits were undone.'),
    edits: z
      .array(EditRecordSchema)
      .min(1)
      .meta({ uniqueItems: true })
      .describe('Consumed edit records in the same newest-first order requested.'),
    undoCallId: z.uuidv4().describe('Bridge call identifier that performed the undo.'),
    undoneAt: z.iso.datetime({ offset: true }).describe('Timestamp at which the complete batch undo finished.'),
  })
  .strict()
  .describe('Result of undoing and consuming an identified newest-first edit prefix.');

const UndoneEditPrefixSchema = z
  .array(EditRecordSchema)
  .meta({ uniqueItems: true })
  .describe('Successfully restored and consumed newest-first prefix before execution stopped.');

const UndoEditsFailureResponseSchema = z
  .object({
    error: z.intersection(BridgeErrorSchema, z.object({ editId: z.uuidv4() }).passthrough()),
    undoneEdits: UndoneEditPrefixSchema,
  })
  .strict();

function requireMatchingUndonePrefix(
  expectedWorld: string,
  expectedEditIds: readonly string[],
  edits: readonly z.infer<typeof EditRecordSchema>[],
): void {
  if (edits.length > expectedEditIds.length) {
    throw new ToolFailure({
      code: 'bridge_invalid_response',
      message: 'Paper bridge undo result contained more edits than requested.',
    });
  }
  edits.forEach((edit, index) => requireMatchingUndoIdentity(expectedWorld, expectedEditIds[index]!, edit));
}

function requireMatchingUndoFailure(
  expectedWorld: string,
  expectedEditIds: readonly string[],
  undoneEdits: readonly z.infer<typeof EditRecordSchema>[],
  failedEditId: string,
): void {
  requireMatchingUndonePrefix(expectedWorld, expectedEditIds, undoneEdits);
  const expectedFailedEditId = expectedEditIds[undoneEdits.length];
  if (expectedFailedEditId === undefined || expectedFailedEditId.toLowerCase() !== failedEditId.toLowerCase()) {
    throw new ToolFailure({
      code: 'bridge_invalid_response',
      message: 'Paper bridge undo failure did not identify the next requested edit.',
      editId: failedEditId,
    });
  }
}

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
        'Replace blocks matching any source pattern throughout an inclusive region. Label the single reversible intent concisely. Omitted source properties match any value. Destination entries are exact states; omit every weight for equal probability or provide whole percentages totaling 100. Reuse the returned seed to reproduce a preview. Set maxChangedBlocks for a stricter per-call ceiling and dryRun=true to preview without mutation. Every committed non-empty edit returns retained edit metadata including its edit ID and label.',
      inputSchema: ReplaceRegionBlocksInputSchema,
      outputSchema: toolOutputSchema(ReplaceRegionBlocksOutputSchema),
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
          requireMatchingEditIdentity(input.world, bounds, input.label, callId, result);
          requireMatchingEditOptions(input.seed, input.dryRun, input.maxChangedBlocks, result);
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

  const setBlocks = server.registerTool(
    'set_blocks',
    {
      title: 'Set blocks',
      description:
        'Place blocks from weighted palettes using origin-relative singleton placements and forward inclusive cuboids. Label the single reversible intent concisely. Each placement is [paletteIndex, x, y, z]; each run is [paletteIndex, x, y, z, toX, toY, toZ]. Palette indexes are zero-based and represented blocks cannot overlap. Omit every weight in a palette for equal probability, or provide whole percentages totaling 100. Reuse a get_blocks structure with a new origin and label to copy it elsewhere. Empty palettes, placements, and runs are a valid no-op. Reuse the returned seed to replay a preview. All states and resolved geometry are validated before mutation. Keep the expanded block count, palettes, and encoded request within the active configured limits.' +
        (toolConfiguration.get_server_status
          ? ' Those limits are reported by get_server_status with include.configuration=true.'
          : '') +
        ' Placement does not trigger Minecraft neighbor physics. Set maxChangedBlocks for a stricter per-call ceiling and dryRun=true to preview exact counts. Every committed non-empty edit returns retained edit metadata including its edit ID and label.',
      inputSchema: SetBlocksInputSchema,
      outputSchema: toolOutputSchema(SetBlocksOutputSchema),
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        logger,
        { operation: 'set_blocks', world: input.world, context, failureContext: 'Could not set blocks' },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.setBlocks, callId, SetBlocksOutputSchema, input);
          const bounds = structureBounds(input.origin, input.placements, input.runs);
          const expectedBlockCount = structureBlockCount(input.placements, input.runs);
          if (bounds === null) {
            requireMatchingWorld(input.world, result.world);
            requireMatchingSeed(input.seed, result);
          } else {
            requireMatchingEditIdentity(input.world, bounds, input.label, callId, result);
            requireMatchingEditOptions(input.seed, input.dryRun, input.maxChangedBlocks, result);
          }
          requireMatchingSetBlockCount(expectedBlockCount, result);
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
      outputSchema: toolOutputSchema(GetEditHistoryOutputSchema),
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

  const undoEdits = server.registerTool(
    'undo_edits',
    {
      title: 'Undo edits',
      description:
        'Undo and consume a non-empty newest-first prefix of retained Dirt edits in one loaded world. editIds must exactly match history order, so entries cannot be skipped.' +
        (toolConfiguration.get_edit_history
          ? ' Use get_edit_history immediately beforehand to select the prefix, and re-read it after partial or ambiguous failures.'
          : ''),
      inputSchema: UndoEditsInputSchema,
      outputSchema: toolOutputSchema(UndoEditsOutputSchema, {
        undoneEdits: UndoneEditPrefixSchema,
      }),
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        logger,
        {
          operation: 'undo_edits',
          world: input.world,
          context,
          failureContext: 'Could not undo the edits',
        },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.undoEdits, callId, UndoEditsOutputSchema, input, {
            schema: UndoEditsFailureResponseSchema,
            select: (failure) => {
              requireMatchingUndoFailure(input.world, input.editIds, failure.undoneEdits, failure.error.editId);
              return { undoneEdits: failure.undoneEdits };
            },
          });
          requireMatchingCallId(callId, result.undoCallId);
          requireMatchingWorld(input.world, result.world);
          if (result.edits.length !== input.editIds.length) {
            throw new ToolFailure({
              code: 'bridge_invalid_response',
              message: 'Paper bridge undo result did not contain every requested edit.',
            });
          }
          requireMatchingUndonePrefix(input.world, input.editIds, result.edits);
          const changedBlockCount = result.edits.reduce((sum, edit) => sum + edit.changedBlockCount, 0);
          const noun = result.edits.length === 1 ? 'edit' : 'edits';
          return successResult(
            result,
            `Undid ${result.edits.length} ${noun} in ${result.world}, restoring ${changedBlockCount} change entries.`,
          );
        },
      ),
  );
  if (!toolConfiguration.undo_edits) undoEdits.disable();
}
