import type { BridgeCapabilities, BridgeOperationId } from '../bridge/contract.ts';

export const MCP_TOOL_NAMES = [
  'ping_server',
  'get_server_status',
  'count_region_block_states',
  'get_blocks',
  'scan_orthographic_view',
  'get_player_context',
  'get_perspective_view',
  'replace_region_blocks',
  'set_blocks',
  'get_edit_history',
  'undo_edits',
  'run_minecraft_commands',
] as const;

export type McpToolName = (typeof MCP_TOOL_NAMES)[number];

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
} as const satisfies Record<McpToolName, BridgeOperationId>;

export type McpToolConfiguration = Readonly<Record<McpToolName, boolean>>;

export function toolConfigurationFromCapabilities(capabilities: BridgeCapabilities): McpToolConfiguration {
  const operations = new Set<BridgeOperationId>(capabilities.operations);
  const enabled = (toolName: McpToolName): boolean => operations.has(MCP_TOOL_OPERATIONS[toolName]);
  return {
    ping_server: enabled('ping_server'),
    get_server_status: enabled('get_server_status'),
    count_region_block_states: enabled('count_region_block_states'),
    get_blocks: enabled('get_blocks'),
    scan_orthographic_view: enabled('scan_orthographic_view'),
    get_player_context: enabled('get_player_context'),
    get_perspective_view: enabled('get_perspective_view'),
    replace_region_blocks: enabled('replace_region_blocks'),
    set_blocks: enabled('set_blocks'),
    get_edit_history: enabled('get_edit_history'),
    undo_edits: enabled('undo_edits'),
    run_minecraft_commands: enabled('run_minecraft_commands'),
  };
}
