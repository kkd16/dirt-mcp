import { McpServer } from '@modelcontextprotocol/server';
import packageMetadata from '../package.json' with { type: 'json' };
import type { BridgeClient } from './bridge/client.ts';
import type { DirtLogger } from './logging.ts';
import type { McpToolConfiguration } from './tools/configuration.ts';
import { registerEditingTools } from './tools/editing.ts';
import { registerInspectionTools } from './tools/inspection.ts';
import { registerStatusTools } from './tools/status.ts';

function serverInstructions(configuration: McpToolConfiguration): string {
  const instructions = [
    'Dirt operates on live Paper worlds. Inspections and new edits require already-loaded chunks; undo can reload existing chunks without generating terrain.',
    'Coordinates are absolute Minecraft block coordinates (X east/west, Y up/down, Z south/north); region corners are inclusive and normalized automatically.',
  ];

  if (configuration.get_server_status) {
    instructions.push(
      'Call get_server_status before large inspections or edits and keep request size, scan volume, result count, region volume, and changed blocks within its active limits.',
    );
  }

  if (
    configuration.count_region_block_states ||
    configuration.get_region_blocks ||
    configuration.scan_orthographic_view
  ) {
    instructions.push('Inspection result limits fail the call instead of truncating data.');
  }

  let errorGuidance =
    'Treat structuredContent as the canonical result; text content is only a summary. Dirt-mapped failures set isError=true and put code, message, and callId in structuredContent.error, with an optional editId for reconciliation.';
  if (configuration.get_edit_history) {
    errorGuidance +=
      ' Reconcile records returned by get_edit_history using editId or callId; an absent record means no undoable edit remains.';
  }
  instructions.push(errorGuidance);

  if (configuration.replace_region_blocks || configuration.fill_region || configuration.set_blocks) {
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
