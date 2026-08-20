import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import type { DirtLogger } from '../logging.ts';
import {
  BlockPositionSchema,
  BLOCK_AXES,
  BoundsSchema,
  DimensionsSchema,
  INT32_MAX,
  MAX_BLOCK_STATE_ENTRIES,
  NonBlankStringSchema,
  READ_WORLD_ANNOTATIONS,
  SignedInt32Schema,
} from './common.ts';
import { executeToolCall, successResult } from './execution.ts';
import type { McpToolConfiguration } from './configuration.ts';
import {
  containsPosition,
  inclusiveBlockVolume,
  invalidBridgeResponse,
  normalizedBounds,
  requireMatchingWorld,
  sameBounds,
  sameCoordinates,
} from './response-validation.ts';
import { compactView } from './view-grid.ts';

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
    world: z.string().min(1).describe('Inspected world name.'),
    bounds: BoundsSchema,
    dimensions: DimensionsSchema,
    volume: z.number().int().positive().describe('Total blocks scanned, including air.'),
    blockStateCounts: z
      .record(z.string().min(1), z.number().int().nonnegative())
      .describe('Canonical block-state string to occurrence count, including properties when present.'),
  })
  .strict()
  .describe('Complete block-state histogram for the region.');

export const GetRegionBlocksInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
    max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
    includeBlockStatePatterns: z
      .array(NonBlankStringSchema)
      .max(MAX_BLOCK_STATE_ENTRIES)
      .default([])
      .describe(
        'Optional allowlist of block-state patterns. Omitted properties match any value; an empty list allows all states.',
      ),
    excludeBlockStatePatterns: z
      .array(NonBlankStringSchema)
      .max(MAX_BLOCK_STATE_ENTRIES)
      .default([])
      .describe('Block-state patterns rejected after include filtering. Omitted properties match any value.'),
    includeAir: z
      .boolean()
      .optional()
      .describe('Whether air-family states may match; omission uses the Paper plugin default.'),
    maxResults: z
      .number()
      .int()
      .min(1)
      .max(INT32_MAX)
      .optional()
      .describe(
        'Maximum returned blocks or runs, bounded by the active inspection-result limit. Results fail instead of truncating.',
      ),
    format: z
      .enum(['blocks', 'runs'])
      .optional()
      .describe(
        'blocks returns individual positions; runs returns lossless axis-aligned spans. Omission uses the plugin default.',
      ),
  })
  .strict()
  .superRefine((input, context) => {
    if (input.includeBlockStatePatterns.length + input.excludeBlockStatePatterns.length > MAX_BLOCK_STATE_ENTRIES) {
      context.addIssue({
        code: 'custom',
        message: `include and exclude block-state patterns may contain at most ${MAX_BLOCK_STATE_ENTRIES} entries combined`,
      });
    }
  })
  .describe('Filters and return format for exact region block data.');

const RegionBlocksOutputBase = {
  world: z.string().min(1).describe('Inspected world name.'),
  bounds: BoundsSchema,
  volume: z.number().int().positive().describe('Total blocks scanned before filtering.'),
  matchedBlockCount: z.number().int().nonnegative().describe('Total matching blocks represented by the response.'),
};

const GetRegionBlocksOutputSchema = z
  .discriminatedUnion('format', [
    z
      .object({
        ...RegionBlocksOutputBase,
        format: z.literal('blocks').describe('Response contains one entry per matching block.'),
        blocks: z
          .array(
            z
              .object({
                position: BlockPositionSchema,
                blockState: z.string().min(1).describe('Canonical block state at position.'),
              })
              .strict()
              .describe('One matching block.'),
          )
          .describe('Matching blocks in deterministic scan order.'),
      })
      .strict(),
    z
      .object({
        ...RegionBlocksOutputBase,
        format: z.literal('runs').describe('Response contains lossless axis-aligned block runs.'),
        runs: z
          .array(
            z
              .object({
                blockState: z.string().min(1).describe('Canonical block state shared by the run.'),
                from: BlockPositionSchema.describe('Inclusive first block of the run.'),
                to: BlockPositionSchema.describe('Inclusive last block of the run.'),
              })
              .strict()
              .describe('A lossless run of matching blocks.'),
          )
          .describe('Matching block runs in deterministic scan order.'),
      })
      .strict(),
  ])
  .describe('Exact matching block data; inspect format before reading blocks or runs.');

