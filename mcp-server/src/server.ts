import { McpServer } from '@modelcontextprotocol/server';
import packageMetadata from '../package.json' with { type: 'json' };
import type { BridgeClient } from './bridge/client.ts';
import type { DirtLogger } from './logging.ts';
import type { McpToolConfiguration } from './tools/configuration.ts';
import { registerCommandTools } from './tools/commands.ts';
import { registerEditingTools } from './tools/editing.ts';
import { registerInspectionTools } from './tools/inspection.ts';
import { registerPerspectiveTools } from './tools/perspective.ts';
import { registerPlayerTools } from './tools/player.ts';
import { registerStatusTools } from './tools/status.ts';

function serverInstructions(configuration: McpToolConfiguration): string {
  if (!Object.values(configuration).some(Boolean)) {
    return 'No Dirt MCP tools are enabled for this server.';
  }
  return [
    'Dirt operates on live Paper worlds; inspections and new edits require already-loaded chunks.',
    'Use inspect, preview, edit, and verify as the normal workflow, then use retained edit IDs for undo when needed.',
    'Treat structuredContent as canonical and text content as a summary.',
    'A cancelled, timed-out, or disconnected state-changing call may have completed; inspect edit history or world state before retrying, and use callId to correlate operator logs.',
  ].join(' ');
}

export function createDirtServer(
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): McpServer {
  const server = new McpServer(
    { name: 'dirt-mcp', version: packageMetadata.version },
    {
      instructions: serverInstructions(toolConfiguration),
      capabilities: { tools: { listChanged: false } },
    },
  );
  registerStatusTools(server, bridge, toolConfiguration, logger);
  registerInspectionTools(server, bridge, toolConfiguration, logger);
  registerPlayerTools(server, bridge, toolConfiguration, logger);
  registerPerspectiveTools(server, bridge, toolConfiguration, logger);
  registerEditingTools(server, bridge, toolConfiguration, logger);
  registerCommandTools(server, bridge, toolConfiguration, logger);
  return server;
}
