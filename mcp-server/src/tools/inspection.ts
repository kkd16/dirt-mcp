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

export const ExactBlockStructureOutputSchema = z
  .object({
    world: z.string().min(1).describe('Inspected world name.'),
    origin: BlockPositionSchema.describe('Normalized minimum inspection bound used by every relative tuple.'),
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
      .describe('Sightlines on each side of center along the direction-specific horizontal axis.'),
    verticalRadius: z
      .number()
      .int()
      .min(0)
      .max(INT32_MAX)
      .describe('Sightlines on each side of center along the direction-specific vertical axis.'),
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
        'Maximum returned placements plus runs, bounded by the active inspection-result limit. Results fail instead of truncating.',
      ),
  })
  .strict()
  .describe('Bounded orthographic sightlines scanned for a selected non-air depth.');

type CountRegionBlockStatesInput = z.infer<typeof CountRegionBlockStatesInputSchema>;
type CountRegionBlockStatesOutput = z.infer<typeof CountRegionBlockStatesOutputSchema>;
type GetBlocksInput = z.infer<typeof GetBlocksInputSchema>;
type ExactBlockStructureOutput = z.infer<typeof ExactBlockStructureOutputSchema>;
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

interface ExactStructureExpectation {
  readonly world: string;
  readonly origin: z.infer<typeof BlockPositionSchema>;
  readonly bounds: z.infer<typeof BoundsSchema>;
  readonly maxResults: number | undefined;
}

function requireMatchingExactStructureResponse(
  expected: ExactStructureExpectation,
  actual: ExactBlockStructureOutput,
): void {
  requireMatchingWorld(expected.world, actual.world);
  const { bounds } = expected;
  const volume = inclusiveBlockVolume(bounds);
  if (!sameCoordinates(expected.origin, actual.origin)) {
    invalidBridgeResponse('Paper bridge exact-structure origin did not match the requested inspection.');
  }

  const entryCount = actual.placements.length + actual.runs.length;
  if (expected.maxResults !== undefined && entryCount > expected.maxResults) {
    invalidBridgeResponse('Paper bridge exact structure exceeded the requested maxResults.');
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
      invalidBridgeResponse('Paper bridge returned block geometry outside the requested inspection.');
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
    invalidBridgeResponse('Paper bridge block structure exceeded the requested inspection volume.');
  }
  if (referencedPalettes.size !== actual.palettes.length) {
    invalidBridgeResponse('Paper bridge returned an unused exact palette.');
  }
}

export function requireMatchingGetBlocksResponse(expected: GetBlocksInput, actual: ExactBlockStructureOutput): void {
  const bounds = normalizedBounds(expected.min, expected.max);
  requireMatchingExactStructureResponse(
    { world: expected.world, origin: bounds.min, bounds, maxResults: expected.maxResults },
    actual,
  );
}

export function requireMatchingScanResponse(
  expected: ScanOrthographicViewInput,
  actual: ExactBlockStructureOutput,
): void {
  const basis = VIEW_BASIS[expected.direction];
  const bounds = viewBounds(expected, basis);
  requireMatchingExactStructureResponse(
    { world: expected.world, origin: bounds.min, bounds, maxResults: expected.maxResults },
    actual,
  );

  const footprints: ViewFootprint[] = [];
  const addFootprint = (
    from: z.infer<typeof BlockPositionSchema> | undefined,
    to: z.infer<typeof BlockPositionSchema> | undefined,
  ): void => {
    if (from === undefined || to === undefined) {
      invalidBridgeResponse('Paper bridge returned overflowing orthographic geometry.');
    }
    const fromView = viewCoordinates(from, expected.origin, basis);
    const toView = viewCoordinates(to, expected.origin, basis);
    if (fromView.distance !== toView.distance || fromView.distance < 1 || fromView.distance > expected.maxDistance) {
      invalidBridgeResponse('Paper bridge returned orthographic geometry outside the requested viewport.');
    }
    const footprint = {
      minHorizontal: Math.min(fromView.horizontal, toView.horizontal),
      maxHorizontal: Math.max(fromView.horizontal, toView.horizontal),
      minVertical: Math.min(fromView.vertical, toView.vertical),
      maxVertical: Math.max(fromView.vertical, toView.vertical),
    };
    if (
      footprint.minHorizontal < -expected.horizontalRadius ||
      footprint.maxHorizontal > expected.horizontalRadius ||
      footprint.minVertical < -expected.verticalRadius ||
      footprint.maxVertical > expected.verticalRadius
    ) {
      invalidBridgeResponse('Paper bridge returned orthographic geometry outside the requested viewport.');
    }
    if (footprints.some((other) => footprintsOverlap(footprint, other))) {
      invalidBridgeResponse('Paper bridge returned multiple orthographic blocks on one sightline.');
    }
    footprints.push(footprint);
  };

  for (const placement of actual.placements) {
    const position = resolveOffset(actual.origin, placement[1], placement[2], placement[3]);
    addFootprint(position, position);
  }
  for (const run of actual.runs) {
    addFootprint(
      resolveOffset(actual.origin, run[1], run[2], run[3]),
      resolveOffset(actual.origin, run[4], run[5], run[6]),
    );
  }
}

interface ViewFootprint {
  readonly minHorizontal: number;
  readonly maxHorizontal: number;
  readonly minVertical: number;
  readonly maxVertical: number;
}

function footprintsOverlap(left: ViewFootprint, right: ViewFootprint): boolean {
  return (
    left.minHorizontal <= right.maxHorizontal &&
    right.minHorizontal <= left.maxHorizontal &&
    left.minVertical <= right.maxVertical &&
    right.minVertical <= left.maxVertical
  );
}

function viewCoordinates(
  position: z.infer<typeof BlockPositionSchema>,
  origin: z.infer<typeof BlockPositionSchema>,
  basis: (typeof VIEW_BASIS)[keyof typeof VIEW_BASIS],
): { readonly horizontal: number; readonly vertical: number; readonly distance: number } {
  const delta = { x: position.x - origin.x, y: position.y - origin.y, z: position.z - origin.z };
  const dot = (axis: (typeof basis)[keyof typeof basis]): number =>
    delta.x * axis.x + delta.y * axis.y + delta.z * axis.z;
  return { horizontal: dot(basis.horizontal), vertical: dot(basis.vertical), distance: dot(basis.forward) };
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
      outputSchema: toolOutputSchema(ExactBlockStructureOutputSchema),
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
          const result = await bridge.request(BRIDGE_ROUTES.getBlocks, callId, ExactBlockStructureOutputSchema, input);
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
        'Return a selected zero-based non-air depth on each bounded world-axis sightline as replay-ready exact palettes, origin-relative placements, and inclusive cuboid runs. Depth 0 is the first non-air block, 1 is the second, and so on. Add a concise label to use the result directly with set_blocks.' +
        (toolConfiguration.get_server_status
          ? ' Active scan and result ceilings are reported by get_server_status with include.configuration=true.'
          : ''),
      inputSchema: ScanOrthographicViewInputSchema,
      outputSchema: toolOutputSchema(ExactBlockStructureOutputSchema),
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
            ExactBlockStructureOutputSchema,
            input,
          );
          requireMatchingScanResponse(input, result);
          const entries = result.placements.length + result.runs.length;
          const blockCount = structureBlockCount(result.placements, result.runs);
          return successResult(
            result,
            `Visible blocks: ${blockCount}; structure entries: ${entries}; palettes: ${result.palettes.length}; world: ${result.world}.`,
          );
        },
      ),
  );
  if (!toolConfiguration.scan_orthographic_view) scanOrthographicView.disable();
}
