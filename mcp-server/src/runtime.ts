import { once } from 'node:events';
import type { EventEmitter } from 'node:events';
import type { DirtMcpHandler } from './mcp-http.ts';

interface RuntimeHttpServer {
  close(callback: (error?: Error) => void): unknown;
}

interface RuntimeDatabase {
  close(): void;
}

export async function waitForListening(server: EventEmitter): Promise<void> {
  await once(server, 'listening');
}

export async function closeRuntime(
  httpServer: RuntimeHttpServer,
  mcp: Pick<DirtMcpHandler, 'close'>,
  database: RuntimeDatabase,
): Promise<void> {
  await closeInOrder(
    () =>
      new Promise<void>((resolve, reject) => {
        httpServer.close((error) => (error === undefined ? resolve() : reject(error)));
      }),
    () => mcp.close(),
    () => database.close(),
  );
}

export async function closeApplication(mcp: Pick<DirtMcpHandler, 'close'>, database: RuntimeDatabase): Promise<void> {
  await closeInOrder(
    () => mcp.close(),
    () => database.close(),
  );
}

async function closeInOrder(...close: ReadonlyArray<() => void | Promise<void>>): Promise<void> {
  const errors: unknown[] = [];
  await close.reduce(
    (closing, closeResource) =>
      closing.then(closeResource).catch((error: unknown) => {
        errors.push(error);
      }),
    Promise.resolve(),
  );
  if (errors.length === 1) throw errors[0];
  if (errors.length > 1) throw new AggregateError(errors, 'Multiple errors occurred while stopping Dirt.');
}
