import { randomUUID } from 'node:crypto';
import {
  CLIENT_INFO_META_KEY,
  McpServer,
  type CallToolResult,
  type RegisteredTool,
  type ServerContext,
  type ToolAnnotations,
} from '@modelcontextprotocol/server';
import * as z from 'zod/v4';

const INT32_MIN = -2_147_483_648;
const INT32_MAX = 2_147_483_647;

export interface BridgeConfig {
  baseUrl: string;
  url: URL;
  token: string;
}

const BlockPositionSchema = z.object({
  x: z.number().int().min(INT32_MIN).max(INT32_MAX).describe('World X block coordinate.'),
  y: z.number().int().min(INT32_MIN).max(INT32_MAX).describe('World Y block coordinate.'),
  z: z.number().int().min(INT32_MIN).max(INT32_MAX).describe('World Z block coordinate.'),
}).strict().describe('An absolute Minecraft block position.');

const BoundsSchema = z.object({
  min: BlockPositionSchema.describe('Inclusive minimum corner after coordinate normalization.'),
  max: BlockPositionSchema.describe('Inclusive maximum corner after coordinate normalization.'),
}).strict().describe('Normalized inclusive region bounds.');

const DimensionsSchema = z.object({
  x: z.number().int().positive().describe('Region size along X in blocks.'),
  y: z.number().int().positive().describe('Region size along Y in blocks.'),
  z: z.number().int().positive().describe('Region size along Z in blocks.'),
}).strict().describe('Inclusive region dimensions in blocks.');

const CountRegionBlockStatesInputSchema = z.object({
  world: z.string().min(1).describe('Exact name of an already loaded Paper world.'),
  min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
  max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
}).strict().describe('Region whose canonical block states should be counted.');

const CountRegionBlockStatesOutputSchema = z.object({
  world: z.string().min(1).describe('Inspected world name.'),
  bounds: BoundsSchema,
  dimensions: DimensionsSchema,
  volume: z.number().int().positive().describe('Total blocks scanned, including air.'),
  blockStateCounts: z.record(z.string().min(1), z.number().int().nonnegative())
    .describe('Canonical block-state string to occurrence count, including properties when present.'),
}).strict().describe('Complete block-state histogram for the region.');

const GetRegionBlocksInputSchema = z.object({
  world: z.string().min(1).describe('Exact name of an already loaded Paper world.'),
  min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
  max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
  includeBlockStatePatterns: z.array(z.string().min(1)).optional().default([])
    .describe('Optional allowlist of block-state patterns. Omitted properties match any value; an empty list allows all states.'),
  excludeBlockStatePatterns: z.array(z.string().min(1)).optional().default([])
    .describe('Block-state patterns rejected after include filtering. Omitted properties match any value.'),
  includeAir: z.boolean().optional()
    .describe('Whether air-family states may match; omission uses the Paper plugin default.'),
  maxResults: z.number().int().min(1).max(INT32_MAX).optional()
    .describe('Maximum returned blocks or runs. Results are never truncated: exceeding this limit fails the call.'),
  format: z.enum(['blocks', 'runs']).optional()
    .describe('blocks returns individual positions; runs returns lossless axis-aligned spans. Omission uses the plugin default.'),
}).strict().describe('Filters and return format for exact region block data.');

const RegionBlocksOutputBase = {
  world: z.string().min(1).describe('Inspected world name.'),
  bounds: BoundsSchema,
  volume: z.number().int().positive().describe('Total blocks scanned before filtering.'),
  matchedBlockCount: z.number().int().nonnegative().describe('Total matching blocks represented by the response.'),
};

const GetRegionBlocksOutputSchema = z.discriminatedUnion('format', [
  z.object({
    ...RegionBlocksOutputBase,
    format: z.literal('blocks').describe('Response contains one entry per matching block.'),
    blocks: z.array(z.object({
      position: BlockPositionSchema,
      blockState: z.string().min(1).describe('Canonical block state at position.'),
    }).strict().describe('One matching block.')).describe('Matching blocks in deterministic scan order.'),
  }).strict(),
  z.object({
    ...RegionBlocksOutputBase,
    format: z.literal('runs').describe('Response contains lossless axis-aligned block runs.'),
    runs: z.array(z.object({
      blockState: z.string().min(1).describe('Canonical block state shared by the run.'),
      from: BlockPositionSchema.describe('Inclusive first block of the run.'),
      to: BlockPositionSchema.describe('Inclusive last block of the run.'),
    }).strict().describe('A lossless run of matching blocks.')).describe('Matching block runs in deterministic scan order.'),
  }).strict(),
]).describe('Exact matching block data; inspect format before reading blocks or runs.');

