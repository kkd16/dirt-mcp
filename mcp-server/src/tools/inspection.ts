import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import { toolOutputSchema } from '../bridge/errors.ts';
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
import { isForwardRun, resolveOffset, runContains, runsOverlap, structureBlockCount } from './block-structure.ts';
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

export const GetBlocksInputSchema = z
  .object({
    world: NonBlankStringSchema.describe('Exact name of an already loaded Paper world.'),
    min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
    max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
    includeBlockStatePatterns: z
      .array(NonBlankStringSchema)
      .max(MAX_BLOCK_STATE_PATTERNS)
      .default([])
      .describe(
        'Optional allowlist of block-state patterns. Omitted properties match any value; an empty list allows all states.',
      ),
    excludeBlockStatePatterns: z
      .array(NonBlankStringSchema)
      .max(MAX_BLOCK_STATE_PATTERNS)
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
        'Maximum returned placements plus runs, bounded by the active inspection-result limit. Results fail instead of truncating.',
      ),
  })
  .strict()
  .superRefine((input, context) => {
    if (input.includeBlockStatePatterns.length + input.excludeBlockStatePatterns.length > MAX_BLOCK_STATE_PATTERNS) {
      context.addIssue({
        code: 'custom',
        message: `include and exclude block-state patterns may contain at most ${MAX_BLOCK_STATE_PATTERNS} entries combined`,
      });
    }
  })
  .describe('Filters for replay-ready exact region block data.');

const ExactPaletteEntrySchema = z
  .object({ blockState: z.string().min(1).describe('Exact canonical block state represented by this palette.') })
  .strict();

const ExactPalettesSchema = z
  .array(z.tuple([ExactPaletteEntrySchema]).rest(z.never()))
  .max(MAX_PALETTE_ENTRIES)
  .superRefine((palettes, context) => {
    const states = new Set<string>();
    palettes.forEach((palette, index) => {
      const state = palette[0].blockState;
      if (states.has(state)) {
        context.addIssue({ code: 'custom', path: [index], message: 'Exact palettes must represent distinct states.' });
      }
      states.add(state);
    });
  })
  .describe('First-seen exact singleton palettes referenced by placements and runs.');

export const GetBlocksOutputSchema = z
  .object({
    world: z.string().min(1).describe('Inspected world name.'),
    origin: BlockPositionSchema.describe('Normalized minimum requested corner used by every relative tuple.'),
    palettes: ExactPalettesSchema,
    placements: z.array(PalettePlacementSchema).describe('Single matching blocks as origin-relative tuples.'),
    runs: z.array(PaletteRunSchema).describe('Matching blocks packed as origin-relative inclusive cuboids.'),
  })
  .strict()
  .refine(
    (result) =>
      result.placements.length + result.runs.length === 0 ? result.palettes.length === 0 : result.palettes.length > 0,
    'Palettes must be empty exactly when the returned geometry is empty.',
  )
  .describe('Replay-ready exact block geometry accepted by set_blocks after adding its required edit label.');

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
        'Maximum non-empty cells, bounded by the active inspection-result limit. Results fail instead of truncating.',
      ),
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

const ScanOrthographicViewOutputSchema = z
  .object({
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
    blockStatePalette: z
      .array(z.string().min(1))
      .describe('Canonical states indexed from 1 by blockStateIndexRows; index 0 means no visible block.'),
    blockStateIndexRows: z
      .array(z.array(z.number().int().min(0).max(INT32_MAX)))
      .describe('Top-to-bottom rows and left-to-right cells containing palette indices; 0 means empty sightline.'),
    distanceRows: z
      .array(z.array(z.number().int().min(0).max(INT32_MAX)))
      .describe('Distances aligned with blockStateIndexRows; 0 means empty sightline.'),
  })
  .strict()
  .describe('Lossless compact orthographic scan.');

type ScanOrthographicViewOutput = z.infer<typeof ScanOrthographicViewOutputSchema>;

type CountRegionBlockStatesInput = z.infer<typeof CountRegionBlockStatesInputSchema>;
type CountRegionBlockStatesOutput = z.infer<typeof CountRegionBlockStatesOutputSchema>;
type GetBlocksInput = z.infer<typeof GetBlocksInputSchema>;
type GetBlocksOutput = z.infer<typeof GetBlocksOutputSchema>;
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

