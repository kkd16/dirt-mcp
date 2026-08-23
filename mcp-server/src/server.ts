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

  const hasInspection =
    configuration.count_region_block_states ||
    configuration.get_blocks ||
    configuration.scan_orthographic_view ||
    configuration.get_player_context ||
    configuration.get_perspective_view;
  const hasMutation = configuration.replace_region_blocks || configuration.set_blocks;
  const hasRegion =
    configuration.count_region_block_states || configuration.get_blocks || configuration.replace_region_blocks;
  const hasDetailedInspection =
    configuration.get_blocks || configuration.scan_orthographic_view || configuration.get_perspective_view;
  const hasWorldTool = hasInspection || hasMutation || configuration.get_edit_history || configuration.undo_edits;
  const instructions: string[] = [];

  if (hasWorldTool) instructions.push('World tools operate on live Paper worlds.');
  if (hasInspection) instructions.push('Region and block-view inspections require already-loaded chunks.');
  if (hasMutation) instructions.push('New edits require already-loaded chunks.');
  if (configuration.undo_edits) instructions.push('Undo can reload existing chunks without generating terrain.');

  if (hasInspection || hasMutation) {
    instructions.push('Minecraft axes use X east/west, Y up/down, and Z south/north.');
  }
  if (hasRegion) {
    instructions.push('Region corners are inclusive absolute block positions and normalized automatically.');
  }

  if (configuration.get_server_status && (hasInspection || hasMutation)) {
    instructions.push(
      'Call get_server_status with include.configuration=true before large world operations and keep requests within its active limits.',
    );
  }

  if (configuration.get_server_status && configuration.run_minecraft_commands) {
    instructions.push(
      'Call get_server_status with include.configuration=true before large command batches. Keep batch size and encoded request size within its active limits; captured feedback is truncated at its request-wide limit.',
    );
  }

  if (hasDetailedInspection) {
    instructions.push('Inspection result limits fail the call instead of truncating data.');
  }

  if (configuration.get_player_context) {
    instructions.push(
      'Player context is point-in-time; recapture it before relying on player state that may have changed.',
    );
  }
  if (configuration.get_perspective_view) {
    instructions.push(
      'Perspective views are point-in-time block-collision projections; recapture a player source before a POV-dependent edit if the player may have moved.',
    );
  }

  if (configuration.run_minecraft_commands) {
    instructions.push(
      'run_minecraft_commands attempts commands in order, no more than once each, as an operator-level non-player sender and stops at the first per-command failure; structuredContent.results is the non-empty attempted prefix, and remaining commands were not attempted. Dispatch is synchronous, but arbitrary non-atomic effects may outlive the response and are outside Dirt edit history and undo. A timeout, disconnect, or unexpected internal failure can leave completion ambiguous, so inspect server or world state before deciding whether to retry.',
    );
    instructions.push(
      'For run_minecraft_commands, a final not_found or dispatch_failed outcome is preserved in the attempted-prefix structuredContent.results and sets isError=true without replacing it with structuredContent.error.',
    );
  }

  let errorGuidance =
    'Treat structuredContent as the canonical result; text content is only a summary. Dirt-mapped failures set isError=true, put callId at the structuredContent root, and put code and message in structuredContent.error. Correctable failures also include strict code-specific details; structuredContent.error may also include editId.';
  if (configuration.get_edit_history) {
    errorGuidance +=
      ' Reconcile records returned by get_edit_history using editId or callId; an absent record means no undoable edit remains.';
  }
  if (configuration.undo_edits) {
    errorGuidance +=
      ' A runtime undo_edits failure also puts undoneEdits at the structuredContent root, possibly empty; those records were restored and consumed before the edit identified by structuredContent.error.editId failed.';
  }
  instructions.push(errorGuidance);

  if (hasMutation) {
    instructions.push(
      'Mutation tools can apply immediately. Give each edit a concise label describing one reversible intent, combine related geometry into one set_blocks call, keep unrelated refinements separate, pass dryRun=true when a preview is needed, and retain the edit ID returned by every committed result.',
    );
  }

  if (configuration.get_edit_history && configuration.undo_edits) {
    instructions.push(
      'Use get_edit_history to inspect retained undoable edits newest first, then pass the exact newest-first prefix to undo_edits. Re-read history after partial or ambiguous undo outcomes before deciding what remains to undo.',
    );
  } else if (configuration.get_edit_history) {
    instructions.push('Use get_edit_history to inspect retained undoable edits newest first.');
  } else if (configuration.undo_edits) {
    instructions.push(
      'Pass a newest-first prefix of retained edit IDs from committed results to undo_edits so intervening edits cannot be skipped accidentally.',
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
  registerPlayerTools(server, bridge, toolConfiguration, logger);
  registerPerspectiveTools(server, bridge, toolConfiguration, logger);
  registerEditingTools(server, bridge, toolConfiguration, logger);
  registerCommandTools(server, bridge, toolConfiguration, logger);
  return server;
}