const OrthographicViewDirectionSchema = z
  .enum(['north', 'east', 'south', 'west', 'up', 'down'])
  .describe('World-axis scan direction: north=-Z, east=+X, south=+Z, west=-X, up=+Y, down=-Y.');

export const ScanOrthographicViewInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    origin: BlockPositionSchema.describe('View anchor; scanning begins one block away and excludes the origin.'),
    direction: OrthographicViewDirectionSchema,
    horizontalRadius: z
      .number()
      .int()
      .min(0)
      .max(INT32_MAX)
      .describe('Cells on each side of the center sightline along the returned horizontal basis.'),
    verticalRadius: z
      .number()
      .int()
      .min(0)
      .max(INT32_MAX)
      .describe('Cells on each side of the center sightline along the returned vertical basis.'),
    maxDistance: z
      .number()
      .int()
      .min(1)
      .max(INT32_MAX)
      .describe('Maximum forward scan distance; distance 1 is adjacent to origin.'),
    depth: z
      .number()
      .int()
      .min(0)
      .max(INT32_MAX)
      .default(0)
      .describe('Zero-based non-air hit to return per sightline: 0 is first, 1 is second, and so on.'),
    maxResults: z
      .number()
      .int()
      .min(1)
      .max(INT32_MAX)
      .optional()
      .describe(
        'Maximum visible blocks, bounded by the active inspection-result limit. Results fail instead of truncating.',
      ),
    format: z
      .enum(['blocks', 'grid'])
      .default('blocks')
      .describe('blocks returns explicit positions; grid returns compact lossless palette and distance matrices.'),
  })
  .strict()
  .describe('Bounded orthographic sightlines scanned for a selected non-air depth.');

const AxisVectorSchema = z
  .object({
    x: z.number().int().min(-1).max(1).describe('X component.'),
    y: z.number().int().min(-1).max(1).describe('Y component.'),
    z: z.number().int().min(-1).max(1).describe('Z component.'),
  })
  .strict()
  .describe('A world-axis unit vector.');

const ViewMetadata = {
  world: z.string().min(1).describe('Scanned world name.'),
  origin: BlockPositionSchema,
  direction: OrthographicViewDirectionSchema,
  basis: z
    .object({
      forward: AxisVectorSchema.describe('Direction of increasing sightline distance.'),
      horizontal: AxisVectorSchema.describe('Direction of increasing horizontal offset.'),
      vertical: AxisVectorSchema.describe('Direction of increasing vertical offset.'),
    })
    .strict()
    .describe('Basis for converting view-relative offsets to world positions.'),
  viewport: z
    .object({
      horizontalRadius: z.number().int().nonnegative().describe('Horizontal radius used.'),
      verticalRadius: z.number().int().nonnegative().describe('Vertical radius used.'),
      maxDistance: z.number().int().positive().describe('Forward distance used.'),
      depth: z.number().int().nonnegative().describe('Zero-based non-air depth returned.'),
    })
    .strict()
    .describe('Resolved scan dimensions.'),
  bounds: BoundsSchema.describe('Inclusive world-space bounds scanned.'),
  scannedVolume: z.number().int().positive().describe('Total blocks checked across all sightlines.'),
  visibleBlockCount: z.number().int().nonnegative().describe('Sightlines whose requested non-air block was found.'),
};

