#!/usr/bin/env node

import { serveStdio } from '@modelcontextprotocol/server/stdio';
import { randomUUID } from 'node:crypto';
import { BridgeClient } from './bridge/client.ts';
import { BRIDGE_ROUTES } from './bridge/contract.ts';
import { ToolFailure, toolFailureLogLevel } from './bridge/errors.ts';
import { BridgeConfigurationError, readBridgeConfig, type BridgeConfig } from './config.ts';
import { createLogger, safeErrorFields, type DirtLogger, type LogFields } from './logging.ts';
import { createDirtServer } from './server.ts';
import type { McpToolConfiguration } from './tools/configuration.ts';
import { ServerStatusSchema } from './tools/status.ts';

function createToolConfigurationReader(
  config: BridgeConfig,
  logger: DirtLogger,
  reportedErrors: WeakSet<Error>,
): () => Promise<McpToolConfiguration> {
  let snapshot: Promise<McpToolConfiguration> | undefined;

  return () => {
    if (snapshot !== undefined) return snapshot;

    const callId = randomUUID();
    const started = performance.now();
    const catalogLogger = logger.child({
      component: 'catalog',
      operation: 'get_server_status',
      call_id: callId,
    });
    const pending = new BridgeClient(config)
      .request(BRIDGE_ROUTES.serverStatus, callId, ServerStatusSchema)
      .then((status) => {
        const enabledTools = Object.entries(status.tools).flatMap(([name, enabled]) => (enabled ? [name] : []));
        catalogLogger.info('catalog.loaded', 'Loaded the MCP tool catalog snapshot.', {
          enabled_tool_count: enabledTools.length,
          enabled_tools: enabledTools.join(','),
          duration_ms: elapsedMilliseconds(started),
        });
        return status.tools;
      })
      .catch((error: unknown) => {
        if (error instanceof Error) reportedErrors.add(error);
        const fields = { ...failureLogFields(error), duration_ms: elapsedMilliseconds(started) };
        const level = error instanceof ToolFailure ? toolFailureLogLevel(error) : 'error';
        if (level === 'error') {
          catalogLogger.error('catalog.load_failed', 'Could not load the MCP tool catalog snapshot.', fields);
        } else {
          catalogLogger.warning('catalog.load_failed', 'Could not load the MCP tool catalog snapshot.', fields);
        }
        throw error;
      });
    snapshot = pending;
    void pending.catch(() => {
      if (snapshot === pending) snapshot = undefined;
    });
    return pending;
  };
}

function main(): void {
  const logger = createLogger('runtime');
  let config: BridgeConfig;
  try {
    config = readBridgeConfig(process.env);
  } catch (error: unknown) {
    logger.error('runtime.start_failed', 'Dirt MCP could not start.', failureLogFields(error));
    process.exitCode = 1;
    return;
  }

  const reportedErrors = new WeakSet<Error>();
  const readToolConfiguration = createToolConfigurationReader(config, logger, reportedErrors);
  serveStdio(async () => createDirtServer(config, await readToolConfiguration(), logger), {
    onerror(error) {
      if (reportedErrors.has(error)) return;
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

main();