const OrthographicViewDirectionSchema = z.enum(['north', 'east', 'south', 'west', 'up', 'down'])
  .describe('World-axis scan direction: north=-Z, east=+X, south=+Z, west=-X, up=+Y, down=-Y.');

const ScanOrthographicViewInputSchema = z.object({
  world: z.string().min(1).describe('Exact name of an already loaded Paper world.'),
  origin: BlockPositionSchema.describe('View anchor; scanning begins one block away and excludes the origin.'),
  direction: OrthographicViewDirectionSchema,
  horizontalRadius: z.number().int().min(0).max(INT32_MAX)
    .describe('Cells on each side of the center sightline along the returned horizontal basis.'),
  verticalRadius: z.number().int().min(0).max(INT32_MAX)
    .describe('Cells on each side of the center sightline along the returned vertical basis.'),
  maxDistance: z.number().int().min(1).max(INT32_MAX)
    .describe('Maximum forward scan distance; distance 1 is adjacent to origin.'),
  maxResults: z.number().int().min(1).max(INT32_MAX).optional()
    .describe('Maximum visible blocks. Results are never truncated: exceeding this limit fails the call.'),
  format: z.enum(['blocks', 'grid']).optional().default('blocks')
    .describe('blocks returns explicit positions; grid returns compact lossless palette and distance matrices.'),
}).strict().describe('Bounded orthographic sightlines to scan for their first non-air blocks.');

const AxisVectorSchema = z.object({
  x: z.number().int().min(-1).max(1).describe('X component.'),
  y: z.number().int().min(-1).max(1).describe('Y component.'),
  z: z.number().int().min(-1).max(1).describe('Z component.'),
}).strict().describe('A world-axis unit vector.');

const ViewMetadata = {
  world: z.string().min(1).describe('Scanned world name.'),
  origin: BlockPositionSchema,
  direction: OrthographicViewDirectionSchema,
  basis: z.object({
    forward: AxisVectorSchema.describe('Direction of increasing sightline distance.'),
    horizontal: AxisVectorSchema.describe('Direction of increasing horizontal offset.'),
    vertical: AxisVectorSchema.describe('Direction of increasing vertical offset.'),
  }).strict().describe('Basis for converting view-relative offsets to world positions.'),
  viewport: z.object({
    horizontalRadius: z.number().int().nonnegative().describe('Horizontal radius used.'),
    verticalRadius: z.number().int().nonnegative().describe('Vertical radius used.'),
    maxDistance: z.number().int().positive().describe('Forward distance used.'),
  }).strict().describe('Resolved scan dimensions.'),
  bounds: BoundsSchema.describe('Inclusive world-space bounds scanned.'),
  scannedVolume: z.number().int().positive().describe('Total blocks checked across all sightlines.'),
  visibleBlockCount: z.number().int().nonnegative().describe('Sightlines whose first non-air block was found.'),
};

const ScanOrthographicViewBlocksOutputSchema = z.object({
  ...ViewMetadata,
  format: z.literal('blocks').describe('Response contains explicit visible-block entries.'),
  blocks: z.array(z.object({
    position: BlockPositionSchema.describe('Absolute position of the first non-air block.'),
    offset: z.object({
      horizontal: z.number().int().min(INT32_MIN).max(INT32_MAX)
        .describe('Signed displacement along basis.horizontal.'),
      vertical: z.number().int().min(INT32_MIN).max(INT32_MAX)
        .describe('Signed displacement along basis.vertical.'),
      distance: z.number().int().min(1).max(INT32_MAX)
        .describe('Positive displacement along basis.forward; 1 is adjacent to origin.'),
    }).strict().describe('View-relative location of the visible block.'),
    blockState: z.string().min(1).describe('Canonical state of the visible block.'),
  }).strict().describe('First non-air block on one sightline.')).describe('Visible blocks in deterministic viewport order.'),
}).strict().describe('Orthographic scan with explicit block positions.');

const ScanOrthographicViewGridOutputSchema = z.object({
  ...ViewMetadata,
  format: z.literal('grid').describe('Response contains compact palette and distance matrices.'),
  blockStatePalette: z.array(z.string().min(1))
    .describe('Canonical states indexed from 1 by blockStateIndexRows; index 0 means no visible block.'),
  blockStateIndexRows: z.array(z.array(z.number().int().nonnegative()))
    .describe('Top-to-bottom rows and left-to-right cells containing palette indices; 0 means empty sightline.'),
  distanceRows: z.array(z.array(z.number().int().nonnegative()))
    .describe('Distances aligned with blockStateIndexRows; 0 means empty sightline.'),
}).strict().describe('Lossless compact orthographic scan.');