const ScanOrthographicViewBlocksOutputSchema = z
  .object({
    ...ViewMetadata,
    format: z.literal('blocks').describe('Response contains explicit visible-block entries.'),
    blocks: z
      .array(
        z
          .object({
            position: BlockPositionSchema.describe('Absolute position of the requested non-air block.'),
            offset: z
              .object({
                horizontal: SignedInt32Schema.describe('Signed displacement along basis.horizontal.'),
                vertical: SignedInt32Schema.describe('Signed displacement along basis.vertical.'),
                distance: z
                  .number()
                  .int()
                  .min(1)
                  .max(INT32_MAX)
                  .describe('Positive displacement along basis.forward; 1 is adjacent to origin.'),
              })
              .strict()
              .describe('View-relative location of the visible block.'),
            blockState: z.string().min(1).describe('Canonical state of the visible block.'),
          })
          .strict()
          .describe('Requested non-air block on one sightline.'),
      )
      .describe('Visible blocks in deterministic viewport order.'),
  })
  .strict()
  .describe('Orthographic scan with explicit block positions.');

const ScanOrthographicViewGridOutputSchema = z
  .object({
    ...ViewMetadata,
    format: z.literal('grid').describe('Response contains compact palette and distance matrices.'),
    blockStatePalette: z
      .array(z.string().min(1))
      .describe('Canonical states indexed from 1 by blockStateIndexRows; index 0 means no visible block.'),
    blockStateIndexRows: z
      .array(z.array(z.number().int().nonnegative()))
      .describe('Top-to-bottom rows and left-to-right cells containing palette indices; 0 means empty sightline.'),
    distanceRows: z
      .array(z.array(z.number().int().nonnegative()))
      .describe('Distances aligned with blockStateIndexRows; 0 means empty sightline.'),
  })
  .strict()
  .describe('Lossless compact orthographic scan.');

const ScanOrthographicViewOutputSchema = z
  .discriminatedUnion('format', [ScanOrthographicViewBlocksOutputSchema, ScanOrthographicViewGridOutputSchema])
  .describe('Orthographic scan result; inspect format before reading blocks or grid fields.');

export type ScanOrthographicViewBlocksOutput = z.infer<typeof ScanOrthographicViewBlocksOutputSchema>;
export type ScanOrthographicViewGridOutput = z.infer<typeof ScanOrthographicViewGridOutputSchema>;

type CountRegionBlockStatesInput = z.infer<typeof CountRegionBlockStatesInputSchema>;
type CountRegionBlockStatesOutput = z.infer<typeof CountRegionBlockStatesOutputSchema>;
type GetRegionBlocksInput = z.infer<typeof GetRegionBlocksInputSchema>;
type GetRegionBlocksOutput = z.infer<typeof GetRegionBlocksOutputSchema>;
type ScanOrthographicViewInput = z.infer<typeof ScanOrthographicViewInputSchema>;

const VIEW_BASIS = {
  north: {
    forward: { x: 0, y: 0, z: -1 },
    horizontal: { x: 1, y: 0, z: 0 },
    vertical: { x: 0, y: 1, z: 0 },
  },
  east: {
    forward: { x: 1, y: 0, z: 0 },
    horizontal: { x: 0, y: 0, z: 1 },
    vertical: { x: 0, y: 1, z: 0 },
  },
  south: {
    forward: { x: 0, y: 0, z: 1 },
    horizontal: { x: -1, y: 0, z: 0 },
    vertical: { x: 0, y: 1, z: 0 },
  },
  west: {
    forward: { x: -1, y: 0, z: 0 },
    horizontal: { x: 0, y: 0, z: -1 },
    vertical: { x: 0, y: 1, z: 0 },
  },
  up: {
    forward: { x: 0, y: 1, z: 0 },
    horizontal: { x: 1, y: 0, z: 0 },
    vertical: { x: 0, y: 0, z: -1 },
  },
  down: {
    forward: { x: 0, y: -1, z: 0 },
    horizontal: { x: 1, y: 0, z: 0 },
    vertical: { x: 0, y: 0, z: -1 },
  },
} as const;

