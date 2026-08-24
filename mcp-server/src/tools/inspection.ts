import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import type { components } from '../generated/openapi.ts';
import type { DirtLogger } from '../logging.ts';
import {
  BlockPositionSchema,
  BoundsSchema,
  DimensionsSchema,
  INT32_MAX,
  MAX_BLOCK_STATE_PATTERNS,
  MAX_PALETTE_ENTRIES,
  NonBlankStringSchema,
  PalettePlacementSchema,
  PaletteRunSchema,
  READ_WORLD_ANNOTATIONS,
} from './common.ts';
import type { McpToolConfiguration } from './configuration.ts';
import { executeToolCall, successResult } from './execution.ts';

const DEFAULT_MAX_RESULTS = 1_024;

const BlockStatePatternListSchema = z
  .array(NonBlankStringSchema)
  .max(MAX_BLOCK_STATE_PATTERNS)
  .refine((patterns) => new Set(patterns).size === patterns.length, 'Block-state patterns must be distinct.')
  .meta({ uniqueItems: true });

const CountRegionBlockStatesInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
    max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
  })
  .strict()
  .describe('Region whose canonical block states should be counted.');

const CountRegionBlockStatesOutputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Inspected world name.'),
    bounds: BoundsSchema,
    dimensions: DimensionsSchema,
    volume: z.number().int().positive().describe('Total blocks scanned, including air.'),
    blockStateCounts: z
      .record(NonBlankStringSchema, z.number().int().nonnegative())
      .describe('Canonical block-state string to occurrence count.'),
  })
  .strict()
  .describe('Complete block-state histogram for the region.') satisfies z.ZodType<
  components['schemas']['CountRegionBlockStatesResponse']
>;

export const GetBlocksInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
    max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
    includeBlockStatePatterns: BlockStatePatternListSchema.default([]).describe(
      'Optional allowlist of block-state patterns; an empty list allows all states.',
    ),
    excludeBlockStatePatterns: BlockStatePatternListSchema.default([]).describe(
      'Block-state patterns rejected after include filtering.',
    ),
    includeAir: z.boolean().default(false).describe('Whether air-family states may match.'),
    maxResults: z
      .number()
      .int()
      .min(1)
      .max(INT32_MAX)
      .default(DEFAULT_MAX_RESULTS)
      .describe('Maximum returned placements plus runs; defaults to 1024.'),
  })
  .strict()
  .refine(
    (input) =>
      input.includeBlockStatePatterns.length + input.excludeBlockStatePatterns.length <= MAX_BLOCK_STATE_PATTERNS,
    { message: `Include and exclude patterns may contain at most ${MAX_BLOCK_STATE_PATTERNS} entries combined.` },
  )
  .describe('Filters for replay-ready exact region block data.');

const ExactPaletteEntrySchema = z
  .object({ blockState: NonBlankStringSchema.describe('Exact canonical block state represented by this palette.') })
  .strict();

const ExactPalettesSchema = z
  .array(z.tuple([ExactPaletteEntrySchema]))
  .max(MAX_PALETTE_ENTRIES)
  .describe('Exact singleton palettes referenced by placements and runs.');

export const ExactBlockStructureOutputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Inspected world name.'),
    origin: BlockPositionSchema.describe('Origin used by every relative tuple.'),
    palettes: ExactPalettesSchema,
    placements: z.array(PalettePlacementSchema).describe('Single matching blocks as origin-relative tuples.'),
    runs: z.array(PaletteRunSchema).describe('Matching blocks packed as origin-relative inclusive cuboids.'),
  })
  .strict()
  .describe('Replay-ready exact block geometry.') satisfies z.ZodType<
  components['schemas']['ExactBlockStructureResponse']
>;

const OrthographicViewDirectionSchema = z
  .enum(['north', 'east', 'south', 'west', 'up', 'down'])
  .describe('World-axis scan direction.');

