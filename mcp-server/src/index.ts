#!/usr/bin/env node

import { serveStdio } from '@modelcontextprotocol/server/stdio';
import { randomUUID } from 'node:crypto';
import { BridgeClient } from './bridge/client.ts';
import { BRIDGE_ROUTES } from './bridge/contract.ts';
import { ToolFailure } from './bridge/errors.ts';
import { readBridgeConfig } from './config.ts';
import { createDirtServer } from './server.ts';
import type { McpToolConfiguration } from './tools/configuration.ts';
import { ServerStatusSchema } from './tools/status.ts';

const config = readBridgeConfig(process.env);
let toolConfigurationSnapshot: Promise<McpToolConfiguration> | undefined;

function readToolConfigurationSnapshot(): Promise<McpToolConfiguration> {
  if (toolConfigurationSnapshot !== undefined) return toolConfigurationSnapshot;

  const pending = new BridgeClient(config)
    .request(BRIDGE_ROUTES.serverStatus, randomUUID(), ServerStatusSchema)
    .then((status) => status.tools);
  toolConfigurationSnapshot = pending;
  void pending.catch(() => {
    if (toolConfigurationSnapshot === pending) toolConfigurationSnapshot = undefined;
  });
  return pending;
}

void serveStdio(async () => createDirtServer(config, await readToolConfigurationSnapshot()), {
  onerror(error) {
    const code = error instanceof ToolFailure ? ` code=${JSON.stringify(error.code)}` : '';
    process.stderr.write(`Dirt MCP stdio error_type=${JSON.stringify(error.name)}${code}\n`);
  },
});
console.error('Dirt MCP server listening over stdio');
