import { serve } from '@hono/node-server';
import type Database from 'better-sqlite3';
import { AccessRepository } from './access/repository.ts';
import { createAuth } from './auth.ts';
import { BridgeClient } from './bridge/client.ts';
import { readRuntimeConfig, RuntimeConfigurationError, type RuntimeConfig } from './config.ts';
import { createLogger, safeErrorFields, type DirtLogger } from './logging.ts';
import { createDirtMcpHandler } from './mcp-http.ts';
import { closeApplication, closeRuntime, waitForListening } from './runtime.ts';
import { openDatabase } from './storage.ts';
import { createWebApp } from './web/app.ts';

async function main(): Promise<void> {
  const logger = createLogger('runtime');
  try {
    const config = readRuntimeConfig(process.env);
    const database = openDatabase(config.databasePath);
    let initialized: Awaited<ReturnType<typeof initializeApplication>>;
    try {
      initialized = await initializeApplication(config, database, logger);
    } catch (error: unknown) {
      try {
        database.close();
      } catch (cleanupError: unknown) {
        throw new AggregateError([error, cleanupError], 'Dirt initialization and cleanup both failed.', {
          cause: cleanupError,
        });
      }
      throw error;
    }
    const { app, mcp } = initialized;
    let httpServer: ReturnType<typeof serve>;
    try {
      httpServer = serve({ fetch: app.fetch, hostname: '127.0.0.1', port: config.port });
    } catch (error: unknown) {
      try {
        await closeApplication(mcp, database);
      } catch (cleanupError: unknown) {
        throw new AggregateError([error, cleanupError], 'Dirt startup and cleanup both failed.', {
          cause: cleanupError,
        });
      }
      throw error;
    }
    try {
      await waitForListening(httpServer);
    } catch (error: unknown) {
      try {
        await closeRuntime(httpServer, mcp, database);
      } catch (cleanupError: unknown) {
        throw new AggregateError([error, cleanupError], 'Dirt startup and cleanup both failed.', {
          cause: cleanupError,
        });
      }
      throw error;
    }
    let closing = false;
    const shutdown = async (reason: NodeJS.Signals | 'HTTP_ERROR'): Promise<void> => {
      if (closing) return;
      closing = true;
      logger.info('runtime.stopping', 'Dirt web service is stopping.', { reason });
      try {
        await closeRuntime(httpServer, mcp, database);
        logger.info('runtime.stopped', 'Dirt web service stopped.');
      } catch (error: unknown) {
        logger.error('runtime.stop_failed', 'Dirt web service did not stop cleanly.', safeErrorFields(error));
        process.exitCode = 1;
      }
    };
    process.once('SIGINT', () => void shutdown('SIGINT'));
    process.once('SIGTERM', () => void shutdown('SIGTERM'));
    httpServer.on('error', (error: Error) => {
      logger.error('runtime.http_failed', 'Dirt HTTP server failed.', safeErrorFields(error));
      void shutdown('HTTP_ERROR');
    });
    logger.info('runtime.started', 'Dirt web service is listening.', {
      bind_address: '127.0.0.1',
      port: config.port,
      bridge_origin: config.bridge.origin,
    });
  } catch (error: unknown) {
    logger.error('runtime.start_failed', 'Dirt web service could not start.', {
      ...safeErrorFields(error),
      ...(error instanceof RuntimeConfigurationError ? { error_code: error.code } : {}),
    });
    process.exitCode = 1;
  }
}

async function initializeApplication(config: RuntimeConfig, database: Database.Database, logger: DirtLogger) {
  const repository = new AccessRepository(database);
  repository.assertSchema();
  const bridge = new BridgeClient(config.bridge);
  const auth = createAuth(config, database, repository);
  await auth.$context;
  const mcp = createDirtMcpHandler(auth, bridge, repository, config, logger);
  try {
    const app = createWebApp({ auth, config, logger, mcp, repository });
    return { app, mcp };
  } catch (error: unknown) {
    try {
      await mcp.close();
    } catch (cleanupError: unknown) {
      throw new AggregateError([error, cleanupError], 'Dirt application initialization and cleanup both failed.', {
        cause: cleanupError,
      });
    }
    throw error;
  }
}

await main();