export function requireMatchingGetBlocksResponse(expected: GetBlocksInput, actual: GetBlocksOutput): void {
  requireMatchingWorld(expected.world, actual.world);
  const bounds = normalizedBounds(expected.min, expected.max);
  const volume = inclusiveBlockVolume(bounds);
  if (!sameCoordinates(bounds.min, actual.origin)) {
    invalidBridgeResponse('Paper bridge block origin did not match the normalized requested minimum.');
  }

  const entryCount = actual.placements.length + actual.runs.length;
  if (expected.maxResults !== undefined && entryCount > expected.maxResults) {
    invalidBridgeResponse('Paper bridge region result exceeded the requested maxResults.');
  }
  const positions = new Set<string>();
  const referencedPalettes = new Set<number>();
  const requirePalette = (paletteIndex: number): void => {
    if (paletteIndex >= actual.palettes.length) {
      invalidBridgeResponse('Paper bridge block geometry referenced a missing palette.');
    }
    referencedPalettes.add(paletteIndex);
  };
  const addPlacement = (position: { readonly x: number; readonly y: number; readonly z: number }): void => {
    if (!containsPosition(bounds, position)) {
      invalidBridgeResponse('Paper bridge returned block geometry outside the requested region.');
    }
    const key = `${position.x},${position.y},${position.z}`;
    if (positions.has(key)) {
      invalidBridgeResponse('Paper bridge returned overlapping block geometry.');
    }
    positions.add(key);
  };

  for (const placement of actual.placements) {
    requirePalette(placement[0]);
    const position = resolveOffset(actual.origin, placement[1], placement[2], placement[3]);
    if (position === undefined) invalidBridgeResponse('Paper bridge returned an overflowing block placement.');
    addPlacement(position);
  }
  for (const [runIndex, run] of actual.runs.entries()) {
    requirePalette(run[0]);
    if (!isForwardRun(run)) invalidBridgeResponse('Paper bridge returned a reversed block run.');
    const from = resolveOffset(actual.origin, run[1], run[2], run[3]);
    const to = resolveOffset(actual.origin, run[4], run[5], run[6]);
    if (from === undefined || to === undefined || !containsPosition(bounds, from) || !containsPosition(bounds, to)) {
      invalidBridgeResponse('Paper bridge returned an invalid block run.');
    }
    for (const placement of actual.placements) {
      if (runContains(run, placement[1], placement[2], placement[3])) {
        invalidBridgeResponse('Paper bridge returned overlapping block geometry.');
      }
    }
    for (const [previousIndex, previous] of actual.runs.entries()) {
      if (previousIndex >= runIndex) break;
      if (runsOverlap(run, previous)) invalidBridgeResponse('Paper bridge returned overlapping block geometry.');
    }
  }
  if (structureBlockCount(actual.placements, actual.runs) > volume) {
    invalidBridgeResponse('Paper bridge block structure exceeded the requested region volume.');
  }
  if (referencedPalettes.size !== actual.palettes.length) {
    invalidBridgeResponse('Paper bridge returned an unused exact palette.');
  }
}

