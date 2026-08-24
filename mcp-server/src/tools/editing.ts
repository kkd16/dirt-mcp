import type { CallToolResult, McpServer } from '@modelcontextprotocol/server';
import { randomBytes } from 'node:crypto';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES, MutationBridgeErrorSchema } from '../bridge/contract.ts';
import type { components } from '../generated/openapi.ts';
import type { DirtLogger } from '../logging.ts';
import {
  BlockPositionSchema,
  BoundsSchema,
  MAX_BLOCK_STATE_PATTERNS,
  MAX_PALETTE_ENTRIES,
  NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
  NonBlankStringSchema,
  PalettePlacementSchema,
  PaletteRunSchema,
  READ_WORLD_ANNOTATIONS,
  SignedInt32Schema,
} from './common.ts';
import type { McpToolConfiguration } from './configuration.ts';
import { executeToolCall, successResult } from './execution.ts';

function uniqueStrings(values: readonly string[]): boolean {
  return new Set(values).size === values.length;
}

function uniqueEditIds(values: readonly string[]): boolean {
  return new Set(values.map((value) => value.toLowerCase())).size === values.length;
}

function uniqueEditRecords(records: readonly { readonly editId: string }[]): boolean {
  return uniqueEditIds(records.map((record) => record.editId));
}

export const SourceBlockStatePatternsSchema = z
  .array(NonBlankStringSchema)
  .min(1)
  .max(MAX_BLOCK_STATE_PATTERNS)
  .refine(uniqueStrings, 'Source block-state patterns must be distinct.')
  .meta({ uniqueItems: true })
  .describe('One or more block-state patterns matched as a union.');

const DestinationPaletteEntrySchema = z
  .object({
    blockState: NonBlankStringSchema.describe(
      'Block-state string Paper resolves to the exact state to place; omitted properties use Paper defaults.',
    ),
    weight: z.number().int().min(1).max(100).optional().describe('Whole-number percentage.'),
  })
  .strict();

export const DestinationPaletteSchema = z
  .array(DestinationPaletteEntrySchema)
  .min(1)
  .max(MAX_PALETTE_ENTRIES)
  .superRefine((entries, context) => {
    const weighted = entries.filter((entry) => entry.weight !== undefined);
    if (weighted.length !== 0 && weighted.length !== entries.length) {
      context.addIssue({ code: 'custom', message: 'Supply weights for every destination or for none.' });
    }
    if (weighted.length === entries.length && weighted.reduce((sum, entry) => sum + entry.weight!, 0) !== 100) {
      context.addIssue({ code: 'custom', message: 'Destination weights must total 100.' });
    }
    const states = entries.map((entry) => entry.blockState);
    if (new Set(states).size !== states.length) {
      context.addIssue({ code: 'custom', message: 'Destination block states must be distinct.' });
    }
  })
  .describe('Destination states resolved by Paper, with equal probability or explicit weights totaling 100.');

const SeedSchema = SignedInt32Schema.describe('Signed 32-bit palette seed.');
const PositiveInt32Schema = SignedInt32Schema.positive();
// oxlint-disable-next-line eslint/no-control-regex -- These are precisely the control characters labels forbid.
const FORBIDDEN_EDIT_LABEL_CHARACTER = /[\u0000-\u001F\u007F-\u009F\u2028\u2029]/u;

export const EditLabelSchema = z
  .string()
  .min(1)
  .superRefine((label, context) => {
    // oxlint-disable-next-line typescript/no-misused-spread -- String iteration intentionally counts Unicode code points.
    if ([...label].length > 120) {
      context.addIssue({ code: 'custom', message: 'Label must contain at most 120 Unicode code points.' });
    }
    if (label !== label.trim()) {
      context.addIssue({ code: 'custom', message: 'Label must not have outer whitespace.' });
    }
    if (FORBIDDEN_EDIT_LABEL_CHARACTER.test(label)) {
      context.addIssue({ code: 'custom', message: 'Label must be one line and contain no control characters.' });
    }
  })
  .describe('Concise, single-line description of the reversible edit intent.')
  .meta({
    maxLength: 120,
    pattern: '^(?!\\s)(?!.*\\s$)[^\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]+$',
  });

