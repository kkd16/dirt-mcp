#!/usr/bin/env node

import { watchFile, unwatchFile } from 'node:fs';
import { McpServer, type RegisteredTool } from '@modelcontextprotocol/server';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import type { BridgeConfig } from './tools.js';

const DEFAULT_BRIDGE_URL = 'http://127.0.0.1:8765';
const TOOL_MODULE_URL = new URL('./tools.js', import.meta.url);
const SERVER_INSTRUCTIONS = [
  'Dirt operates on live, already-loaded Paper worlds and chunks.',
  'Coordinates are absolute Minecraft block coordinates (X east/west, Y up/down, Z south/north); region corners are inclusive and normalized automatically.',
  'Call get_server_status before large inspections or edits and keep request size, scan volume, result count, region volume, and changed blocks within its active limits.',
  'Use count_region_block_states for totals, get_region_blocks for exact filtered positions or runs, and scan_orthographic_view for first-visible-block sightlines.',
  'Prefer filters or runs for exact retrieval and grid format for larger orthographic views so structured results stay compact.',
  'Treat structuredContent as the canonical result; text content is only a summary, and failed calls set isError=true with structuredContent.error.code and .message.',
  'Inspection result limits fail the call instead of truncating data.',
  'replace_region_blocks, fill_region, and set_blocks can mutate immediately; pass dryRun=true when a preview is needed.',
  'undo_last_dirt_edit only undoes the newest successful Dirt edit in that world, from bounded in-memory per-world history.',
  'run_minecraft_commands dispatches ordered operator-level commands immediately through a non-player Paper sender; command effects are outside Dirt edit limits and undo history.',
].join(' ');

interface ToolModule {
  registerTools(server: McpServer, config: BridgeConfig, registrations: RegisteredTool[]): void;
}

function bridgeConfig(): BridgeConfig {
  const token = process.env.DIRT_MCP_BRIDGE_TOKEN;
  if (token === undefined || token.length === 0) {
    throw new Error('DIRT_MCP_BRIDGE_TOKEN is required');
  }
  const baseUrl = process.env.DIRT_MCP_BRIDGE_URL ?? DEFAULT_BRIDGE_URL;
  const url = new URL(baseUrl);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1') {
    throw new Error('DIRT_MCP_BRIDGE_URL must use http://127.0.0.1');
  }
  return { baseUrl, url, token };
}

async function loadToolModule(version: number): Promise<ToolModule> {
  const loaded: unknown = await import(`${TOOL_MODULE_URL.href}?version=${version}`);
  if (typeof loaded !== 'object'
      || loaded === null
      || !('registerTools' in loaded)
      || typeof loaded.registerTools !== 'function') {
    throw new Error('Tool module must export registerTools');
  }
  return loaded as ToolModule;
}

function installTools(
  server: McpServer,
  config: BridgeConfig,
  module: ToolModule,
): RegisteredTool[] {
  const registrations: RegisteredTool[] = [];
  try {
    module.registerTools(server, config, registrations);
    return registrations;
  } catch (error: unknown) {
    for (const tool of registrations) {
      tool.remove();
    }
    throw error;
  }
}

async function createServer(): Promise<McpServer> {
  const config = bridgeConfig();
  const server = new McpServer(
    { name: 'dirt-mcp', version: '0.1.0' },
    { instructions: SERVER_INSTRUCTIONS },
  );
  let activeModule = await loadToolModule(0);
  let activeTools = installTools(server, config, activeModule);

  if (process.env.DIRT_MCP_DEV_RELOAD === '1') {
    let version = 0;
    let reloads = Promise.resolve();
    const watcher = watchFile(TOOL_MODULE_URL, { interval: 250 }, (current, previous) => {
      if (current.mtimeMs === previous.mtimeMs) {
        return;
      }
      reloads = reloads.then(async () => {
        try {
          const replacement = await loadToolModule(++version);
          for (const tool of activeTools) {
            tool.remove();
          }
          try {
            activeTools = installTools(server, config, replacement);
            activeModule = replacement;
          } catch (error: unknown) {
            activeTools = installTools(server, config, activeModule);
            throw error;
          }
          console.error('Reloaded Dirt MCP tools');
        } catch (error: unknown) {
          console.error('Could not reload Dirt MCP tools:', error);
        }
      });
    });
    watcher.unref();
    server.server.onclose = () => unwatchFile(TOOL_MODULE_URL);
  }

  return server;
}

void serveStdio(createServer);
console.error('Dirt MCP server listening over stdio');
