import { McpServer } from '@modelcontextprotocol/server';
import packageMetadata from '../package.json' with { type: 'json' };
import type { BridgeClient } from './bridge/client.ts';
import type { DirtLogger } from './logging.ts';
import type { McpToolConfiguration } from './tools/configuration.ts';
import { registerEditingTools } from './tools/editing.ts';
import { registerInspectionTools } from './tools/inspection.ts';
import { registerStatusTools } from './tools/status.ts';

function serverInstructions(configuration: McpToolConfiguration): string {
  if (!Object.values(configuration).some(Boolean)) {
    return 'No Dirt MCP tools are enabled for this server.';
  }

  const hasInspection =
    configuration.count_region_block_states || configuration.get_region_blocks || configuration.scan_orthographic_view;
  const hasMutation = configuration.replace_region_blocks || configuration.fill_region || configuration.set_blocks;
  const hasRegion =
    configuration.count_region_block_states ||
    configuration.get_region_blocks ||
    configuration.replace_region_blocks ||
    configuration.fill_region;
  const hasDetailedInspection = configuration.get_region_blocks || configuration.scan_orthographic_view;
  const hasWorldTool = hasInspection || hasMutation || configuration.get_edit_history || configuration.undo_edit;
  const instructions: string[] = [];

  if (hasWorldTool) instructions.push('World tools operate on live Paper worlds.');
  if (hasInspection) instructions.push('Inspections require already-loaded chunks.');
  if (hasMutation) instructions.push('New edits require already-loaded chunks.');
  if (configuration.undo_edit) instructions.push('Undo can reload existing chunks without generating terrain.');

  if (hasInspection || hasMutation) {
    instructions.push('Minecraft axes use X east/west, Y up/down, and Z south/north.');
  }
  if (hasRegion) {
    instructions.push('Region corners are inclusive absolute block positions and normalized automatically.');
  }

  if (configuration.get_server_status && (hasInspection || hasMutation)) {
    instructions.push(
      'Call get_server_status before large world operations and keep requests within its active limits.',
    );
  }

  if (hasDetailedInspection) {
    instructions.push('Inspection result limits fail the call instead of truncating data.');
  }

  let errorGuidance =
    'Treat structuredContent as the canonical result; text content is only a summary. Dirt-mapped failures set isError=true, put callId at the structuredContent root, and put code and message in structuredContent.error. Correctable failures also include strict code-specific details; structuredContent.error may also include editId.';
  if (configuration.get_edit_history) {
    errorGuidance +=
      ' Reconcile records returned by get_edit_history using editId or callId; an absent record means no undoable edit remains.';
  }
  instructions.push(errorGuidance);

  if (hasMutation) {
    instructions.push(
      'Mutation tools can apply immediately; pass dryRun=true when a preview is needed, and retain the edit ID returned by every committed result.',
    );
  }

  if (configuration.get_edit_history && configuration.undo_edit) {
    instructions.push(
      'Use get_edit_history to inspect retained undoable edits newest first, then pass the newest edit ID to undo_edit so an intervening edit cannot be undone accidentally.',
    );
  } else if (configuration.get_edit_history) {
    instructions.push('Use get_edit_history to inspect retained undoable edits newest first.');
  } else if (configuration.undo_edit) {
    instructions.push(
      'Pass the newest retained edit ID from a committed result to undo_edit so an intervening edit cannot be undone accidentally.',
    );
  }

  return instructions.join(' ');
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
  registerEditingTools(server, bridge, toolConfiguration, logger);
  return server;
}