const EditOptionsInputShape = {
  label: EditLabelSchema,
  seed: SeedSchema.optional().describe('Optional reproducibility seed; omission generates one in Dirt MCP.'),
  maxChangedBlocks: PositiveInt32Schema.optional().describe(
    'Optional per-call changed-block ceiling; omission uses the server-selected ceiling.',
  ),
  dryRun: z.boolean().default(false).describe('Preview without mutation when true.'),
};

const EditOperationSchema = z.enum(['replace_region_blocks', 'set_blocks']);

export const EditRecordSchema = z
  .object({
    editId: z.uuidv4(),
    callId: z.uuidv4(),
    label: EditLabelSchema,
    operation: EditOperationSchema,
    world: NonBlankStringSchema,
    worldId: z.uuid(),
    bounds: BoundsSchema,
    changedBlockCount: z.number().int().positive(),
    completedAt: z.iso.datetime({ offset: true }),
    status: z.enum(['committed', 'recovery_required']),
  })
  .strict()
  .describe('Identity and lifecycle metadata for one retained edit.') satisfies z.ZodType<
  components['schemas']['EditRecord']
>;

const EditOutcomeSchema = z.enum(['preview', 'no_change', 'committed']);

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

const ReplaceRegionBlocksOutputShape = {
  world: NonBlankStringSchema,
  bounds: BoundsSchema,
  seed: SeedSchema,
  matchedBlockCount: z.number().int().nonnegative(),
};

export const ReplaceRegionBlocksOutputSchema = z
  .discriminatedUnion('outcome', [
    z
      .object({
        ...ReplaceRegionBlocksOutputShape,
        outcome: z.literal('preview'),
        edit: z.null(),
        changedBlockCount: z.number().int().nonnegative(),
      })
      .strict(),
    z
      .object({
        ...ReplaceRegionBlocksOutputShape,
        outcome: z.literal('no_change'),
        edit: z.null(),
        changedBlockCount: z.literal(0),
      })
      .strict(),
    z
      .object({
        ...ReplaceRegionBlocksOutputShape,
        outcome: z.literal('committed'),
        edit: EditRecordSchema.extend({
          operation: z.literal('replace_region_blocks'),
          status: z.literal('committed'),
        }),
        changedBlockCount: z.number().int().positive(),
      })
      .strict(),
  ])
  .describe('Completed or previewed block-state replacement.') satisfies z.ZodType<
  components['schemas']['ReplaceRegionBlocksResponse']
>;

const SetBlocksPalettesSchema = z
  .array(DestinationPaletteSchema)
  .max(MAX_PALETTE_ENTRIES)
  .superRefine((palettes, context) => {
    const entryCount = palettes.reduce((total, palette) => total + palette.length, 0);
    if (entryCount > MAX_PALETTE_ENTRIES) {
      context.addIssue({
        code: 'custom',
        message: `Palettes may contain at most ${MAX_PALETTE_ENTRIES} entries in total.`,
      });
    }
  })
  .describe('Weighted palettes referenced by zero-based index, with at most 256 entries in total.');

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
  .describe('One undoable weighted-palette edit from origin-relative block geometry.');

const SetBlocksOutputShape = {
  world: NonBlankStringSchema,
  bounds: BoundsSchema.nullable(),
  seed: SeedSchema,
  blockCount: z.number().int().nonnegative(),
  unchangedBlockCount: z.number().int().nonnegative(),
};