export function requireMatchingCountRegionResponse(
  expected: CountRegionBlockStatesInput,
  actual: CountRegionBlockStatesOutput,
): void {
  requireMatchingWorld(expected.world, actual.world);
  const bounds = normalizedBounds(expected.min, expected.max);
  if (!sameBounds(bounds, actual.bounds)) {
    invalidBridgeResponse('Paper bridge count bounds did not match the requested region.');
  }

  const dimensions = {
    x: bounds.max.x - bounds.min.x + 1,
    y: bounds.max.y - bounds.min.y + 1,
    z: bounds.max.z - bounds.min.z + 1,
  };
  const volume = inclusiveBlockVolume(bounds);
  const histogramTotal = Object.values(actual.blockStateCounts).reduce((sum, count) => sum + BigInt(count), 0n);
  if (
    !sameCoordinates(dimensions, actual.dimensions) ||
    BigInt(actual.volume) !== volume ||
    histogramTotal !== volume
  ) {
    invalidBridgeResponse('Paper bridge returned inconsistent region count totals.');
  }
}

export function requireMatchingGetRegionResponse(expected: GetRegionBlocksInput, actual: GetRegionBlocksOutput): void {
  requireMatchingWorld(expected.world, actual.world);
  const bounds = normalizedBounds(expected.min, expected.max);
  const volume = inclusiveBlockVolume(bounds);
  if (!sameBounds(bounds, actual.bounds) || BigInt(actual.volume) !== volume) {
    invalidBridgeResponse('Paper bridge region result did not match the requested bounds and volume.');
  }
  if (expected.format !== undefined && actual.format !== expected.format) {
    invalidBridgeResponse('Paper bridge region result format did not match the explicit request.');
  }
  if (BigInt(actual.matchedBlockCount) > volume) {
    invalidBridgeResponse('Paper bridge matchedBlockCount exceeded the requested region volume.');
  }

  const entries = actual.format === 'blocks' ? actual.blocks : actual.runs;
  if (expected.maxResults !== undefined && entries.length > expected.maxResults) {
    invalidBridgeResponse('Paper bridge region result exceeded the requested maxResults.');
  }

  if (actual.format === 'blocks') {
    if (actual.blocks.length !== actual.matchedBlockCount) {
      invalidBridgeResponse('Paper bridge block entries did not match matchedBlockCount.');
    }
    const positions = new Set<string>();
    for (const block of actual.blocks) {
      if (!containsPosition(bounds, block.position)) {
        invalidBridgeResponse('Paper bridge returned a block outside the requested region.');
      }
      const positionKey = `${block.position.x},${block.position.y},${block.position.z}`;
      if (positions.has(positionKey)) {
        invalidBridgeResponse('Paper bridge returned a duplicate block position.');
      }
      positions.add(positionKey);
    }
    return;
  }

  let representedBlocks = 0n;
  for (const run of actual.runs) {
    if (!containsPosition(bounds, run.from) || !containsPosition(bounds, run.to)) {
      invalidBridgeResponse('Paper bridge returned a block run outside the requested region.');
    }
    const varyingAxes = BLOCK_AXES.filter((axis) => run.from[axis] !== run.to[axis]);
    if (varyingAxes.length > 1 || run.from.x > run.to.x || run.from.y > run.to.y || run.from.z > run.to.z) {
      invalidBridgeResponse('Paper bridge returned an invalid axis-aligned block run.');
    }
    representedBlocks += inclusiveBlockVolume({ min: run.from, max: run.to });
    if (representedBlocks > BigInt(actual.matchedBlockCount)) {
      invalidBridgeResponse('Paper bridge block runs represented more blocks than matchedBlockCount.');
    }
  }
  if (representedBlocks !== BigInt(actual.matchedBlockCount)) {
    invalidBridgeResponse('Paper bridge block runs did not represent matchedBlockCount exactly.');
  }
}

