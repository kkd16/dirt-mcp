import { McpServer } from '@modelcontextprotocol/server';
import { BridgeClient } from '../bridge/client.ts';
import { registerEditingTools } from './editing.ts';
import { registerInspectionTools } from './inspection.ts';
import { registerStatusTools } from './status.ts';
import type { McpToolConfiguration } from './configuration.ts';

export function registerToolCatalog(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
): void {
  registerStatusTools(server, bridge, toolConfiguration);
  registerInspectionTools(server, bridge, toolConfiguration);
  registerEditingTools(server, bridge, toolConfiguration);
}