export function requireMatchingScanResponse(
  expected: ScanOrthographicViewInput,
  actual: ScanOrthographicViewOutput,
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
  const rowCount = Number(height);
  const columnCount = Number(width);
  if (actual.blockStateIndexRows.length !== rowCount || actual.distanceRows.length !== rowCount) {
    invalidBridgeResponse('Paper bridge returned orthographic grid rows with the wrong height.');
  }
  if (new Set(actual.blockStatePalette).size !== actual.blockStatePalette.length) {
    invalidBridgeResponse('Paper bridge returned duplicate orthographic palette entries.');
  }

  const seenPaletteIndexes = new Set<number>();
  let visibleCellCount = 0;
  for (let row = 0; row < rowCount; row++) {
    const blockStateIndexes = actual.blockStateIndexRows[row]!;
    const distances = actual.distanceRows[row]!;
    if (blockStateIndexes.length !== columnCount || distances.length !== columnCount) {
      invalidBridgeResponse('Paper bridge returned orthographic grid rows with the wrong width.');
    }
    for (let column = 0; column < columnCount; column++) {
      const blockStateIndex = blockStateIndexes[column]!;
      const distance = distances[column]!;
      if ((blockStateIndex === 0) !== (distance === 0)) {
        invalidBridgeResponse('Paper bridge returned misaligned orthographic state and distance cells.');
      }
      if (blockStateIndex > actual.blockStatePalette.length || distance > expected.maxDistance) {
        invalidBridgeResponse('Paper bridge returned an orthographic cell outside its palette or viewport.');
      }
      if (blockStateIndex === 0) continue;
      visibleCellCount++;
      if (!seenPaletteIndexes.has(blockStateIndex)) {
        if (blockStateIndex !== seenPaletteIndexes.size + 1) {
          invalidBridgeResponse('Paper bridge orthographic palette was not in first-seen cell order.');
        }
        seenPaletteIndexes.add(blockStateIndex);
      }
    }
  }
  if (actual.visibleBlockCount !== visibleCellCount || actual.blockStatePalette.length !== seenPaletteIndexes.size) {
    invalidBridgeResponse('Paper bridge returned inconsistent orthographic grid counts.');
  }
  if (expected.maxResults !== undefined && visibleCellCount > expected.maxResults) {
    invalidBridgeResponse('Paper bridge view result exceeded the requested maxResults.');
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
        'Return a complete canonical block-state histogram for an inclusive region. Use this when totals are sufficient.',
      inputSchema: CountRegionBlockStatesInputSchema,
      outputSchema: toolOutputSchema(CountRegionBlockStatesOutputSchema),
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

  const getBlocks = server.registerTool(
    'get_blocks',
    {
      title: 'Get blocks',
      description:
        'Return filtered exact block states as replay-ready origin-relative palettes, placements, and inclusive cuboid runs. To copy it with set_blocks, reuse the structure, change origin, and add a concise edit label. Results that exceed active scan, palette, or result ceilings fail rather than truncate.' +
        (toolConfiguration.get_server_status
          ? ' The active ceilings are reported by get_server_status with include.configuration=true.'
          : ''),
      inputSchema: GetBlocksInputSchema,
      outputSchema: toolOutputSchema(GetBlocksOutputSchema),
      annotations: READ_WORLD_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        logger,
        {
          operation: 'get_blocks',
          world: input.world,
          context,
          failureContext: 'Could not get blocks',
        },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.getBlocks, callId, GetBlocksOutputSchema, input);
          requireMatchingGetBlocksResponse(input, result);
          const entries = result.placements.length + result.runs.length;
          const blockCount = structureBlockCount(result.placements, result.runs);
          return successResult(
            result,
            `Matching blocks: ${blockCount}; structure entries: ${entries}; palettes: ${result.palettes.length}; world: ${result.world}.`,
          );
        },
      ),
  );
  if (!toolConfiguration.get_blocks) getBlocks.disable();

  const scanOrthographicView = server.registerTool(
    'scan_orthographic_view',
    {
      title: 'Scan an orthographic view',
      description:
        'Return a compact lossless grid for a selected zero-based non-air depth on each bounded world-axis sightline. Depth 0 is the first non-air block, 1 is the second, and so on.' +
        (toolConfiguration.get_server_status
          ? ' Active scan and result ceilings are reported by get_server_status with include.configuration=true.'
          : ''),
      inputSchema: ScanOrthographicViewInputSchema,
      outputSchema: toolOutputSchema(ScanOrthographicViewOutputSchema),
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
          const result = await bridge.request(
            BRIDGE_ROUTES.scanOrthographicView,
            callId,
            ScanOrthographicViewOutputSchema,
            input,
          );
          requireMatchingScanResponse(input, result);
          const width = result.viewport.horizontalRadius * 2 + 1;
          const height = result.viewport.verticalRadius * 2 + 1;
          return successResult(
            result,
            `Scanned ${width}x${height} view: ${result.visibleBlockCount} visible cells using ${result.blockStatePalette.length} block states.`,
          );
        },
      ),
  );
  if (!toolConfiguration.scan_orthographic_view) scanOrthographicView.disable();
}