export function requireMatchingScanResponse(
  expected: ScanOrthographicViewInput,
  actual: ScanOrthographicViewBlocksOutput,
): void {
  requireMatchingWorld(expected.world, actual.world);
  const basis = VIEW_BASIS[expected.direction];
  if (
    !sameCoordinates(expected.origin, actual.origin) ||
    actual.direction !== expected.direction ||
    !sameCoordinates(basis.forward, actual.basis.forward) ||
    !sameCoordinates(basis.horizontal, actual.basis.horizontal) ||
    !sameCoordinates(basis.vertical, actual.basis.vertical)
  ) {
    invalidBridgeResponse('Paper bridge view origin, direction, or basis did not match the request.');
  }
  if (
    actual.viewport.horizontalRadius !== expected.horizontalRadius ||
    actual.viewport.verticalRadius !== expected.verticalRadius ||
    actual.viewport.maxDistance !== expected.maxDistance ||
    actual.viewport.depth !== expected.depth
  ) {
    invalidBridgeResponse('Paper bridge returned a viewport different from the requested view.');
  }

  const width = BigInt(expected.horizontalRadius) * 2n + 1n;
  const height = BigInt(expected.verticalRadius) * 2n + 1n;
  const scannedVolume = width * height * BigInt(expected.maxDistance);
  const bounds = viewBounds(expected, basis);
  if (BigInt(actual.scannedVolume) !== scannedVolume || !sameBounds(bounds, actual.bounds)) {
    invalidBridgeResponse('Paper bridge returned invalid orthographic scan bounds or volume.');
  }
  if (actual.visibleBlockCount !== actual.blocks.length) {
    invalidBridgeResponse('Paper bridge returned an inconsistent visible block count.');
  }
  if (expected.maxResults !== undefined && actual.blocks.length > expected.maxResults) {
    invalidBridgeResponse('Paper bridge view result exceeded the requested maxResults.');
  }

  let previousCell = -1n;
  for (const block of actual.blocks) {
    const { horizontal, vertical, distance } = block.offset;
    if (
      Math.abs(horizontal) > expected.horizontalRadius ||
      Math.abs(vertical) > expected.verticalRadius ||
      distance > expected.maxDistance
    ) {
      invalidBridgeResponse('Paper bridge returned a view block outside its viewport.');
    }
    const position = viewPosition(expected.origin, basis, horizontal, vertical, distance);
    if (!sameCoordinates(position, block.position)) {
      invalidBridgeResponse('Paper bridge view block position did not match its offset and basis.');
    }
    const cell = BigInt(expected.verticalRadius - vertical) * width + BigInt(horizontal + expected.horizontalRadius);
    if (cell <= previousCell) {
      invalidBridgeResponse('Paper bridge view blocks were not in distinct deterministic viewport order.');
    }
    previousCell = cell;
  }
}

function viewPosition(
  origin: z.infer<typeof BlockPositionSchema>,
  basis: (typeof VIEW_BASIS)[keyof typeof VIEW_BASIS],
  horizontal: number,
  vertical: number,
  distance: number,
): z.infer<typeof BlockPositionSchema> {
  return {
    x: origin.x + basis.horizontal.x * horizontal + basis.vertical.x * vertical + basis.forward.x * distance,
    y: origin.y + basis.horizontal.y * horizontal + basis.vertical.y * vertical + basis.forward.y * distance,
    z: origin.z + basis.horizontal.z * horizontal + basis.vertical.z * vertical + basis.forward.z * distance,
  };
}

