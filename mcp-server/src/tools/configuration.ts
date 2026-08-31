import type { BridgeCapabilities, BridgeOperationId } from '../bridge/contract.ts';

export const MCP_TOOL_OPERATIONS = {
  ping_server: 'pingServer',
  get_server_status: 'getServerStatus',
  count_region_block_states: 'countRegionBlockStates',
  get_blocks: 'getBlocks',
  scan_orthographic_view: 'scanOrthographicView',
  get_player_context: 'getPlayerContext',
  get_perspective_view: 'getPerspectiveView',
  replace_region_blocks: 'replaceRegionBlocks',
  set_blocks: 'setBlocks',
  get_edit_history: 'getEditHistory',
  undo_edits: 'undoEdits',
  run_minecraft_commands: 'runMinecraftCommands',
} as const satisfies Record<string, BridgeOperationId>;

type McpToolName = keyof typeof MCP_TOOL_OPERATIONS;
export type McpToolConfiguration = Readonly<Record<McpToolName, boolean>>;

export function toolConfigurationFromCapabilities(capabilities: BridgeCapabilities): McpToolConfiguration {
  const operations = new Set<BridgeOperationId>(capabilities.operations);
  return {
    ping_server: operations.has(MCP_TOOL_OPERATIONS.ping_server),
    get_server_status: operations.has(MCP_TOOL_OPERATIONS.get_server_status),
    count_region_block_states: operations.has(MCP_TOOL_OPERATIONS.count_region_block_states),
    get_blocks: operations.has(MCP_TOOL_OPERATIONS.get_blocks),
    scan_orthographic_view: operations.has(MCP_TOOL_OPERATIONS.scan_orthographic_view),
    get_player_context: operations.has(MCP_TOOL_OPERATIONS.get_player_context),
    get_perspective_view: operations.has(MCP_TOOL_OPERATIONS.get_perspective_view),
    replace_region_blocks: operations.has(MCP_TOOL_OPERATIONS.replace_region_blocks),
    set_blocks: operations.has(MCP_TOOL_OPERATIONS.set_blocks),
    get_edit_history: operations.has(MCP_TOOL_OPERATIONS.get_edit_history),
    undo_edits: operations.has(MCP_TOOL_OPERATIONS.undo_edits),
    run_minecraft_commands: operations.has(MCP_TOOL_OPERATIONS.run_minecraft_commands),
  };
}
