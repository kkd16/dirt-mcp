import * as z from 'zod';
import { AccessProfileSchema, profileGrants, type AccessProfile } from '../access/profiles.ts';
import { BRIDGE_OPERATION_IDS, type BridgeOperationId } from '../bridge/contract.ts';
import rawCatalog from './catalog.json' with { type: 'json' };

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

export const TOOL_CATEGORIES = ['status', 'inspection', 'editing', 'commands'] as const;

export type McpToolName = (typeof MCP_TOOL_NAMES)[number];
export type ToolCategory = (typeof TOOL_CATEGORIES)[number];

const JsonValueSchema = z.json();
const ToolCatalogEntrySchema = z
  .object({
    name: z.enum(MCP_TOOL_NAMES),
    operationId: z.enum(BRIDGE_OPERATION_IDS),
    category: z.enum(TOOL_CATEGORIES),
    title: z.string().trim().min(1),
    minimumProfile: AccessProfileSchema,
    changesWorld: z.boolean(),
    description: z.string().trim().min(1),
    useWhen: z.string().trim().min(1),
    inputs: z.string().trim().min(1),
    returns: z.string().trim().min(1),
    caution: z.string().trim().min(1),
    exampleInput: JsonValueSchema,
  })
  .strict();
const ToolCatalogSchema = z
  .object({ version: z.literal(1), tools: z.array(ToolCatalogEntrySchema) })
  .strict()
  .superRefine(({ tools }, context) => {
    const names = new Set(tools.map(({ name }) => name));
    const operations = new Set(tools.map(({ operationId }) => operationId));
    if (tools.length !== MCP_TOOL_NAMES.length || MCP_TOOL_NAMES.some((name) => !names.has(name))) {
      context.addIssue({ code: 'custom', message: 'The catalog must contain every MCP tool exactly once.' });
    }
    if (operations.size !== BRIDGE_OPERATION_IDS.length || BRIDGE_OPERATION_IDS.some((id) => !operations.has(id))) {
      context.addIssue({ code: 'custom', message: 'The catalog must contain every bridge operation exactly once.' });
    }
  });

export type ToolCatalogEntry = z.infer<typeof ToolCatalogEntrySchema>;

export const TOOL_CATALOG: readonly ToolCatalogEntry[] = ToolCatalogSchema.parse(rawCatalog).tools;

const TOOL_CATALOG_BY_NAME = new Map<McpToolName, ToolCatalogEntry>(TOOL_CATALOG.map((tool) => [tool.name, tool]));

const McpToolOperationsSchema = z.record(z.enum(MCP_TOOL_NAMES), z.enum(BRIDGE_OPERATION_IDS));
const McpToolConfigurationSchema = z.record(z.enum(MCP_TOOL_NAMES), z.boolean());

export const MCP_TOOL_OPERATIONS: Readonly<Record<McpToolName, BridgeOperationId>> = McpToolOperationsSchema.parse(
  Object.fromEntries(TOOL_CATALOG.map(({ name, operationId }) => [name, operationId])),
);

export type McpToolConfiguration = Readonly<Record<McpToolName, boolean>>;

export function catalogTool(name: McpToolName): ToolCatalogEntry {
  const tool = TOOL_CATALOG_BY_NAME.get(name);
  if (tool === undefined) throw new Error(`Missing Dirt tool catalog entry: ${name}`);
  return tool;
}

export function profileToolConfiguration(profile: AccessProfile): McpToolConfiguration {
  return toolConfigurationFromEntries(
    TOOL_CATALOG.map((tool) => [tool.name, profileGrants(profile, tool.minimumProfile)]),
  );
}

export function intersectToolConfigurations(
  left: McpToolConfiguration,
  right: McpToolConfiguration,
): McpToolConfiguration {
  return toolConfigurationFromEntries(MCP_TOOL_NAMES.map((name) => [name, left[name] && right[name]]));
}

export function toolConfigurationFromEntries(entries: Iterable<readonly [McpToolName, boolean]>): McpToolConfiguration {
  return McpToolConfigurationSchema.parse(Object.fromEntries(entries));
}