const ScanOrthographicViewOutputSchema = z.discriminatedUnion('format', [
  ScanOrthographicViewBlocksOutputSchema,
  ScanOrthographicViewGridOutputSchema,
]).describe('Orthographic scan result; inspect format before reading blocks or grid fields.');

type ScanOrthographicViewBlocksOutput = z.infer<typeof ScanOrthographicViewBlocksOutputSchema>;

function compactView(
  view: ScanOrthographicViewBlocksOutput,
): z.infer<typeof ScanOrthographicViewGridOutputSchema> {
  const width = 2 * view.viewport.horizontalRadius + 1;
  const height = 2 * view.viewport.verticalRadius + 1;
  const blockStateIndexRows = Array.from({ length: height }, () => Array<number>(width).fill(0));
  const distanceRows = Array.from({ length: height }, () => Array<number>(width).fill(0));
  const blockStatePalette: string[] = [];
  const paletteIndices = new Map<string, number>();

  for (const block of view.blocks) {
    const rowIndex = view.viewport.verticalRadius - block.offset.vertical;
    const columnIndex = block.offset.horizontal + view.viewport.horizontalRadius;
    const blockStateIndexRow = blockStateIndexRows[rowIndex];
    const distanceRow = distanceRows[rowIndex];
    if (blockStateIndexRow === undefined || distanceRow === undefined
        || columnIndex < 0 || columnIndex >= width) {
      throw new DirtToolError('bridge_invalid_response', 'Bridge returned a view block outside its viewport.');
    }

    let paletteIndex = paletteIndices.get(block.blockState);
    if (paletteIndex === undefined) {
      blockStatePalette.push(block.blockState);
      paletteIndex = blockStatePalette.length;
      paletteIndices.set(block.blockState, paletteIndex);
    }
    blockStateIndexRow[columnIndex] = paletteIndex;
    distanceRow[columnIndex] = block.offset.distance;
  }

  const { blocks: _blocks, ...metadata } = view;
  return { ...metadata, format: 'grid', blockStatePalette, blockStateIndexRows, distanceRows };
}

const ReplaceRegionBlocksInputSchema = z.object({
  world: z.string().min(1).describe('Exact name of an already loaded Paper world.'),
  min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
  max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
  sourceBlockState: z.string().min(1)
    .describe('Exact canonical block state to replace, including properties when they must match.'),
  destinationBlockState: z.string().min(1)
    .describe('Canonical block state to write, including desired properties.'),
  dryRun: z.boolean().optional()
    .describe('Preview counts without mutating the world; omission uses the Paper plugin default.'),
}).strict().describe('Exact block-state replacement in an inclusive region.');

const ReplaceRegionBlocksOutputSchema = z.object({
  world: z.string().min(1).describe('Edited world name.'),
  bounds: BoundsSchema,
  sourceBlockState: z.string().min(1).describe('Canonical source state used for matching.'),
  destinationBlockState: z.string().min(1).describe('Canonical destination state used for writing.'),
  dryRun: z.boolean().describe('Whether the world was left unchanged.'),
  matchedBlockCount: z.number().int().nonnegative().describe('Blocks matching sourceBlockState.'),
  changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
}).strict().describe('Completed or previewed exact block-state replacement.');

const FillRegionInputSchema = z.object({
  world: z.string().min(1).describe('Exact name of an already loaded Paper world.'),
  min: BlockPositionSchema.describe('One inclusive corner; ordering relative to max does not matter.'),
  max: BlockPositionSchema.describe('The other inclusive corner; ordering relative to min does not matter.'),
  blockState: z.string().min(1).describe('Canonical block state to write throughout the region.'),
  dryRun: z.boolean().optional()
    .describe('Preview counts without mutating the world; omission uses the Paper plugin default.'),
}).strict().describe('Uniform block-state fill of an inclusive region.');

const FillRegionOutputSchema = z.object({
  world: z.string().min(1).describe('Edited world name.'),
  bounds: BoundsSchema,
  blockState: z.string().min(1).describe('Canonical block state used for the fill.'),
  dryRun: z.boolean().describe('Whether the world was left unchanged.'),
  volume: z.number().int().positive().describe('Total blocks in the region.'),
  changedBlockCount: z.number().int().nonnegative().describe('Blocks changed, or that would change in a dry run.'),
}).strict().describe('Completed or previewed region fill.');

