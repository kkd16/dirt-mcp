import { serve } from '@hono/node-server';
import { AccessRepository } from './access/repository.ts';
import { createAuth } from './auth.ts';
import { BridgeClient } from './bridge/client.ts';
import { readRuntimeConfig, RuntimeConfigurationError } from './config.ts';
import { createLogger, safeErrorFields } from './logging.ts';
import { createDirtMcpHandler } from './mcp-http.ts';
import { openDatabase } from './storage.ts';
import { createWebApp } from './web/app.ts';

async function main(): Promise<void> {
  const logger = createLogger('runtime');
  try {
    const config = readRuntimeConfig(process.env);
    const database = openDatabase(config.databasePath);
    const repository = new AccessRepository(database);
    repository.assertSchema();
    const bridge = new BridgeClient(config.bridge);
    const auth = createAuth(config, database, repository);
    const mcp = createDirtMcpHandler(auth, bridge, repository, config, logger);
    const app = createWebApp({ auth, config, logger, mcp, repository });
    const httpServer = serve({ fetch: app.fetch, hostname: '127.0.0.1', port: config.port });
    let closing = false;
    const shutdown = async (signal: NodeJS.Signals): Promise<void> => {
      if (closing) return;
      closing = true;
      logger.info('runtime.stopping', 'Dirt web service is stopping.', { signal });
      const stopped = new Promise<void>((resolve, reject) => {
        httpServer.close((error) => (error === undefined ? resolve() : reject(error)));
      });
      await mcp.close();
      await stopped;
      database.close();
      logger.info('runtime.stopped', 'Dirt web service stopped.');
    };
    process.once('SIGINT', () => void shutdown('SIGINT'));
    process.once('SIGTERM', () => void shutdown('SIGTERM'));
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

await main();
