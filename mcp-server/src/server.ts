import { McpServer } from '@modelcontextprotocol/server';
import packageMetadata from '../package.json' with { type: 'json' };
import { BridgeClient } from './bridge/client.ts';
import type { BridgeConfig } from './config.ts';
import { registerToolCatalog } from './tools/catalog.ts';

const SERVER_INSTRUCTIONS = [
  'Dirt operates on live, already-loaded Paper worlds and chunks.',
  'Coordinates are absolute Minecraft block coordinates (X east/west, Y up/down, Z south/north); region corners are inclusive and normalized automatically.',
  'Call get_server_status before large inspections or edits and keep request size, scan volume, result count, region volume, and changed blocks within its active limits.',
  'Use count_region_block_states for totals, get_region_blocks for exact filtered positions or runs, and scan_orthographic_view for depth-selected non-air sightlines.',
  'Prefer filters or runs for exact retrieval and grid format for larger orthographic views so structured results stay compact.',
  'Treat structuredContent as the canonical result; text content is only a summary, and failed calls set isError=true with structuredContent.error.code, .message, and .callId plus .editId when a mutation may remain or an undoable edit is retained. Use callId to reconcile ambiguous edit failures against get_edit_history.',
  'Inspection result limits fail the call instead of truncating data.',
  'replace_region_blocks, fill_region, and set_blocks can mutate immediately; pass dryRun=true when a preview is needed, and retain the edit ID returned by every committed result.',
  'Use get_edit_history to inspect retained undoable edits newest first, then pass the newest edit ID to undo_edit so an intervening edit cannot be undone accidentally.',
].join(' ');

export function createDirtServer(config: BridgeConfig): McpServer {
  const server = new McpServer(
    { name: 'dirt-mcp', version: packageMetadata.version },
    {
      instructions: SERVER_INSTRUCTIONS,
      capabilities: { tools: { listChanged: false } },
    },
  );
  registerToolCatalog(server, new BridgeClient(config));
  return server;
}