const SetBlocksInputSchema = z.object({
  world: z.string().min(1).describe('Exact name of an already loaded Paper world.'),
  changes: z.array(z.object({
    position: BlockPositionSchema,
    blockState: z.string().min(1).describe('Canonical block state to write at this position.'),
  }).strict()).min(1)
    .describe('Distinct block positions and their destination states. Duplicate positions are rejected.'),
  dryRun: z.boolean().optional()
    .describe('Preview exact counts without mutating the world; omission uses the Paper plugin default.'),
}).strict().describe('One sparse, undoable block edit across explicitly listed positions.');

const SetBlocksOutputSchema = z.object({
  world: z.string().min(1).describe('Edited world name.'),
  dryRun: z.boolean().describe('Whether the world was left unchanged.'),
  blockCount: z.number().int().positive().describe('Distinct positions in the request.'),
  changedBlockCount: z.number().int().nonnegative()
    .describe('Blocks changed, or that would change in a dry run.'),
  unchangedBlockCount: z.number().int().nonnegative()
    .describe('Blocks already in their requested state.'),
}).strict().describe('Completed or previewed sparse block edit.');

const UndoLastDirtEditInputSchema = z.object({
  world: z.string().min(1).describe('Exact name of the loaded world whose Dirt edit should be undone.'),
}).strict().describe('World-scoped Dirt edit history lookup.');

const UndoLastDirtEditOutputSchema = z.object({
  world: z.string().min(1).describe('World in which the edit was undone.'),
  changedBlockCount: z.number().int().positive().describe('Blocks restored by the undo.'),
}).strict().describe('Result of undoing the newest successful Dirt edit in this world.');

const RunMinecraftCommandsInputSchema = z.object({
  commands: z.array(z.string().min(1)).min(1)
    .describe('Registered Minecraft commands in execution order. Each may include one in-game leading slash.'),
}).strict().describe('An ordered batch of one or more commands to dispatch through Paper.');

const CommandOutcomeSchema = z.enum([
  'dispatched',
  'not_found',
  'dispatch_failed',
]);

const RunMinecraftCommandsOutputSchema = z.object({
  sender: z.object({
    name: z.string().min(1).describe('Actual Paper command-sender name.'),
    isOperator: z.literal(true).describe('The sender has operator/console-equivalent permissions.'),
    isPlayer: z.literal(false).describe('The supported Paper feedback sender is not a player entity.'),
  }).strict(),
  feedbackTruncated: z.boolean()
    .describe('Whether request-wide command feedback exceeded the configured character limit.'),
  results: z.array(z.object({
    command: z.string().min(1).describe('Normalized command dispatched without the in-game leading slash.'),
    outcome: CommandOutcomeSchema.describe('Paper dispatch outcome; dispatched is not a semantic success signal.'),
    feedback: z.array(z.string()).describe('Plain-text feedback emitted synchronously during dispatch.'),
    message: z.string().nullable().describe('Dispatch failure explanation, otherwise null.'),
  }).strict()).min(1).describe('One result per supplied command in the original order.'),
}).strict().describe('Ordered Paper command dispatch results and bounded feedback.');

const ErrorSchema = z.object({
  error: z.object({
    code: z.string().min(1).describe('Stable machine-readable error code.'),
    message: z.string().min(1).describe('Human-readable explanation.'),
  }).strict(),
}).strict().describe('Structured Dirt error.');

const PingServerOutputSchema = z.object({
  status: z.literal('ok').describe('All Dirt, Paper, and FAWE health checks passed.'),
}).strict().describe('Successful end-to-end Dirt server health check.');

const LimitConfigurationSchema = z.object({
  maxRegionVolume: z.number().int().positive().describe('Maximum cuboid mutation/count volume or explicit positions in set_blocks.'),
  maxChangedBlocks: z.number().int().positive().describe('Maximum blocks one edit may change.'),
  maxRegionBlocksVolume: z.number().int().positive().describe('Maximum get_region_blocks scan volume.'),
  defaultRegionBlocksResultLimit: z.number().int().positive().describe('Default get_region_blocks result limit.'),
  maxRegionBlocksResultLimit: z.number().int().positive().describe('Maximum get_region_blocks result limit.'),
  maxOrthographicViewVolume: z.number().int().positive().describe('Maximum orthographic scan volume.'),
  defaultOrthographicViewResultLimit: z.number().int().positive().describe('Default orthographic visible-block limit.'),
  maxOrthographicViewResultLimit: z.number().int().positive().describe('Maximum orthographic visible-block limit.'),
  maxCommandsPerRequest: z.number().int().positive().describe('Maximum commands accepted in one ordered batch.'),
  maxCommandFeedbackCharacters: z.number().int().positive().describe('Maximum plain-text feedback characters retained per command batch.'),
  undoHistoryPerWorld: z.number().int().nonnegative().describe('In-memory Dirt undo entries retained per world.'),
}).strict().describe('Active limits that constrain Dirt inspection and mutation tools.');

