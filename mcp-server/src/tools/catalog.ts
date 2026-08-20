import type { McpServer } from '@modelcontextprotocol/server';
import type { BridgeClient } from '../bridge/client.ts';
import type { DirtLogger } from '../logging.ts';
import { registerEditingTools } from './editing.ts';
import { registerInspectionTools } from './inspection.ts';
import { registerStatusTools } from './status.ts';
import type { McpToolConfiguration } from './configuration.ts';

export function registerToolCatalog(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  registerStatusTools(server, bridge, toolConfiguration, logger);
  registerInspectionTools(server, bridge, toolConfiguration, logger);
  registerEditingTools(server, bridge, toolConfiguration, logger);
}
