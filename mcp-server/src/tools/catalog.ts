import { McpServer } from '@modelcontextprotocol/server';
import { BridgeClient } from '../bridge/client.ts';
import { registerEditingTools } from './editing.ts';
import { registerInspectionTools } from './inspection.ts';
import { registerStatusTools } from './status.ts';

type ToolRegistrar = (server: McpServer, bridge: BridgeClient) => void;

const TOOL_REGISTRARS: readonly ToolRegistrar[] = [registerStatusTools, registerInspectionTools, registerEditingTools];

export function registerToolCatalog(server: McpServer, bridge: BridgeClient): void {
  for (const registerTools of TOOL_REGISTRARS) {
    registerTools(server, bridge);
  }
}