const DefaultConfigurationSchema = z.object({
  regionBlocksIncludeAir: z.boolean().describe('Default air inclusion for get_region_blocks.'),
  regionBlocksFormat: z.enum(['blocks', 'runs']).describe('Default get_region_blocks format.'),
  replaceRegionBlocksDryRun: z.boolean().describe('Default dry-run behavior for replace_region_blocks.'),
  fillRegionDryRun: z.boolean().describe('Default dry-run behavior for fill_region.'),
  setBlocksDryRun: z.boolean().describe('Default dry-run behavior for set_blocks.'),
}).strict().describe('Active optional-argument defaults for Dirt tools.');

const ServerStatusSchema = z.object({
  builds: z.object({
    minecraft: z.string().min(1).describe('Running Minecraft build.'),
    paper: z.string().min(1).describe('Full running Paper build identifier.'),
    dirtMcp: z.string().min(1).describe('Running Dirt MCP Paper plugin build.'),
    fawe: z.string().min(1).describe('Running FastAsyncWorldEdit build.'),
  }).strict().describe('Exact runtime build identifiers.'),
  performance: z.object({
    tpsOneMinute: z.number().nonnegative().describe('Paper one-minute ticks per second.'),
    averageTickTimeMillis: z.number().nonnegative().describe('Paper average tick duration in milliseconds.'),
  }).strict().describe('Lightweight current Paper performance indicators.'),
  players: z.object({
    online: z.number().int().nonnegative().describe('Current online player count.'),
    maximum: z.number().int().nonnegative().describe('Configured player capacity.'),
    entries: z.array(z.object({
      name: z.string().min(1).describe('Current player name.'),
      world: z.string().min(1).describe('Loaded world containing the player.'),
      gameMode: z.enum(['survival', 'creative', 'adventure', 'spectator']).describe('Current game mode.'),
      blockPosition: BlockPositionSchema.describe('Current integer block position.'),
    }).strict()).describe('Online players sorted by name, with location context.'),
  }).strict().describe('Current player presence.'),
  worlds: z.array(z.object({
    name: z.string().min(1).describe('Exact loaded world name accepted by world tools.'),
    environment: z.string().min(1).describe('Paper world environment, such as normal or nether.'),
    minY: z.number().int().describe('Minimum valid block Y.'),
    maxY: z.number().int().describe('Maximum valid block Y, inclusive.'),
    spawn: BlockPositionSchema.describe('Current world spawn block.'),
    timeOfDay: z.number().int().min(0).max(23_999).describe('Current Minecraft time of day.'),
    storm: z.boolean().describe('Whether the world currently has a storm.'),
    thundering: z.boolean().describe('Whether the world is currently thundering.'),
    playerCount: z.number().int().nonnegative().describe('Players currently in this world.'),
  }).strict()).describe('All currently loaded worlds in Paper order.'),
  limits: LimitConfigurationSchema,
  defaults: DefaultConfigurationSchema,
}).strict().describe('Current lightweight Paper context for grounding subsequent Dirt tool calls.');

const READ_WORLD_ANNOTATIONS: ToolAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: true,
};
const MUTATION_ANNOTATIONS: ToolAnnotations = {
  readOnlyHint: false,
  destructiveHint: true,
  idempotentHint: true,
  openWorldHint: true,
};
const UNDO_ANNOTATIONS: ToolAnnotations = {
  readOnlyHint: false,
  destructiveHint: true,
  idempotentHint: false,
  openWorldHint: true,
};
const COMMAND_ANNOTATIONS: ToolAnnotations = {
  readOnlyHint: false,
  destructiveHint: true,
  idempotentHint: false,
  openWorldHint: true,
};

class DirtToolError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = 'DirtToolError';
  }
}

function successResult(structuredContent: Record<string, unknown>, summary: string): CallToolResult {
  return { content: [{ type: 'text', text: summary }], structuredContent };
}

function errorResult(error: unknown, context: string): CallToolResult {
  const detail = error instanceof DirtToolError
    ? error
    : new DirtToolError('dirt_internal_error', error instanceof Error ? error.message : String(error));
  const structuredContent = { error: { code: detail.code, message: detail.message } };
  return {
    content: [{ type: 'text', text: `${context}: ${detail.message}` }],
    structuredContent,
    isError: true,
  };
}

