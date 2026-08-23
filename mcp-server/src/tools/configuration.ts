import * as z from 'zod/v4';

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

const enabledSchema = z.boolean().describe('Whether this MCP tool is enabled.');
const toolConfigurationShape = {
  ping_server: enabledSchema,
  get_server_status: enabledSchema,
  count_region_block_states: enabledSchema,
  get_blocks: enabledSchema,
  scan_orthographic_view: enabledSchema,
  get_player_context: enabledSchema,
  get_perspective_view: enabledSchema,
  replace_region_blocks: enabledSchema,
  set_blocks: enabledSchema,
  get_edit_history: enabledSchema,
  undo_edits: enabledSchema,
  run_minecraft_commands: enabledSchema,
} satisfies Record<McpToolName, z.ZodBoolean>;

export const McpToolConfigurationSchema = z
  .object(toolConfigurationShape)
  .strict()
  .describe('Effective MCP tool availability snapshotted from the Paper plugin configuration.');

export type McpToolConfiguration = z.infer<typeof McpToolConfigurationSchema>;