function viewBounds(
  input: ScanOrthographicViewInput,
  basis: (typeof VIEW_BASIS)[keyof typeof VIEW_BASIS],
): z.infer<typeof BoundsSchema> {
  return normalizedBounds(
    viewPosition(input.origin, basis, -input.horizontalRadius, -input.verticalRadius, 1),
    viewPosition(input.origin, basis, input.horizontalRadius, input.verticalRadius, input.maxDistance),
  );
}

export function registerInspectionTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  const countRegionBlockStates = server.registerTool(
    'count_region_block_states',
    {
      title: 'Count region block states',
      description:
        'Return a complete canonical block-state histogram for an inclusive region. Use this before exact retrieval when totals are sufficient.',
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
          const result = await bridge.request(
            BRIDGE_ROUTES.countRegionBlockStates,
            callId,
            CountRegionBlockStatesOutputSchema,
            input,
          );
          requireMatchingCountRegionResponse(input, result);
          return successResult(
            result,
            `Counted ${result.volume} blocks across ${Object.keys(result.blockStateCounts).length} block states in ${result.world}.`,
          );
        },
      ),
  );
  if (!toolConfiguration.count_region_block_states) countRegionBlockStates.disable();

  const getRegionBlocks = server.registerTool(
    'get_region_blocks',
    {
      title: 'Get region blocks',
      description:
        'Return filtered exact blocks or lossless runs from an inclusive region. Use filters and runs to keep output compact. Results that exceed active scan or result ceilings fail rather than truncate.' +
        (toolConfiguration.get_server_status ? ' The active ceilings are reported by get_server_status.' : ''),
      inputSchema: GetRegionBlocksInputSchema,
      outputSchema: GetRegionBlocksOutputSchema,
      annotations: READ_WORLD_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        logger,
        {
          operation: 'get_region_blocks',
          world: input.world,
          context,
          failureContext: 'Could not get region blocks',
        },
        async (callId) => {
          const result = await bridge.request(
            BRIDGE_ROUTES.getRegionBlocks,
            callId,
            GetRegionBlocksOutputSchema,
            input,
          );
          requireMatchingGetRegionResponse(input, result);
          const entries = result.format === 'blocks' ? result.blocks.length : result.runs.length;
          const entryKind = result.format === 'blocks' ? 'block' : 'run';
          return successResult(
            result,
            `Matching blocks: ${result.matchedBlockCount}; ${entryKind} entries: ${entries}; world: ${result.world}.`,
          );
        },
      ),
  );
  if (!toolConfiguration.get_region_blocks) getRegionBlocks.disable();

  const scanOrthographicView = server.registerTool(
    'scan_orthographic_view',
    {
      title: 'Scan an orthographic view',
      description:
        'Return a selected zero-based non-air depth on each bounded world-axis sightline. Depth 0 is the first non-air block, 1 is the second, and so on. Prefer grid for larger views.' +
        (toolConfiguration.get_server_status
          ? ' Active scan and result ceilings are reported by get_server_status.'
          : ''),
      inputSchema: ScanOrthographicViewInputSchema,
      outputSchema: ScanOrthographicViewOutputSchema,
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
          const { format, ...bridgeInput } = input;
          const sparseView = await bridge.request(
            BRIDGE_ROUTES.scanOrthographicView,
            callId,
            ScanOrthographicViewBlocksOutputSchema,
            bridgeInput,
          );
          requireMatchingScanResponse(input, sparseView);
          if (format === 'grid') {
            const result = compactView(sparseView);
            const width = result.viewport.horizontalRadius * 2 + 1;
            const height = result.viewport.verticalRadius * 2 + 1;
            return successResult(
              result,
              `Scanned ${width}x${height} view: ${result.visibleBlockCount} visible blocks using ${result.blockStatePalette.length} block states.`,
            );
          }
          return successResult(
            sparseView,
            `Scanned view in ${sparseView.world}: ${sparseView.visibleBlockCount} visible blocks returned explicitly.`,
          );
        },
      ),
  );
  if (!toolConfiguration.scan_orthographic_view) scanOrthographicView.disable();
}