function logValue(value: string | number): string {
  return JSON.stringify(value).replaceAll('\u2028', '\\u2028').replaceAll('\u2029', '\\u2029');
}

function clientLabel(context: ServerContext): string {
  const envelope = context.mcpReq.envelope as Record<string, unknown> | undefined;
  const current = envelope?.[CLIENT_INFO_META_KEY];
  if (typeof current === 'object' && current !== null) {
    const { name, version } = current as { name?: unknown; version?: unknown };
    if (typeof name === 'string' && typeof version === 'string') {
      return `${name}/${version}`;
    }
  }
  return 'unknown';
}

async function auditToolCall(
  tool: string,
  world: string | undefined,
  context: ServerContext,
  call: (callId: string) => Promise<CallToolResult>,
): Promise<CallToolResult> {
  const callId = randomUUID();
  const started = performance.now();
  let outcome = 'exception';
  try {
    const result = await call(callId);
    outcome = result.isError === true ? 'error' : 'ok';
    return result;
  } finally {
    const fields = [
      `tool=${tool}`,
      `call=${callId}`,
      `request=${logValue(context.mcpReq.id)}`,
      `client=${logValue(clientLabel(context))}`,
    ];
    if (world !== undefined) fields.push(`world=${logValue(world)}`);
    fields.push(`outcome=${outcome}`);
    fields.push(`duration_ms=${Math.max(0, Math.round(performance.now() - started))}`);
    process.stderr.write(`Dirt MCP tool_call ${fields.join(' ')}\n`);
  }
}

