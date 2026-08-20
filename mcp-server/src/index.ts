#!/usr/bin/env node

import { serveStdio } from '@modelcontextprotocol/server/stdio';
import { readBridgeConfig } from './config.ts';
import { createDirtServer } from './server.ts';

const config = readBridgeConfig(process.env);

void serveStdio(() => createDirtServer(config), {
  onerror(error) {
    process.stderr.write(`Dirt MCP stdio error_type=${JSON.stringify(error.name)}\n`);
  },
});
console.error('Dirt MCP server listening over stdio');
