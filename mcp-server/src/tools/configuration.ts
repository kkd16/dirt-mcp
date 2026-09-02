import type { BridgeCapabilities, BridgeOperationId } from '../bridge/contract.ts';
import {
  MCP_TOOL_NAMES,
  MCP_TOOL_OPERATIONS,
  toolConfigurationFromEntries,
  type McpToolConfiguration,
} from './catalog.ts';

export { MCP_TOOL_OPERATIONS, type McpToolConfiguration, type McpToolName } from './catalog.ts';

export function toolConfigurationFromCapabilities(capabilities: BridgeCapabilities): McpToolConfiguration {
  const operations = new Set<BridgeOperationId>(capabilities.operations);
  return toolConfigurationFromEntries(MCP_TOOL_NAMES.map((name) => [name, operations.has(MCP_TOOL_OPERATIONS[name])]));
}
