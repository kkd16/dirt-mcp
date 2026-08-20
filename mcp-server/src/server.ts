import { McpServer } from '@modelcontextprotocol/server';
import packageMetadata from '../package.json' with { type: 'json' };
import { BridgeClient } from './bridge/client.ts';
import type { BridgeConfig } from './config.ts';
import { registerToolCatalog } from './tools/catalog.ts';
import { MCP_TOOL_NAMES, type McpToolConfiguration, type McpToolName } from './tools/configuration.ts';

function enabledNames(configuration: McpToolConfiguration, names: readonly McpToolName[]): McpToolName[] {
  return names.filter((name) => configuration[name]);
}

function serverInstructions(configuration: McpToolConfiguration): string {
  const enabled = enabledNames(configuration, MCP_TOOL_NAMES);
  const instructions = [
    enabled.length === 0
      ? 'This Dirt server snapshot has no enabled MCP tools.'
      : `Enabled tools for this Dirt server snapshot: ${enabled.join(', ')}.`,
    'Dirt operates on live, already-loaded Paper worlds and chunks.',
    'Coordinates are absolute Minecraft block coordinates (X east/west, Y up/down, Z south/north); region corners are inclusive and normalized automatically.',
  ];

  if (configuration.get_server_status) {
    instructions.push(
      'Call get_server_status before large inspections or edits and keep request size, scan volume, result count, region volume, and changed blocks within its active limits.',
    );
  }

  const inspections = enabledNames(configuration, [
    'count_region_block_states',
    'get_region_blocks',
    'scan_orthographic_view',
  ]);
  if (inspections.length > 0) instructions.push(`Available inspection tools: ${inspections.join(', ')}.`);
  if (configuration.get_region_blocks) {
    instructions.push('Prefer filters or runs for exact retrieval so structured results stay compact.');
  }
  if (configuration.scan_orthographic_view) {
    instructions.push('Prefer grid format for larger orthographic views so structured results stay compact.');
  }
  if (inspections.length > 0) {
    instructions.push('Inspection result limits fail the call instead of truncating data.');
  }

  let errorGuidance =
    'Treat structuredContent as the canonical result; text content is only a summary. Failures mapped by a Dirt tool handler set isError=true with structuredContent.error.code, .message, and .callId and may add .editId for edit transaction reconciliation. Invalid tool names or arguments are rejected before Dirt generates a call ID. MCP SDK output-validation failures occur outside Dirt error mapping and do not carry structuredContent.error.callId.';
  if (configuration.get_edit_history) {
    errorGuidance +=
      ' Reconcile records returned by get_edit_history using editId or callId; an absent record means no undoable edit remains.';
  }
  instructions.push(errorGuidance);

  const mutations = enabledNames(configuration, ['replace_region_blocks', 'fill_region', 'set_blocks']);
  if (mutations.length > 0) {
    instructions.push(
      `${mutations.join(', ')} can mutate immediately; pass dryRun=true when a preview is needed, and retain the edit ID returned by every committed result.`,
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

export function createDirtServer(config: BridgeConfig, toolConfiguration: McpToolConfiguration): McpServer {
  const server = new McpServer(
    { name: 'dirt-mcp', version: packageMetadata.version },
    {
      instructions: serverInstructions(toolConfiguration),
      capabilities: { tools: { listChanged: false } },
    },
  );
  registerToolCatalog(server, new BridgeClient(config), toolConfiguration);
  return server;
}