export function registerTools(
  server: McpServer,
  config: BridgeConfig,
  registrations: RegisteredTool[],
): void {
  const register: typeof server.registerTool = server.registerTool.bind(server);

  registrations.push(register('ping_server', {
    title: 'Ping Dirt server',
    description: 'Run a non-mutating end-to-end health check across the authenticated bridge, Dirt plugin, Paper, and a Paper-backed FAWE session. Returns only status ok on success.',
    inputSchema: z.object({}).strict().describe('No arguments.'),
    outputSchema: PingServerOutputSchema,
    annotations: READ_WORLD_ANNOTATIONS,
  }, async (_input, context) => auditToolCall('ping_server', undefined, context, async (callId) => {
    try {
      const result = await bridgeRequest(config, '/v1/ping', callId, PingServerOutputSchema);
      return successResult(result, 'ok');
    } catch (error: unknown) {
      return errorResult(error, `Dirt server health check failed at ${config.baseUrl}`);
    }
  })));

  registrations.push(register('get_server_status', {
    title: 'Get Dirt server status',
    description: 'Return current Minecraft, Paper, Dirt MCP, and FAWE builds; TPS; online players and block positions; loaded worlds; and active Dirt limits/defaults. Use this to ground later world operations.',
    inputSchema: z.object({}).strict().describe('No arguments.'),
    outputSchema: ServerStatusSchema,
    annotations: READ_WORLD_ANNOTATIONS,
  }, async (_input, context) => auditToolCall('get_server_status', undefined, context, async (callId) => {
    try {
      const status = await bridgeRequest(config, '/v1/server-status', callId, ServerStatusSchema);
      const worldNames = status.worlds.map((world) => world.name).join(', ') || 'none';
      return successResult(
        status,
        `Paper ${status.builds.paper}; players ${status.players.online}/${status.players.maximum}; loaded worlds: ${worldNames}.`,
      );
    } catch (error: unknown) {
      return errorResult(error, `Could not get Dirt server status from ${config.baseUrl}`);
    }
  })));

  registrations.push(register('count_region_block_states', {
    title: 'Count region block states',
    description: 'Return a complete canonical block-state histogram for an inclusive region. Use this before exact retrieval when totals are sufficient.',
    inputSchema: CountRegionBlockStatesInputSchema,
    outputSchema: CountRegionBlockStatesOutputSchema,
    annotations: READ_WORLD_ANNOTATIONS,
  }, async (input, context) => auditToolCall('count_region_block_states', input.world, context, async (callId) => {
    try {
      const result = await bridgeRequest(config, '/v1/count-region-block-states', callId, CountRegionBlockStatesOutputSchema, jsonPost(input), 30_000);
      return successResult(result, `Counted ${result.volume} blocks across ${Object.keys(result.blockStateCounts).length} block states in ${result.world}.`);
    } catch (error: unknown) {
      return errorResult(error, 'Could not count region block states');
    }
  })));

  registrations.push(register('get_region_blocks', {
    title: 'Get region blocks',
    description: 'Return filtered exact blocks or lossless runs from an inclusive region. Results fail rather than truncate when maxResults is exceeded.',
    inputSchema: GetRegionBlocksInputSchema,
    outputSchema: GetRegionBlocksOutputSchema,
    annotations: READ_WORLD_ANNOTATIONS,
  }, async (input, context) => auditToolCall('get_region_blocks', input.world, context, async (callId) => {
    try {
      const result = await bridgeRequest(config, '/v1/get-region-blocks', callId, GetRegionBlocksOutputSchema, jsonPost(input), 30_000);
      const entries = result.format === 'blocks' ? result.blocks.length : result.runs.length;
      const entryKind = result.format === 'blocks' ? 'block' : 'run';
      return successResult(result, `Matching blocks: ${result.matchedBlockCount}; ${entryKind} entries: ${entries}; world: ${result.world}.`);
    } catch (error: unknown) {
      return errorResult(error, 'Could not get region blocks');
    }
  })));

  registrations.push(register('scan_orthographic_view', {
    title: 'Scan an orthographic view',
    description: 'Return the first non-air block on each bounded world-axis sightline. Choose blocks for explicit positions or grid for compact lossless matrices.',
    inputSchema: ScanOrthographicViewInputSchema,
    outputSchema: ScanOrthographicViewOutputSchema,
    annotations: READ_WORLD_ANNOTATIONS,
  }, async (input, context) => auditToolCall('scan_orthographic_view', input.world, context, async (callId) => {
    try {
      const { format, ...bridgeInput } = input;
      const sparseView = await bridgeRequest(config, '/v1/scan-orthographic-view', callId, ScanOrthographicViewBlocksOutputSchema, jsonPost(bridgeInput), 30_000);
      if (format === 'grid') {
        const result = compactView(sparseView);
        const width = result.viewport.horizontalRadius * 2 + 1;
        const height = result.viewport.verticalRadius * 2 + 1;
        return successResult(result, `Scanned ${width}x${height} view: ${result.visibleBlockCount} visible blocks using ${result.blockStatePalette.length} block states.`);
      }
      return successResult(sparseView, `Scanned view in ${sparseView.world}: ${sparseView.visibleBlockCount} visible blocks returned explicitly.`);
    } catch (error: unknown) {
      return errorResult(error, 'Could not scan the orthographic view');
    }
  })));

  registrations.push(register('replace_region_blocks', {
    title: 'Replace region blocks',
    description: 'Replace one exact canonical block state throughout an inclusive region. Set dryRun=true to preview without mutation.',
    inputSchema: ReplaceRegionBlocksInputSchema,
    outputSchema: ReplaceRegionBlocksOutputSchema,
    annotations: MUTATION_ANNOTATIONS,
  }, async (input, context) => auditToolCall('replace_region_blocks', input.world, context, async (callId) => {
    try {
      const result = await bridgeRequest(config, '/v1/replace-region-blocks', callId, ReplaceRegionBlocksOutputSchema, jsonPost(input), 120_000);
      const verb = result.dryRun ? 'Would change' : 'Changed';
      return successResult(result, `${verb} ${result.changedBlockCount} of ${result.matchedBlockCount} matching blocks in ${result.world}.`);
    } catch (error: unknown) {
      return errorResult(error, 'Could not replace region blocks');
    }
  })));

  registrations.push(register('fill_region', {
    title: 'Fill a region',
    description: 'Set every block in an inclusive region to one canonical block state. Set dryRun=true to preview without mutation.',
    inputSchema: FillRegionInputSchema,
    outputSchema: FillRegionOutputSchema,
    annotations: MUTATION_ANNOTATIONS,
  }, async (input, context) => auditToolCall('fill_region', input.world, context, async (callId) => {
    try {
      const result = await bridgeRequest(config, '/v1/fill-region', callId, FillRegionOutputSchema, jsonPost(input), 120_000);
      const verb = result.dryRun ? 'Would change' : 'Changed';
      return successResult(result, `${verb} ${result.changedBlockCount} of ${result.volume} blocks in ${result.world}.`);
    } catch (error: unknown) {
      return errorResult(error, 'Could not fill the region');
    }
  })));

  registrations.push(register('set_blocks', {
    title: 'Set blocks',
    description: 'Set distinct explicit positions to individual canonical block states in one FAWE edit and one Dirt undo entry. All entries are validated before mutation; duplicate positions are rejected. Placement does not trigger Minecraft neighbor physics. Set dryRun=true to preview exact counts.',
    inputSchema: SetBlocksInputSchema,
    outputSchema: SetBlocksOutputSchema,
    annotations: MUTATION_ANNOTATIONS,
  }, async (input, context) => auditToolCall('set_blocks', input.world, context, async (callId) => {
    try {
      const result = await bridgeRequest(config, '/v1/set-blocks', callId, SetBlocksOutputSchema, jsonPost(input), 120_000);
      const verb = result.dryRun ? 'Would change' : 'Changed';
      return successResult(result, `${verb} ${result.changedBlockCount} of ${result.blockCount} explicitly listed blocks in ${result.world}.`);
    } catch (error: unknown) {
      return errorResult(error, 'Could not set blocks');
    }
  })));

  registrations.push(register('undo_last_dirt_edit', {
    title: 'Undo the last Dirt edit',
    description: 'Undo the newest successful Dirt replace, fill, or sparse set in one loaded world. History is in-memory and scoped per world.',
    inputSchema: UndoLastDirtEditInputSchema,
    outputSchema: UndoLastDirtEditOutputSchema,
    annotations: UNDO_ANNOTATIONS,
  }, async (input, context) => auditToolCall('undo_last_dirt_edit', input.world, context, async (callId) => {
    try {
      const result = await bridgeRequest(config, '/v1/undo-last-dirt-edit', callId, UndoLastDirtEditOutputSchema, jsonPost(input), 120_000);
      return successResult(result, `Undid the last Dirt edit in ${result.world}, restoring ${result.changedBlockCount} blocks.`);
    } catch (error: unknown) {
      return errorResult(error, 'Could not undo the last Dirt edit');
    }
  })));

  registrations.push(register('run_minecraft_commands', {
    title: 'Run Minecraft commands',
    description: 'Run registered vanilla, Paper, or plugin commands sequentially with operator-level permissions. The sender is not a player: player-only commands, @s, and relative context can differ. Every command is attempted once in order; arbitrary effects are immediate and are not covered by Dirt undo or edit limits.',
    inputSchema: RunMinecraftCommandsInputSchema,
    outputSchema: RunMinecraftCommandsOutputSchema,
    annotations: COMMAND_ANNOTATIONS,
  }, async (input, context) => auditToolCall('run_minecraft_commands', undefined, context, async (callId) => {
    try {
      const result = await bridgeRequest(
        config,
        '/v1/run-minecraft-commands',
        callId,
        RunMinecraftCommandsOutputSchema,
        jsonPost(input),
        120_000,
      );
      const dispatched = result.results.filter((entry) => entry.outcome === 'dispatched').length;
      const failed = result.results.some(
        (entry) => entry.outcome === 'not_found' || entry.outcome === 'dispatch_failed',
      );
      const summary = failed
        ? `Dispatched ${dispatched} of ${result.results.length} command(s); see per-command outcomes.`
        : `Dispatched ${dispatched} command(s) in order.`;
      return {
        content: [{ type: 'text', text: summary }],
        structuredContent: result,
        ...(failed ? { isError: true } : {}),
      };
    } catch (error: unknown) {
      return errorResult(error, 'Could not run Minecraft commands');
    }
  })));
}

