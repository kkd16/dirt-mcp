import { randomUUID } from 'node:crypto';
import {
  CLIENT_INFO_META_KEY,
  McpServer,
  type CallToolResult,
  type RegisteredTool,
  type ServerContext,
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
  x: z.number().int().min(INT32_MIN).max(INT32_MAX),
  y: z.number().int().min(INT32_MIN).max(INT32_MAX),
  z: z.number().int().min(INT32_MIN).max(INT32_MAX),
}).strict();

const BoundsSchema = z.object({
  min: BlockPositionSchema,
  max: BlockPositionSchema,
}).strict();

const InspectRegionInputSchema = z.object({
  world: z.string().min(1),
  min: BlockPositionSchema,
  max: BlockPositionSchema,
}).strict();

const InspectRegionOutputSchema = z.object({
  world: z.string().min(1),
  bounds: BoundsSchema,
  dimensions: z.object({
    x: z.number().int().positive(),
    y: z.number().int().positive(),
    z: z.number().int().positive(),
  }).strict(),
  volume: z.number().int().positive(),
  blockStates: z.record(z.string(), z.number().int().nonnegative()),
}).strict();

const InspectBlocksInputSchema = z.object({
  world: z.string().min(1),
  min: BlockPositionSchema,
  max: BlockPositionSchema,
  include: z.array(z.string().min(1))
    .optional().default([])
    .describe('Optional block-state allowlist; omitted state properties match any value.'),
  exclude: z.array(z.string().min(1))
    .optional().default([])
    .describe('Block-state patterns to exclude after include filtering.'),
  includeAir: z.boolean().optional()
    .describe('Include air-family states; omission uses the Paper plugin configuration.'),
  maxResults: z.number().int().min(1).max(INT32_MAX).optional()
    .describe('Maximum returned entries, bounded by Paper configuration; oversized results fail.'),
  mode: z.enum(['blocks', 'runs']).optional()
    .describe('Sparse blocks or exact axis-aligned runs; omission uses the Paper plugin configuration.'),
}).strict();

const ExactInspectionBase = {
  world: z.string().min(1),
  bounds: BoundsSchema,
  volume: z.number().int().positive(),
  matchedBlocks: z.number().int().nonnegative(),
};

const InspectBlocksOutputSchema = z.discriminatedUnion('mode', [
  z.object({
    ...ExactInspectionBase,
    mode: z.literal('blocks'),
    blocks: z.array(z.object({
      position: BlockPositionSchema,
      state: z.string().min(1),
    }).strict()),
  }).strict(),
  z.object({
    ...ExactInspectionBase,
    mode: z.literal('runs'),
    runs: z.array(z.object({
      state: z.string().min(1),
      from: BlockPositionSchema,
      to: BlockPositionSchema,
    }).strict()),
  }).strict(),
]);

const ViewDirectionSchema = z.enum(['north', 'east', 'south', 'west', 'up', 'down']);

const InspectViewInputSchema = z.object({
  world: z.string().min(1),
  origin: BlockPositionSchema
    .describe('Center anchor of the view; scanning starts one block away and never includes the origin.'),
  direction: ViewDirectionSchema
    .describe('World-axis direction: north=-Z, east=+X, south=+Z, west=-X, up=+Y, down=-Y.'),
  horizontalRadius: z.number().int().min(0).max(INT32_MAX)
    .describe('Viewport cells on each side of the center sightline along the returned horizontal basis.'),
  verticalRadius: z.number().int().min(0).max(INT32_MAX)
    .describe('Viewport cells on each side of the center sightline along the returned vertical basis.'),
  maxDistance: z.number().int().min(1).max(INT32_MAX)
    .describe('Maximum blocks to scan forward; distance 1 is adjacent to origin.'),
  maxResults: z.number().int().min(1).max(INT32_MAX).optional()
    .describe('Maximum visible blocks, bounded by Paper configuration; omission uses its default.'),
  format: z.enum(['blocks', 'grid']).optional().default('blocks')
    .describe('Use grid for a compact lossless palette and distance matrix; blocks returns explicit positions.'),
}).strict();

