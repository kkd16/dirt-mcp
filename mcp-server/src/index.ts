#!/usr/bin/env node

import { McpServer } from '@modelcontextprotocol/server';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod/v4';

const DEFAULT_BRIDGE_URL = 'http://127.0.0.1:8765';
const INT32_MIN = -2_147_483_648;
const INT32_MAX = 2_147_483_647;

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

function createServer(): McpServer {
  const bridgeToken = process.env.DIRT_MCP_BRIDGE_TOKEN;
  if (bridgeToken === undefined || bridgeToken.length === 0) {
    throw new Error('DIRT_MCP_BRIDGE_TOKEN is required');
  }
  const baseUrl = process.env.DIRT_MCP_BRIDGE_URL ?? DEFAULT_BRIDGE_URL;
  const bridgeUrl = new URL(baseUrl);
  if (bridgeUrl.protocol !== 'http:' || bridgeUrl.hostname !== '127.0.0.1') {
    throw new Error('DIRT_MCP_BRIDGE_URL must use http://127.0.0.1');
  }

  const server = new McpServer({
    name: 'dirt-mcp',
    version: '0.1.0',
  });

  server.registerTool(
    'dirt_status',
    {
      title: 'Dirt MCP status',
      description: 'Check whether the local Dirt MCP Paper bridge is available.',
      inputSchema: z.object({}),
      outputSchema: HealthSchema,
    },
    async () => {
      try {
        const response = await bridgeRequest('/v1/health');
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
            text: `Dirt MCP Paper bridge is unavailable at ${baseUrl}: ${message}`,
          }],
          isError: true,
        };
      }
    },
  );

  server.registerTool(
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
  );

  server.registerTool(
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
  );

  async function bridgeRequest(
    path: string,
    init?: RequestInit,
    timeoutMilliseconds = 3_000,
  ): Promise<Response> {
    const headers = new Headers(init?.headers);
    headers.set('Accept', 'application/json');
    headers.set('Authorization', `Bearer ${bridgeToken}`);

    const response = await fetch(new URL(path, bridgeUrl), {
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

  return server;
}

void serveStdio(createServer);
console.error('Dirt MCP server listening over stdio');
