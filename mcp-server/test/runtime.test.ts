import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';
import { closeApplication, closeRuntime, waitForListening } from '../dist/runtime.js';

test('runtime drains HTTP before closing MCP and SQLite', async () => {
  const events: string[] = [];
  let finishHttpClose: ((error?: Error) => void) | undefined;
  const closing = closeRuntime(
    {
      close(callback) {
        events.push('http-start');
        finishHttpClose = callback;
      },
    },
    {
      async close() {
        events.push('mcp');
      },
    },
    {
      close() {
        events.push('database');
      },
    },
  );

  await Promise.resolve();
  assert.deepEqual(events, ['http-start']);
  assert.ok(finishHttpClose !== undefined);
  finishHttpClose();
  await closing;
  assert.deepEqual(events, ['http-start', 'mcp', 'database']);
});

test('runtime preserves close callback errors and still runs every cleanup', async () => {
  const httpError = new Error('http close failed');
  const mcpError = new Error('mcp close failed');
  const databaseError = new Error('database close failed');
  const events: string[] = [];

  await assert.rejects(
    closeRuntime(
      {
        close(callback) {
          events.push('http');
          callback(httpError);
        },
      },
      {
        async close() {
          events.push('mcp');
          throw mcpError;
        },
      },
      {
        close() {
          events.push('database');
          throw databaseError;
        },
      },
    ),
    (error) =>
      error instanceof AggregateError &&
      error.errors[0] === httpError &&
      error.errors[1] === mcpError &&
      error.errors[2] === databaseError,
  );
  assert.deepEqual(events, ['http', 'mcp', 'database']);

  await assert.rejects(
    closeApplication(
      { close: async () => {} },
      {
        close() {
          throw databaseError;
        },
      },
    ),
    (error) => error === databaseError,
  );
});

test('listening wait resolves on listen and rejects deterministic startup errors', async () => {
  const listening = new EventEmitter();
  const ready = waitForListening(listening);
  listening.emit('listening');
  await ready;

  const failed = new EventEmitter();
  const failure = new Error('address already in use');
  const waiting = waitForListening(failed);
  failed.emit('error', failure);
  await assert.rejects(waiting, (error) => error === failure);
});
