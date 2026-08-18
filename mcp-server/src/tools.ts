import { McpServer, type RegisteredTool } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';

const INT32_MIN = -2_147_483_648;
const INT32_MAX = 2_147_483_647;
const MAX_EXACT_RESULTS = 10_000;

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
  includeAir: z.boolean().optional().default(false)
    .describe('Include air-family states; false by default.'),
  maxResults: z.number().int().min(1).max(MAX_EXACT_RESULTS).optional().default(MAX_EXACT_RESULTS)
    .describe('Maximum returned block or run entries; oversized results fail instead of truncating.'),
  mode: z.enum(['blocks', 'runs']).optional().default('blocks')
    .describe('Return sparse blocks or a deterministic exact cover of axis-aligned runs.'),
}).strict();

const ExactInspectionBase = {
  world: z.string().min(1),
  bounds: BoundsSchema,
  volume: z.number().int().min(1).max(32_768),
  matchedBlocks: z.number().int().min(0).max(32_768),
};

const InspectBlocksOutputSchema = z.discriminatedUnion('mode', [
  z.object({
    ...ExactInspectionBase,
    mode: z.literal('blocks'),
    blocks: z.array(z.object({
      position: BlockPositionSchema,
      state: z.string().min(1),
    }).strict()).max(MAX_EXACT_RESULTS),
  }).strict(),
  z.object({
    ...ExactInspectionBase,
    mode: z.literal('runs'),
    runs: z.array(z.object({
      state: z.string().min(1),
      from: BlockPositionSchema,
      to: BlockPositionSchema,
    }).strict()).max(MAX_EXACT_RESULTS),
  }).strict(),
]);

const ReplaceBlocksInputSchema = z.object({
  world: z.string().min(1),
  min: BlockPositionSchema,
  max: BlockPositionSchema,
  source: z.string().min(1),
  destination: z.string().min(1),
  dryRun: z.boolean().optional().default(false),
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
  dryRun: z.boolean().optional().default(false),
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
  version: z.string(),
  minecraftVersion: z.string(),
}).strict();

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
      description: 'Check whether the local Dirt MCP Paper bridge is available.',
      inputSchema: z.object({}),
      outputSchema: HealthSchema,
    },
    async () => {
      try {
        const response = await bridgeRequest(config, '/v1/health');
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
    },
  ));

  registrations.push(register(
    'inspect_region',
    {
      title: 'Inspect a region',
      description: 'Count block states in a bounded region of already-loaded Minecraft chunks.',
      inputSchema: InspectRegionInputSchema,
      outputSchema: InspectRegionOutputSchema,
    },
    async (input) => {
      try {
        const response = await bridgeRequest(
          config,
          '/v1/inspect-region',
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
    },
  ));

  registrations.push(register(
    'inspect_blocks',
    {
      title: 'Inspect exact blocks',
      description: 'Return exact filtered block positions or lossless compressed runs from already-loaded chunks.',
      inputSchema: InspectBlocksInputSchema,
      outputSchema: InspectBlocksOutputSchema,
    },
    async (input) => {
      try {
        const response = await bridgeRequest(
          config,
          '/v1/inspect-blocks',
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
    },
  ));

  registrations.push(register(
    'replace_blocks',
    {
      title: 'Replace blocks',
      description: 'Replace one exact block state in a bounded region, or preview the exact result.',
      inputSchema: ReplaceBlocksInputSchema,
      outputSchema: ReplaceBlocksOutputSchema,
    },
    async (input) => {
      try {
        const response = await bridgeRequest(
          config,
          '/v1/replace-blocks',
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
    },
  ));

  registrations.push(register(
    'fill_region',
    {
      title: 'Fill a region',
      description: 'Set every block in a bounded region to one block state, or preview the exact result.',
      inputSchema: FillRegionInputSchema,
      outputSchema: FillRegionOutputSchema,
    },
    async (input) => {
      try {
        const response = await bridgeRequest(
          config,
          '/v1/fill-region',
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
    },
  ));

  registrations.push(register(
    'undo_last_edit',
    {
      title: 'Undo the last edit',
      description: 'Undo the newest successful Dirt MCP edit in a loaded world.',
      inputSchema: UndoLastEditInputSchema,
      outputSchema: UndoLastEditOutputSchema,
    },
    async (input) => {
      try {
        const response = await bridgeRequest(
          config,
          '/v1/undo-last-edit',
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
    },
  ));
}

async function bridgeRequest(
  config: BridgeConfig,
  path: string,
  init?: RequestInit,
  timeoutMilliseconds = 3_000,
): Promise<Response> {
  const headers = new Headers(init?.headers);
  headers.set('Accept', 'application/json');
  headers.set('Authorization', `Bearer ${config.token}`);

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