export const SetBlocksOutputSchema = z
  .discriminatedUnion('outcome', [
    z
      .object({
        ...SetBlocksOutputShape,
        outcome: z.literal('preview'),
        edit: z.null(),
        changedBlockCount: z.number().int().nonnegative(),
      })
      .strict(),
    z
      .object({
        ...SetBlocksOutputShape,
        outcome: z.literal('no_change'),
        edit: z.null(),
        changedBlockCount: z.literal(0),
      })
      .strict(),
    z
      .object({
        ...SetBlocksOutputShape,
        outcome: z.literal('committed'),
        edit: EditRecordSchema.extend({ operation: z.literal('set_blocks'), status: z.literal('committed') }),
        changedBlockCount: z.number().int().positive(),
      })
      .strict(),
  ])
  .describe('Completed or previewed palette-based block edit.') satisfies z.ZodType<
  components['schemas']['SetBlocksResponse']
>;

const GetEditHistoryInputSchema = z
  .object({ world: NonBlankStringSchema.describe('Loaded world whose retained edits should be returned.') })
  .strict();

export const GetEditHistoryOutputSchema = z
  .object({
    world: NonBlankStringSchema,
    edits: z
      .array(EditRecordSchema)
      .refine(uniqueEditRecords, 'Edit IDs must be distinct.')
      .meta({ uniqueItems: true })
      .describe('Retained undoable edits ordered newest first.'),
  })
  .strict()
  .describe('Current bounded undoable edit history for one loaded world.') satisfies z.ZodType<
  components['schemas']['GetEditHistoryResponse']
>;

export const UndoEditsInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Loaded world containing the retained edits.'),
    editIds: z
      .array(z.uuidv4())
      .min(1)
      .refine(uniqueEditIds, 'Edit IDs must be distinct.')
      .meta({ uniqueItems: true })
      .describe('Exact newest-first prefix of retained edit IDs to undo.'),
  })
  .strict();

const UndoneEditsSchema = z
  .array(EditRecordSchema)
  .refine(uniqueEditRecords, 'Edit IDs must be distinct.')
  .meta({ uniqueItems: true });

export const UndoEditsOutputSchema = z
  .object({
    outcome: z.literal('completed'),
    world: NonBlankStringSchema,
    undoneEdits: UndoneEditsSchema.min(1),
    undoCallId: z.uuidv4(),
    undoneAt: z.iso.datetime({ offset: true }),
  })
  .strict()
  .describe('Completed undo of the requested newest-first edit prefix.');

const UndoEditsPartialBridgeSchema = z
  .object({
    outcome: z.literal('partial'),
    world: NonBlankStringSchema,
    undoCallId: z.uuidv4(),
    undoneEdits: UndoneEditsSchema,
    failure: MutationBridgeErrorSchema,
  })
  .strict();

const UndoEditsBridgeOutputSchema = z.discriminatedUnion('outcome', [
  UndoEditsOutputSchema,
  UndoEditsPartialBridgeSchema,
]) satisfies z.ZodType<components['schemas']['UndoEditsResponse']>;

type Bounds = z.infer<typeof BoundsSchema>;
type EditRecord = z.infer<typeof EditRecordSchema>;

interface EditRequestCorrelation {
  readonly world: string;
  readonly label: string;
  readonly seed: number;
  readonly dryRun: boolean;
}

interface EditResponseCorrelation {
  readonly world: string;
  readonly bounds: Bounds | null;
  readonly seed: number;
  readonly outcome: z.infer<typeof EditOutcomeSchema>;
  readonly edit: EditRecord | null;
  readonly changedBlockCount: number;
}

function sameUuid(left: string, right: string): boolean {
  return left.toLowerCase() === right.toLowerCase();
}

function sameBounds(left: Bounds, right: Bounds): boolean {
  return (
    left.min.x === right.min.x &&
    left.min.y === right.min.y &&
    left.min.z === right.min.z &&
    left.max.x === right.max.x &&
    left.max.y === right.max.y &&
    left.max.z === right.max.z
  );
}