const AxisVectorSchema = z.object({
  x: z.number().int().min(-1).max(1),
  y: z.number().int().min(-1).max(1),
  z: z.number().int().min(-1).max(1),
}).strict();

const InspectViewBlocksOutputSchema = z.object({
  world: z.string().min(1),
  origin: BlockPositionSchema,
  direction: ViewDirectionSchema,
  basis: z.object({
    forward: AxisVectorSchema,
    horizontal: AxisVectorSchema,
    vertical: AxisVectorSchema,
  }).strict().describe('World-axis unit vectors used to convert each view-relative offset to a position.'),
  viewport: z.object({
    horizontalRadius: z.number().int().nonnegative(),
    verticalRadius: z.number().int().nonnegative(),
    maxDistance: z.number().int().positive(),
  }).strict(),
  bounds: BoundsSchema,
  scannedVolume: z.number().int().positive(),
  visibleBlocks: z.number().int().nonnegative(),
  blocks: z.array(z.object({
    position: BlockPositionSchema,
    offset: z.object({
      horizontal: z.number().int().min(INT32_MIN).max(INT32_MAX)
        .describe('Signed displacement along basis.horizontal.'),
      vertical: z.number().int().min(INT32_MIN).max(INT32_MAX)
        .describe('Signed displacement along basis.vertical.'),
      distance: z.number().int().min(1).max(INT32_MAX)
        .describe('Positive displacement along basis.forward; 1 is adjacent to origin.'),
    }).strict(),
    state: z.string().min(1),
  }).strict()),
}).strict();

const InspectViewGridOutputSchema = z.object({
  world: z.string().min(1),
  origin: BlockPositionSchema,
  direction: ViewDirectionSchema,
  basis: z.object({
    forward: AxisVectorSchema,
    horizontal: AxisVectorSchema,
    vertical: AxisVectorSchema,
  }).strict().describe('World-axis unit vectors used to reconstruct absolute positions.'),
  viewport: z.object({
    horizontalRadius: z.number().int().nonnegative(),
    verticalRadius: z.number().int().nonnegative(),
    maxDistance: z.number().int().positive(),
  }).strict(),
  bounds: BoundsSchema,
  scannedVolume: z.number().int().positive(),
  visibleBlocks: z.number().int().nonnegative(),
  format: z.literal('grid'),
  palette: z.array(z.string().min(1))
    .describe('Canonical states; stateRows uses one-based indices and reserves 0 for an empty sightline.'),
  stateRows: z.array(z.array(z.number().int().nonnegative()))
    .describe('Top-to-bottom rows, left-to-right cells; 0 means no visible block.'),
  distanceRows: z.array(z.array(z.number().int().nonnegative()))
    .describe('Distances aligned with stateRows; 0 means no visible block.'),
}).strict();

const InspectViewOutputSchema = z.union([
  InspectViewBlocksOutputSchema,
  InspectViewGridOutputSchema,
]);

type InspectViewBlocksOutput = z.infer<typeof InspectViewBlocksOutputSchema>;

function compactView(view: InspectViewBlocksOutput): z.infer<typeof InspectViewGridOutputSchema> {
  const width = 2 * view.viewport.horizontalRadius + 1;
  const height = 2 * view.viewport.verticalRadius + 1;
  const stateRows = Array.from({ length: height }, () => Array<number>(width).fill(0));
  const distanceRows = Array.from({ length: height }, () => Array<number>(width).fill(0));
  const palette: string[] = [];
  const paletteIndices = new Map<string, number>();

  for (const block of view.blocks) {
    const rowIndex = view.viewport.verticalRadius - block.offset.vertical;
    const columnIndex = block.offset.horizontal + view.viewport.horizontalRadius;
    const stateRow = stateRows[rowIndex];
    const distanceRow = distanceRows[rowIndex];
    if (stateRow === undefined || distanceRow === undefined
        || columnIndex < 0 || columnIndex >= width) {
      throw new Error('Bridge returned a view block outside its viewport');
    }

    let paletteIndex = paletteIndices.get(block.state);
    if (paletteIndex === undefined) {
      palette.push(block.state);
      paletteIndex = palette.length;
      paletteIndices.set(block.state, paletteIndex);
    }
    stateRow[columnIndex] = paletteIndex;
    distanceRow[columnIndex] = block.offset.distance;
  }

  const { blocks: _blocks, ...metadata } = view;
  return {
    ...metadata,
    format: 'grid',
    palette,
    stateRows,
    distanceRows,
  };
}

