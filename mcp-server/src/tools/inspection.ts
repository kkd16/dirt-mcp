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
  PositiveInt32Schema,
  READ_WORLD_ANNOTATIONS,
  normalizedBounds,
  sameBlockPosition,
  sameBounds,
  type Bounds,
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
    volume: PositiveInt32Schema.describe('Total blocks scanned, including air.'),
    blockStateCounts: z
      .record(NonBlankStringSchema, PositiveInt32Schema)
      .refine((counts) => Object.keys(counts).length > 0, 'The histogram must contain at least one block state.')
      .meta({ minProperties: 1 })
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
      .describe(
        'Caller ceiling on returned placements plus runs; Paper may enforce a lower ceiling. Defaults to 1024.',
      ),
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
  .meta({ uniqueItems: true })
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
  .superRefine((structure, context) => {
    const geometryCount = structure.placements.length + structure.runs.length;
    if ((structure.palettes.length === 0) !== (geometryCount === 0)) {
      context.addIssue({
        code: 'custom',
        message: 'Palettes must be empty exactly when the structure has no geometry.',
        path: ['palettes'],
      });
    }

    const states = structure.palettes.map(([entry]) => entry.blockState);
    if (new Set(states).size !== states.length) {
      context.addIssue({
        code: 'custom',
        message: 'Exact palettes must contain distinct block states.',
        path: ['palettes'],
      });
    }

    for (const [index, [paletteIndex]] of structure.placements.entries()) {
      if (paletteIndex >= structure.palettes.length) {
        context.addIssue({
          code: 'custom',
          message: 'Placement palette index must reference an existing palette.',
          path: ['placements', index, 0],
        });
      }
    }
    for (const [index, [paletteIndex]] of structure.runs.entries()) {
      if (paletteIndex >= structure.palettes.length) {
        context.addIssue({
          code: 'custom',
          message: 'Run palette index must reference an existing palette.',
          path: ['runs', index, 0],
        });
      }
    }
  })
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
      .describe(
        'Caller ceiling on returned placements plus runs; Paper may enforce a lower ceiling. Defaults to 1024.',
      ),
  })
  .strict()
  .describe('Bounded orthographic sightlines scanned for a selected non-air depth.');

export function countRegionBlockStatesBridgeOutputSchema(
  request: components['schemas']['CountRegionBlockStatesRequest'],
) {
  return CountRegionBlockStatesOutputSchema.superRefine((response, context) => {
    const expectedBounds = normalizedBounds(request.min, request.max);
    const expectedDimensions = {
      x: expectedBounds.max.x - expectedBounds.min.x + 1,
      y: expectedBounds.max.y - expectedBounds.min.y + 1,
      z: expectedBounds.max.z - expectedBounds.min.z + 1,
    };
    const expectedVolume = expectedDimensions.x * expectedDimensions.y * expectedDimensions.z;
    const countedVolume = Object.values(response.blockStateCounts).reduce((total, count) => total + count, 0);

    if (response.world !== request.world) {
      context.addIssue({
        code: 'custom',
        message: 'The response world must match the requested world.',
        path: ['world'],
      });
    }
    if (!sameBounds(response.bounds, expectedBounds)) {
      context.addIssue({
        code: 'custom',
        message: 'The response bounds must match the normalized requested bounds.',
        path: ['bounds'],
      });
    }
    if (
      response.dimensions.x !== expectedDimensions.x ||
      response.dimensions.y !== expectedDimensions.y ||
      response.dimensions.z !== expectedDimensions.z
    ) {
      context.addIssue({
        code: 'custom',
        message: 'The response dimensions must match the normalized bounds.',
        path: ['dimensions'],
      });
    }
    if (response.volume !== expectedVolume) {
      context.addIssue({ code: 'custom', message: 'The response volume must match its dimensions.', path: ['volume'] });
    }
    if (countedVolume !== response.volume) {
      context.addIssue({
        code: 'custom',
        message: 'Block-state counts must sum to the response volume.',
        path: ['blockStateCounts'],
      });
    }
  });
}

function validateExactStructureCorrelation(
  request: { readonly world: string; readonly maxResults: number },
  response: z.infer<typeof ExactBlockStructureOutputSchema>,
  context: z.core.$RefinementCtx,
): void {
  if (response.world !== request.world) {
    context.addIssue({
      code: 'custom',
      message: 'The response world must match the requested world.',
      path: ['world'],
    });
  }
  if (response.placements.length + response.runs.length > request.maxResults) {
    context.addIssue({
      code: 'custom',
      message: 'The response structure must not exceed the requested result ceiling.',
      path: ['placements'],
    });
  }
}