function jsonPost(body: unknown): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

async function bridgeRequest<T>(
  config: BridgeConfig,
  path: string,
  callId: string,
  schema: z.ZodType<T>,
  init?: RequestInit,
  timeoutMilliseconds = 3_000,
): Promise<T> {
  const headers = new Headers(init?.headers);
  headers.set('Accept', 'application/json');
  headers.set('Authorization', `Bearer ${config.token}`);
  headers.set('X-Dirt-Call-Id', callId);

  let response: Response;
  try {
    response = await fetch(new URL(path, config.url), {
      ...init,
      headers,
      redirect: 'error',
      signal: AbortSignal.timeout(timeoutMilliseconds),
    });
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : String(error);
    throw new DirtToolError('bridge_unavailable', `Paper bridge request failed: ${message}`);
  }

  if (!response.ok) {
    if (response.status === 401) {
      throw new DirtToolError('bridge_unauthorized', 'Paper bridge rejected DIRT_MCP_BRIDGE_TOKEN.');
    }
    const body: unknown = await response.json().catch(() => undefined);
    const detail = ErrorSchema.safeParse(body);
    if (detail.success) {
      throw new DirtToolError(detail.data.error.code, detail.data.error.message);
    }
    throw new DirtToolError('bridge_http_error', `Paper bridge returned unstructured HTTP ${response.status}.`);
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new DirtToolError('bridge_invalid_response', 'Paper bridge returned invalid JSON.');
  }
  const parsed = schema.safeParse(body);
  if (!parsed.success) {
    throw new DirtToolError('bridge_invalid_response', 'Paper bridge response did not match the documented schema.');
  }
  return parsed.data;
}