const ReplaceBlocksInputSchema = z.object({
  world: z.string().min(1),
  min: BlockPositionSchema,
  max: BlockPositionSchema,
  source: z.string().min(1),
  destination: z.string().min(1),
  dryRun: z.boolean().optional()
    .describe('Preview without mutation; omission uses the Paper plugin configuration.'),
}).strict();

const ReplaceBlocksOutputSchema = z.object({
  world: z.string().min(1),
  bounds: BoundsSchema,
  source: z.string().min(1),
  destination: z.string().min(1),
  dryRun: z.boolean(),
  matchedBlocks: z.number().int().nonnegative(),
  changedBlocks: z.number().int().nonnegative(),
}).strict();

const FillRegionInputSchema = z.object({
  world: z.string().min(1),
  min: BlockPositionSchema,
  max: BlockPositionSchema,
  destination: z.string().min(1),
  dryRun: z.boolean().optional()
    .describe('Preview without mutation; omission uses the Paper plugin configuration.'),
}).strict();

const FillRegionOutputSchema = z.object({
  world: z.string().min(1),
  bounds: BoundsSchema,
  destination: z.string().min(1),
  dryRun: z.boolean(),
  volume: z.number().int().positive(),
  changedBlocks: z.number().int().nonnegative(),
}).strict();

const UndoLastEditInputSchema = z.object({
  world: z.string().min(1),
}).strict();

const UndoLastEditOutputSchema = z.object({
  world: z.string().min(1),
  changedBlocks: z.number().int().positive(),
}).strict();

const ErrorSchema = z.object({
  error: z.object({
    code: z.string(),
    message: z.string(),
  }).strict(),
}).strict();