function normalizedBounds(
  min: components['schemas']['BlockPosition'],
  max: components['schemas']['BlockPosition'],
): Bounds {
  return {
    min: { x: Math.min(min.x, max.x), y: Math.min(min.y, max.y), z: Math.min(min.z, max.z) },
    max: { x: Math.max(min.x, max.x), y: Math.max(min.y, max.y), z: Math.max(min.z, max.z) },
  };
}

function bridgeMismatch(context: z.core.$RefinementCtx, message: string, path: PropertyKey[]): void {
  context.addIssue({ code: 'custom', message, path });
}

function validateEditCorrelation(
  request: EditRequestCorrelation,
  response: EditResponseCorrelation,
  callId: string,
  operation: z.infer<typeof EditOperationSchema>,
  context: z.core.$RefinementCtx,
): void {
  if (response.world !== request.world) {
    bridgeMismatch(context, 'The response world must match the requested world.', ['world']);
  }
  if (response.seed !== request.seed) {
    bridgeMismatch(context, 'The response seed must match the requested seed.', ['seed']);
  }
  if ((response.outcome === 'preview') !== request.dryRun) {
    bridgeMismatch(context, 'The response outcome must match the requested dry-run mode.', ['outcome']);
  }
  if (response.outcome !== 'committed' || response.edit === null) return;

  if (!sameUuid(response.edit.callId, callId)) {
    bridgeMismatch(context, 'The committed edit call ID must match the bridge call ID.', ['edit', 'callId']);
  }
  if (response.edit.label !== request.label) {
    bridgeMismatch(context, 'The committed edit label must match the requested label.', ['edit', 'label']);
  }
  if (response.edit.operation !== operation) {
    bridgeMismatch(context, 'The committed edit operation must match the requested operation.', ['edit', 'operation']);
  }
  if (response.edit.world !== request.world) {
    bridgeMismatch(context, 'The committed edit world must match the requested world.', ['edit', 'world']);
  }
  if (response.edit.changedBlockCount !== response.changedBlockCount) {
    bridgeMismatch(context, 'The committed edit count must match the response count.', ['edit', 'changedBlockCount']);
  }
  if (response.bounds === null || !sameBounds(response.edit.bounds, response.bounds)) {
    bridgeMismatch(context, 'The committed edit bounds must match the response bounds.', ['edit', 'bounds']);
  }
}

export function replaceRegionBlocksBridgeOutputSchema(
  request: components['schemas']['ReplaceRegionBlocksRequest'],
  callId: string,
) {
  return ReplaceRegionBlocksOutputSchema.superRefine((response, context) => {
    validateEditCorrelation(request, response, callId, 'replace_region_blocks', context);
    if (!sameBounds(response.bounds, normalizedBounds(request.min, request.max))) {
      bridgeMismatch(context, 'The response bounds must match the normalized requested bounds.', ['bounds']);
    }
    if (response.changedBlockCount > response.matchedBlockCount) {
      bridgeMismatch(context, 'The changed-block count cannot exceed the matched-block count.', ['changedBlockCount']);
    }
  });
}

export function setBlocksBridgeOutputSchema(request: components['schemas']['SetBlocksRequest'], callId: string) {
  return SetBlocksOutputSchema.superRefine((response, context) => {
    validateEditCorrelation(request, response, callId, 'set_blocks', context);
    if (response.changedBlockCount + response.unchangedBlockCount !== response.blockCount) {
      bridgeMismatch(context, 'Changed and unchanged block counts must total the represented block count.', [
        'blockCount',
      ]);
    }
  });
}

export function getEditHistoryBridgeOutputSchema(request: components['schemas']['GetEditHistoryRequest']) {
  return GetEditHistoryOutputSchema.superRefine((response, context) => {
    if (response.world !== request.world) {
      bridgeMismatch(context, 'The response world must match the requested world.', ['world']);
    }
    for (const [index, edit] of response.edits.entries()) {
      if (edit.world !== request.world) {
        bridgeMismatch(context, 'Every retained edit must belong to the requested world.', ['edits', index, 'world']);
      }
    }
  });
}

