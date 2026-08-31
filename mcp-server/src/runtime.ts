import { once } from 'node:events';
import type { EventEmitter } from 'node:events';
import type { DirtMcpHandler } from './mcp-http.ts';

export interface RuntimeHttpServer {
  close(callback: (error?: Error) => void): unknown;
}

interface RuntimeDatabase {
  close(): unknown;
}

export async function waitForListening(server: EventEmitter): Promise<void> {
  await once(server, 'listening');
}

export async function closeRuntime(
  httpServer: RuntimeHttpServer,
  mcp: Pick<DirtMcpHandler, 'close'>,
  database: RuntimeDatabase,
): Promise<void> {
  const errors: unknown[] = [];
  try {
    await new Promise<void>((resolve, reject) => {
      httpServer.close((error) => (error === undefined ? resolve() : reject(error)));
    });
  } catch (error: unknown) {
    errors.push(error);
  }
  await collectMcpCloseErrors(mcp, errors);
  collectDatabaseCloseErrors(database, errors);
  throwCollectedErrors(errors);
}

export async function closeApplication(mcp: Pick<DirtMcpHandler, 'close'>, database: RuntimeDatabase): Promise<void> {
  const errors: unknown[] = [];
  await collectMcpCloseErrors(mcp, errors);
  collectDatabaseCloseErrors(database, errors);
  throwCollectedErrors(errors);
}

async function collectMcpCloseErrors(mcp: Pick<DirtMcpHandler, 'close'>, errors: unknown[]): Promise<void> {
  try {
    await mcp.close();
  } catch (error: unknown) {
    errors.push(error);
  }
}

function collectDatabaseCloseErrors(database: RuntimeDatabase, errors: unknown[]): void {
  try {
    database.close();
  } catch (error: unknown) {
    errors.push(error);
  }
}

function throwCollectedErrors(errors: readonly unknown[]): void {
  if (errors.length === 1) throw errors[0];
  if (errors.length > 1) throw new AggregateError(errors, 'Multiple errors occurred while stopping Dirt.');
}