const HealthSchema = z.object({
  status: z.literal('ok'),
  service: z.literal('dirt-mcp-paper'),
  version: z.string().min(1),
  minecraftVersion: z.string().min(1),
  configuration: z.object({
    bridge: z.object({
      port: z.number().int().min(1).max(65_535),
      backlog: z.number().int().nonnegative(),
      shutdownDelaySeconds: z.number().int().nonnegative(),
      maxRequestBytes: z.number().int().positive(),
      minimumTokenBytes: z.number().int().positive(),
    }).strict(),
    limits: z.object({
      maxRegionVolume: z.number().int().positive(),
      maxChangedBlocks: z.number().int().positive(),
      maxExactInspectionVolume: z.number().int().positive(),
      defaultExactResults: z.number().int().positive(),
      maxExactResults: z.number().int().positive(),
      maxViewVolume: z.number().int().positive(),
      defaultViewResults: z.number().int().positive(),
      maxViewResults: z.number().int().positive(),
      undoHistoryPerWorld: z.number().int().nonnegative(),
    }).strict(),
    defaults: z.object({
      exactInspectionIncludeAir: z.boolean(),
      exactInspectionMode: z.enum(['blocks', 'runs']),
      replaceDryRun: z.boolean(),
      fillDryRun: z.boolean(),
    }).strict(),
  }).strict(),
}).strict();

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
    if (world !== undefined) {
      fields.push(`world=${logValue(world)}`);
    }
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

  registrations.push(register(
    'dirt_status',
    {
      title: 'Dirt MCP status',
      description: 'Check bridge availability and report the active non-secret Paper plugin configuration.',
      inputSchema: z.object({}),
      outputSchema: HealthSchema,
    },
    async (_input, context) => auditToolCall('dirt_status', undefined, context, async (callId) => {
      try {
        const response = await bridgeRequest(config, '/v1/health', callId);
        const health = HealthSchema.parse(await response.json());
        return {
          content: [{ type: 'text', text: JSON.stringify(health, null, 2) }],
          structuredContent: health,
        };
      } catch (error: unknown) {
        const message = error instanceof Error ? error.message : String(error);
        return {
          content: [{
            type: 'text',
            text: `Dirt MCP Paper bridge is unavailable at ${config.baseUrl}: ${message}`,
          }],
          isError: true,
        };
      }
    }),
  ));

  registrations.push(register(
    'inspect_region',
    {
      title: 'Inspect a region',
      description: 'Count block states in a bounded region of already-loaded Minecraft chunks.',
      inputSchema: InspectRegionInputSchema,
      outputSchema: InspectRegionOutputSchema,
    },
    async (input, context) => auditToolCall('inspect_region', input.world, context, async (callId) => {
      try {
        const response = await bridgeRequest(
          config,
          '/v1/inspect-region',
          callId,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(input),
          },
          30_000,
        );
        const inspection = InspectRegionOutputSchema.parse(await response.json());
        return {
          content: [{ type: 'text', text: JSON.stringify(inspection, null, 2) }],
          structuredContent: inspection,
        };
      } catch (error: unknown) {
        const message = error instanceof Error ? error.message : String(error);
        return {
          content: [{ type: 'text', text: `Could not inspect the region: ${message}` }],
          isError: true,
        };
      }
    }),
  ));

  registrations.push(register(
    'inspect_blocks',
    {
      title: 'Inspect exact blocks',
      description: 'Return exact non-air positions or lossless runs from already-loaded chunks; use inspect_region for cheaper palette totals.',
      inputSchema: InspectBlocksInputSchema,
      outputSchema: InspectBlocksOutputSchema,
    },
    async (input, context) => auditToolCall('inspect_blocks', input.world, context, async (callId) => {
      try {
        const response = await bridgeRequest(
          config,
          '/v1/inspect-blocks',
          callId,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(input),
          },
          30_000,
        );
        const inspection = InspectBlocksOutputSchema.parse(await response.json());
        return {
          content: [{ type: 'text', text: JSON.stringify(inspection, null, 2) }],
          structuredContent: inspection,
        };
      } catch (error: unknown) {
        const message = error instanceof Error ? error.message : String(error);
        return {
          content: [{ type: 'text', text: `Could not inspect exact blocks: ${message}` }],
          isError: true,
        };
      }
    }),
  ));

  registrations.push(register(
    'inspect_view',
    {
      title: 'Inspect a view',
      description: 'Return the first non-air block on each bounded orthographic sightline. Use format=grid for compact lossless palette and distance rows, or blocks for explicit positions.',
      inputSchema: InspectViewInputSchema,
      outputSchema: InspectViewOutputSchema,
      annotations: { readOnlyHint: true },
    },
    async (input, context) => auditToolCall('inspect_view', input.world, context, async (callId) => {
      try {
        const { format, ...bridgeInput } = input;
        const response = await bridgeRequest(
          config,
          '/v1/inspect-view',
          callId,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(bridgeInput),
          },
          30_000,
        );
        const sparseView = InspectViewBlocksOutputSchema.parse(await response.json());
        if (format === 'grid') {
          const view = compactView(sparseView);
          return {
            content: [{
              type: 'text',
              text: `Compact ${view.viewport.horizontalRadius * 2 + 1}x${view.viewport.verticalRadius * 2 + 1} view: ${view.visibleBlocks} visible blocks, ${view.palette.length} states. See structuredContent for palette, stateRows, and distanceRows.`,
            }],
            structuredContent: view,
          };
        }
        return {
          content: [{ type: 'text', text: JSON.stringify(sparseView, null, 2) }],
          structuredContent: sparseView,
        };
      } catch (error: unknown) {
        const message = error instanceof Error ? error.message : String(error);
        return {
          content: [{ type: 'text', text: `Could not inspect the view: ${message}` }],
          isError: true,
        };
      }
    }),
  ));

  registrations.push(register(
    'replace_blocks',
    {
      title: 'Replace blocks',
      description: 'Replace one exact block state in a bounded region, or preview the exact result.',
      inputSchema: ReplaceBlocksInputSchema,
      outputSchema: ReplaceBlocksOutputSchema,
    },
    async (input, context) => auditToolCall('replace_blocks', input.world, context, async (callId) => {
      try {
        const response = await bridgeRequest(
          config,
          '/v1/replace-blocks',
          callId,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(input),
          },
          120_000,
        );
        const result = ReplaceBlocksOutputSchema.parse(await response.json());
        return {
          content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
          structuredContent: result,
        };
      } catch (error: unknown) {
        const message = error instanceof Error ? error.message : String(error);
        return {
          content: [{ type: 'text', text: `Could not replace blocks: ${message}` }],
          isError: true,
        };
      }
    }),
  ));

  registrations.push(register(
    'fill_region',
    {
      title: 'Fill a region',
      description: 'Set every block in a bounded region to one block state, or preview the exact result.',
      inputSchema: FillRegionInputSchema,
      outputSchema: FillRegionOutputSchema,
    },
    async (input, context) => auditToolCall('fill_region', input.world, context, async (callId) => {
      try {
        const response = await bridgeRequest(
          config,
          '/v1/fill-region',
          callId,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(input),
          },
          120_000,
        );
        const result = FillRegionOutputSchema.parse(await response.json());
        return {
          content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
          structuredContent: result,
        };
      } catch (error: unknown) {
        const message = error instanceof Error ? error.message : String(error);
        return {
          content: [{ type: 'text', text: `Could not fill the region: ${message}` }],
          isError: true,
        };
      }
    }),
  ));

  registrations.push(register(
    'undo_last_edit',
    {
      title: 'Undo the last edit',
      description: 'Undo the newest successful Dirt MCP edit in a loaded world.',
      inputSchema: UndoLastEditInputSchema,
      outputSchema: UndoLastEditOutputSchema,
    },
    async (input, context) => auditToolCall('undo_last_edit', input.world, context, async (callId) => {
      try {
        const response = await bridgeRequest(
          config,
          '/v1/undo-last-edit',
          callId,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(input),
          },
          120_000,
        );
        const result = UndoLastEditOutputSchema.parse(await response.json());
        return {
          content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
          structuredContent: result,
        };
      } catch (error: unknown) {
        const message = error instanceof Error ? error.message : String(error);
        return {
          content: [{ type: 'text', text: `Could not undo the edit: ${message}` }],
          isError: true,
        };
      }
    }),
  ));
}

async function bridgeRequest(
  config: BridgeConfig,
  path: string,
  callId: string,
  init?: RequestInit,
  timeoutMilliseconds = 3_000,
): Promise<Response> {
  const headers = new Headers(init?.headers);
  headers.set('Accept', 'application/json');
  headers.set('Authorization', `Bearer ${config.token}`);
  headers.set('X-Dirt-Call-Id', callId);

  const response = await fetch(new URL(path, config.url), {
    ...init,
    headers,
    redirect: 'error',
    signal: AbortSignal.timeout(timeoutMilliseconds),
  });
  if (response.ok) {
    return response;
  }
  if (response.status === 401) {
    throw new Error('Bridge rejected DIRT_MCP_BRIDGE_TOKEN');
  }

  const body: unknown = await response.json().catch(() => undefined);
  const detail = ErrorSchema.safeParse(body);
  if (detail.success) {
    throw new Error(`${detail.data.error.code}: ${detail.data.error.message}`);
  }
  throw new Error(`Bridge returned HTTP ${response.status}`);
}