function validateExactStructureBounds(
  response: z.infer<typeof ExactBlockStructureOutputSchema>,
  expectedBounds: Bounds,
  context: z.core.$RefinementCtx,
): void {
  if (!sameBlockPosition(response.origin, expectedBounds.min)) {
    context.addIssue({
      code: 'custom',
      message: 'The response origin must match the minimum inspection bound.',
      path: ['origin'],
    });
  }
  const maximumOffset = {
    x: expectedBounds.max.x - expectedBounds.min.x,
    y: expectedBounds.max.y - expectedBounds.min.y,
    z: expectedBounds.max.z - expectedBounds.min.z,
  };
  const inside = (x: number, y: number, zCoordinate: number): boolean =>
    x >= 0 &&
    y >= 0 &&
    zCoordinate >= 0 &&
    x <= maximumOffset.x &&
    y <= maximumOffset.y &&
    zCoordinate <= maximumOffset.z;

  for (const [index, [, x, y, zCoordinate]] of response.placements.entries()) {
    if (!inside(x, y, zCoordinate)) {
      context.addIssue({
        code: 'custom',
        message: 'Every placement must be inside the requested inspection bounds.',
        path: ['placements', index],
      });
    }
  }
  for (const [index, [, x, y, zCoordinate, toX, toY, toZ]] of response.runs.entries()) {
    if (!inside(x, y, zCoordinate) || !inside(toX, toY, toZ)) {
      context.addIssue({
        code: 'custom',
        message: 'Every run must be inside the requested inspection bounds.',
        path: ['runs', index],
      });
    }
  }
}

function orthographicBounds(request: components['schemas']['ScanOrthographicViewRequest']): Bounds {
  const { x, y, z: zCoordinate } = request.origin;
  const horizontal = request.horizontalRadius;
  const vertical = request.verticalRadius;
  const distance = request.maxDistance;
  switch (request.direction) {
    case 'north':
      return {
        min: { x: x - horizontal, y: y - vertical, z: zCoordinate - distance },
        max: { x: x + horizontal, y: y + vertical, z: zCoordinate - 1 },
      };
    case 'east':
      return {
        min: { x: x + 1, y: y - vertical, z: zCoordinate - horizontal },
        max: { x: x + distance, y: y + vertical, z: zCoordinate + horizontal },
      };
    case 'south':
      return {
        min: { x: x - horizontal, y: y - vertical, z: zCoordinate + 1 },
        max: { x: x + horizontal, y: y + vertical, z: zCoordinate + distance },
      };
    case 'west':
      return {
        min: { x: x - distance, y: y - vertical, z: zCoordinate - horizontal },
        max: { x: x - 1, y: y + vertical, z: zCoordinate + horizontal },
      };
    case 'up':
      return {
        min: { x: x - horizontal, y: y + 1, z: zCoordinate - vertical },
        max: { x: x + horizontal, y: y + distance, z: zCoordinate + vertical },
      };
    case 'down':
      return {
        min: { x: x - horizontal, y: y - distance, z: zCoordinate - vertical },
        max: { x: x + horizontal, y: y - 1, z: zCoordinate + vertical },
      };
  }
  throw new Error('Unsupported orthographic direction.');
}

export function getBlocksBridgeOutputSchema(request: components['schemas']['GetBlocksRequest']) {
  return ExactBlockStructureOutputSchema.superRefine((response, context) => {
    validateExactStructureCorrelation(request, response, context);
    validateExactStructureBounds(response, normalizedBounds(request.min, request.max), context);
  });
}

export function scanOrthographicViewBridgeOutputSchema(request: components['schemas']['ScanOrthographicViewRequest']) {
  return ExactBlockStructureOutputSchema.superRefine((response, context) => {
    validateExactStructureCorrelation(request, response, context);
    validateExactStructureBounds(response, orthographicBounds(request), context);
  });
}

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
              countRegionBlockStatesBridgeOutputSchema(request),
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
              getBlocksBridgeOutputSchema(request),
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
              scanOrthographicViewBridgeOutputSchema(request),
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