export const ScanOrthographicViewInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    origin: BlockPositionSchema.describe('View anchor; scanning begins one block away.'),
    direction: OrthographicViewDirectionSchema,
    horizontalRadius: z.number().int().min(0).max(INT32_MAX),
    verticalRadius: z.number().int().min(0).max(INT32_MAX),
    maxDistance: z.number().int().min(1).max(INT32_MAX),
    depth: z
      .number()
      .int()
      .min(0)
      .max(INT32_MAX)
      .default(0)
      .describe('Zero-based non-air hit to return per sightline.'),
    maxResults: z
      .number()
      .int()
      .min(1)
      .max(INT32_MAX)
      .default(DEFAULT_MAX_RESULTS)
      .describe('Maximum returned placements plus runs; defaults to 1024.'),
  })
  .strict()
  .describe('Bounded orthographic sightlines scanned for a selected non-air depth.');

export function registerInspectionTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  if (toolConfiguration.count_region_block_states) {
    server.registerTool(
      'count_region_block_states',
      {
        title: 'Count region block states',
        description: 'Return a complete canonical block-state histogram for an inclusive region.',
        inputSchema: CountRegionBlockStatesInputSchema,
        outputSchema: CountRegionBlockStatesOutputSchema,
        annotations: READ_WORLD_ANNOTATIONS,
      },
      async (input, context) =>
        executeToolCall(
          logger,
          {
            operation: 'count_region_block_states',
            world: input.world,
            context,
            failureContext: 'Could not count region block states',
          },
          async (callId) => {
            const request: components['schemas']['CountRegionBlockStatesRequest'] = input;
            const result = await bridge.request(
              BRIDGE_ROUTES.countRegionBlockStates,
              callId,
              CountRegionBlockStatesOutputSchema,
              request,
              context.mcpReq.signal,
            );
            return successResult(
              result,
              `Counted ${result.volume} blocks across ${Object.keys(result.blockStateCounts).length} block states in ${result.world}.`,
            );
          },
        ),
    );
  }

  if (toolConfiguration.get_blocks) {
    server.registerTool(
      'get_blocks',
      {
        title: 'Get blocks',
        description: 'Return filtered exact block states as replay-ready palettes, placements, and cuboid runs.',
        inputSchema: GetBlocksInputSchema,
        outputSchema: ExactBlockStructureOutputSchema,
        annotations: READ_WORLD_ANNOTATIONS,
      },
      async (input, context) =>
        executeToolCall(
          logger,
          { operation: 'get_blocks', world: input.world, context, failureContext: 'Could not get blocks' },
          async (callId) => {
            const request: components['schemas']['GetBlocksRequest'] = input;
            const result = await bridge.request(
              BRIDGE_ROUTES.getBlocks,
              callId,
              ExactBlockStructureOutputSchema,
              request,
              context.mcpReq.signal,
            );
            return successResult(
              result,
              `Structure entries: ${result.placements.length + result.runs.length}; palettes: ${result.palettes.length}; world: ${result.world}.`,
            );
          },
        ),
    );
  }

  if (toolConfiguration.scan_orthographic_view) {
    server.registerTool(
      'scan_orthographic_view',
      {
        title: 'Scan an orthographic view',
        description: 'Return a selected non-air depth on bounded world-axis sightlines as replay-ready block geometry.',
        inputSchema: ScanOrthographicViewInputSchema,
        outputSchema: ExactBlockStructureOutputSchema,
        annotations: READ_WORLD_ANNOTATIONS,
      },
      async (input, context) =>
        executeToolCall(
          logger,
          {
            operation: 'scan_orthographic_view',
            world: input.world,
            context,
            failureContext: 'Could not scan the orthographic view',
          },
          async (callId) => {
            const request: components['schemas']['ScanOrthographicViewRequest'] = input;
            const result = await bridge.request(
              BRIDGE_ROUTES.scanOrthographicView,
              callId,
              ExactBlockStructureOutputSchema,
              request,
              context.mcpReq.signal,
            );
            return successResult(
              result,
              `Structure entries: ${result.placements.length + result.runs.length}; palettes: ${result.palettes.length}; world: ${result.world}.`,
            );
          },
        ),
    );
  }
}
