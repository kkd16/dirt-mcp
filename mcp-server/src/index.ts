#!/usr/bin/env node

import { serveStdio } from '@modelcontextprotocol/server/stdio';
import { randomUUID } from 'node:crypto';
import { BridgeClient } from './bridge/client.ts';
import { BRIDGE_ROUTES, BridgeCapabilitiesSchema } from './bridge/contract.ts';
import { ToolFailure, toolFailureLogLevel } from './bridge/errors.ts';
import { BridgeConfigurationError, readBridgeConfig, type BridgeConfig } from './config.ts';
import { createLogger, safeErrorFields, type LogFields } from './logging.ts';
import { createDirtServer } from './server.ts';
import { toolConfigurationFromCapabilities, type McpToolConfiguration } from './tools/configuration.ts';

async function main(): Promise<void> {
  const logger = createLogger('runtime');
  let config: BridgeConfig;
  try {
    config = readBridgeConfig(process.env);
  } catch (error: unknown) {
    logger.error('runtime.start_failed', 'Dirt MCP could not start.', failureLogFields(error));
    process.exitCode = 1;
    return;
  }

  const bridge = new BridgeClient(config);
  const callId = randomUUID();
  const started = performance.now();
  let toolConfiguration: McpToolConfiguration;
  try {
    const capabilities = await bridge.request(BRIDGE_ROUTES.capabilities, callId, BridgeCapabilitiesSchema);
    toolConfiguration = toolConfigurationFromCapabilities(capabilities);
    logger
      .child({ component: 'catalog', operation: 'get_capabilities', call_id: callId })
      .info('catalog.loaded', 'Loaded the bridge capability snapshot.', {
        enabled_tool_count: Object.values(toolConfiguration).filter(Boolean).length,
        operations: capabilities.operations.join(','),
        duration_ms: elapsedMilliseconds(started),
      });
  } catch (error: unknown) {
    const fields = { ...failureLogFields(error), duration_ms: elapsedMilliseconds(started) };
    const level = error instanceof ToolFailure ? toolFailureLogLevel(error) : 'error';
    const startupLogger = logger.child({ component: 'catalog', operation: 'get_capabilities', call_id: callId });
    if (level === 'error') startupLogger.error('runtime.start_failed', 'Could not load bridge capabilities.', fields);
    else startupLogger.warning('runtime.start_failed', 'Could not load bridge capabilities.', fields);
    process.exitCode = 1;
    return;
  }

  serveStdio(() => createDirtServer(bridge, toolConfiguration, logger), {
    onerror(error) {
      logger
        .child({ component: 'stdio' })
        .error('stdio.error', 'The MCP stdio transport reported an error.', failureLogFields(error));
    },
  });
  logger.info('runtime.started', 'Dirt MCP is listening over stdio.', {
    transport: 'stdio',
    bridge_origin: config.origin,
  });
}

function failureLogFields(error: unknown): LogFields {
  return {
    ...safeErrorFields(error),
    ...(error instanceof ToolFailure
      ? {
          error_code: error.code,
          ...(error.editId === undefined ? {} : { edit_id: error.editId }),
        }
      : {}),
    ...(error instanceof BridgeConfigurationError ? { error_code: error.code } : {}),
  };
}

function elapsedMilliseconds(started: number): number {
  return Math.max(0, Math.round(performance.now() - started));
}

void main();
