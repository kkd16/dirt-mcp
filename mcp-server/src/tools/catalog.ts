import { McpServer } from '@modelcontextprotocol/server';
import { BridgeClient } from '../bridge/client.ts';
import { registerEditingTools } from './editing.ts';
import { registerInspectionTools } from './inspection.ts';
import { registerStatusTools } from './status.ts';

export function registerToolCatalog(server: McpServer, bridge: BridgeClient): void {
  registerStatusTools(server, bridge);
  registerInspectionTools(server, bridge);
  registerEditingTools(server, bridge);
}