export function undoEditsBridgeOutputSchema(request: components['schemas']['UndoEditsRequest'], callId: string) {
  return UndoEditsBridgeOutputSchema.superRefine((response, context) => {
    if (response.world !== request.world) {
      bridgeMismatch(context, 'The response world must match the requested world.', ['world']);
    }
    if (!sameUuid(response.undoCallId, callId)) {
      bridgeMismatch(context, 'The undo call ID must match the bridge call ID.', ['undoCallId']);
    }
    for (const [index, edit] of response.undoneEdits.entries()) {
      const requestedId = request.editIds[index];
      if (requestedId === undefined || !sameUuid(edit.editId, requestedId)) {
        bridgeMismatch(context, 'Undone edits must be the exact requested newest-first prefix.', [
          'undoneEdits',
          index,
          'editId',
        ]);
      }
      if (edit.world !== request.world) {
        bridgeMismatch(context, 'Every undone edit must belong to the requested world.', [
          'undoneEdits',
          index,
          'world',
        ]);
      }
    }

    if (response.outcome === 'completed') {
      if (response.undoneEdits.length !== request.editIds.length) {
        bridgeMismatch(context, 'A completed undo must contain every requested edit.', ['undoneEdits']);
      }
      return;
    }

    const failedEditId = request.editIds[response.undoneEdits.length];
    if (failedEditId === undefined) {
      bridgeMismatch(context, 'A partial undo must stop before the requested prefix is complete.', ['undoneEdits']);
    } else if (!sameUuid(response.failure.editId, failedEditId)) {
      bridgeMismatch(context, 'A partial undo failure must identify the next requested edit.', ['failure', 'editId']);
    }
  });
}

const UndoEditsPartialToolResultSchema = z
  .object({
    callId: z.uuidv4(),
    error: MutationBridgeErrorSchema,
    world: NonBlankStringSchema,
    undoneEdits: UndoneEditsSchema,
  })
  .strict();

function editSummary(result: {
  readonly outcome: z.infer<typeof EditOutcomeSchema>;
  readonly changedBlockCount: number;
  readonly edit: z.infer<typeof EditRecordSchema> | null;
  readonly world: string;
}): string {
  const verb = result.outcome === 'preview' ? 'Would change' : 'Changed';
  const edit = result.edit === null ? '' : ` Edit ID: ${result.edit.editId}.`;
  return `${verb} ${result.changedBlockCount} blocks in ${result.world}.${edit}`;
}

function generatedSeed(): number {
  return randomBytes(4).readInt32BE(0);
}

function bridgePalette(
  palette: z.infer<typeof DestinationPaletteSchema>,
): readonly components['schemas']['DestinationPaletteEntry'][] {
  return palette.map((entry) =>
    entry.weight === undefined
      ? { blockState: entry.blockState }
      : { blockState: entry.blockState, weight: entry.weight },
  );
}

function partialUndoResult(result: z.infer<typeof UndoEditsPartialBridgeSchema>): CallToolResult {
  const structuredContent = UndoEditsPartialToolResultSchema.parse({
    callId: result.undoCallId,
    error: result.failure,
    world: result.world,
    undoneEdits: result.undoneEdits,
  });
  return {
    content: [
      {
        type: 'text',
        text: `Undo stopped after ${result.undoneEdits.length} edits: ${result.failure.message}`,
      },
    ],
    structuredContent,
    isError: true,
  };
}

