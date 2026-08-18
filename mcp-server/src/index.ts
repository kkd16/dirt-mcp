#!/usr/bin/env node

import { McpServer } from '@modelcontextprotocol/server';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod/v4';

const DEFAULT_BRIDGE_URL = 'http://127.0.0.1:8765';

const HealthSchema = z.object({
  status: z.literal('ok'),
  service: z.literal('dirt-mcp-paper'),
  version: z.string(),
  minecraftVersion: z.string(),
  capabilities: z.object({
    worldEditing: z.literal(false),
  }).strict(),
}).strict();

function createServer(): McpServer {
  const bridgeToken = process.env.DIRT_MCP_BRIDGE_TOKEN;
  if (bridgeToken === undefined || bridgeToken.length === 0) {
    throw new Error('DIRT_MCP_BRIDGE_TOKEN is required');
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
      const baseUrl = process.env.DIRT_MCP_BRIDGE_URL ?? DEFAULT_BRIDGE_URL;

      try {
        const healthUrl = new URL('/v1/health', baseUrl);
        if (healthUrl.protocol !== 'http:' || healthUrl.hostname !== '127.0.0.1') {
          throw new Error('DIRT_MCP_BRIDGE_URL must use http://127.0.0.1');
        }

        const response = await fetch(healthUrl, {
          headers: {
            Accept: 'application/json',
            Authorization: `Bearer ${bridgeToken}`,
          },
          redirect: 'error',
          signal: AbortSignal.timeout(3_000),
        });

        if (!response.ok) {
          if (response.status === 401) {
            throw new Error('Bridge rejected DIRT_MCP_BRIDGE_TOKEN');
          }
          throw new Error(`Bridge returned HTTP ${response.status}`);
        }

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

  return server;
}

void serveStdio(createServer);
console.error('Dirt MCP server listening over stdio');