export function registerEditingTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  if (toolConfiguration.replace_region_blocks) {
    server.registerTool(
      'replace_region_blocks',
      {
        title: 'Replace region blocks',
        description:
          'Replace matching block states throughout an inclusive region. Supply a concise edit label; use dryRun to preview and reuse the returned seed to reproduce a palette choice.',
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
            const request = {
              ...input,
              destinationPalette: bridgePalette(input.destinationPalette),
              seed: input.seed ?? generatedSeed(),
              maxChangedBlocks: input.maxChangedBlocks ?? null,
            } satisfies components['schemas']['ReplaceRegionBlocksRequest'];
            const result = await bridge.request(
              BRIDGE_ROUTES.replaceRegionBlocks,
              callId,
              replaceRegionBlocksBridgeOutputSchema(request, callId),
              request,
              context.mcpReq.signal,
            );
            return successResult(result, editSummary(result));
          },
        ),
    );
  }

  if (toolConfiguration.set_blocks) {
    server.registerTool(
      'set_blocks',
      {
        title: 'Set blocks',
        description:
          'Place blocks from weighted palettes using origin-relative placements and cuboid runs. Supply a concise edit label; use dryRun to preview.',
        inputSchema: SetBlocksInputSchema,
        outputSchema: SetBlocksOutputSchema,
        annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
      },
      async (input, context) =>
        executeToolCall(
          logger,
          { operation: 'set_blocks', world: input.world, context, failureContext: 'Could not set blocks' },
          async (callId) => {
            const request = {
              ...input,
              palettes: input.palettes.map(bridgePalette),
              placements: input.placements.map(([palette, x, y, blockZ]) => [palette, x, y, blockZ] as const),
              runs: input.runs.map(
                ([palette, x, y, blockZ, toX, toY, toZ]) => [palette, x, y, blockZ, toX, toY, toZ] as const,
              ),
              seed: input.seed ?? generatedSeed(),
              maxChangedBlocks: input.maxChangedBlocks ?? null,
            } satisfies components['schemas']['SetBlocksRequest'];
            const result = await bridge.request(
              BRIDGE_ROUTES.setBlocks,
              callId,
              setBlocksBridgeOutputSchema(request, callId),
              request,
              context.mcpReq.signal,
            );
            return successResult(result, editSummary(result));
          },
        ),
    );
  }

  if (toolConfiguration.get_edit_history) {
    server.registerTool(
      'get_edit_history',
      {
        title: 'Get edit history',
        description: 'Return currently retained undoable Dirt edits for one loaded world, newest first.',
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
            const request: components['schemas']['GetEditHistoryRequest'] = input;
            const result = await bridge.request(
              BRIDGE_ROUTES.getEditHistory,
              callId,
              getEditHistoryBridgeOutputSchema(request),
              request,
              context.mcpReq.signal,
            );
            const noun = result.edits.length === 1 ? 'edit' : 'edits';
            return successResult(result, `Found ${result.edits.length} retained ${noun} in ${result.world}.`);
          },
        ),
    );
  }

  if (toolConfiguration.undo_edits) {
    server.registerTool(
      'undo_edits',
      {
        title: 'Undo edits',
        description: 'Undo a non-empty newest-first prefix of retained Dirt edits in one loaded world.',
        inputSchema: UndoEditsInputSchema,
        outputSchema: UndoEditsOutputSchema,
        annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
      },
      async (input, context) =>
        executeToolCall(
          logger,
          { operation: 'undo_edits', world: input.world, context, failureContext: 'Could not undo the edits' },
          async (callId) => {
            const request: components['schemas']['UndoEditsRequest'] = input;
            const result = await bridge.request(
              BRIDGE_ROUTES.undoEdits,
              callId,
              undoEditsBridgeOutputSchema(request, callId),
              request,
              context.mcpReq.signal,
            );
            if (result.outcome === 'partial') return partialUndoResult(result);
            const noun = result.undoneEdits.length === 1 ? 'edit' : 'edits';
            return successResult(result, `Undid ${result.undoneEdits.length} ${noun} in ${result.world}.`);
          },
        ),
    );
  }
}
