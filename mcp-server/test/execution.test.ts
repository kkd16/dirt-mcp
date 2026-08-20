import assert from 'node:assert/strict';
import test from 'node:test';
import type { ServerContext } from '@modelcontextprotocol/server';
import { ToolFailure } from '../dist/bridge/errors.js';
import { executeToolCall, successResult } from '../dist/tools/execution.js';

function context(id: number): ServerContext {
  return { mcpReq: { id } } as unknown as ServerContext;
}

async function captureStderr<T>(operation: () => Promise<T>): Promise<{ result: T; stderr: string }> {
  const originalWrite = process.stderr.write;
  let stderr = '';
  process.stderr.write = ((chunk: string | Uint8Array) => {
    stderr += typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8');
    return true;
  }) as typeof process.stderr.write;
  try {
    return { result: await operation(), stderr };
  } finally {
    process.stderr.write = originalWrite;
  }
}

test('maps expected tool failures and emits an unknown-client audit', async () => {
  const captured = await captureStderr(() =>
    executeToolCall({ tool: 'test_tool', context: context(17), failureContext: 'Could not test' }, async () => {
      throw new ToolFailure('world_busy', 'World is busy');
    }),
  );

  assert.deepEqual(captured.result.structuredContent, {
    error: { code: 'world_busy', message: 'World is busy' },
  });
  assert.equal(captured.result.isError, true);
  assert.match(captured.stderr, /client="unknown" outcome=error/);
  assert.doesNotMatch(captured.stderr, /unexpected_failure/);
});

test('sanitizes unexpected failures without logging their messages', async () => {
  const captured = await captureStderr(() =>
    executeToolCall(
      { tool: 'test_tool', world: 'world', context: context(18), failureContext: 'Could not test' },
      async () => {
        throw new Error('sensitive implementation detail');
      },
    ),
  );

  assert.deepEqual(captured.result.structuredContent, {
    error: {
      code: 'dirt_internal_error',
      message: 'Dirt MCP encountered an unexpected internal error.',
    },
  });
  assert.match(captured.stderr, /unexpected_failure tool=test_tool/);
  assert.doesNotMatch(captured.stderr, /sensitive implementation detail/);
  assert.match(captured.stderr, /world="world" outcome=error/);
});

test('returns structured success results through the shared executor', async () => {
  const captured = await captureStderr(() =>
    executeToolCall({ tool: 'test_tool', context: context(19), failureContext: 'Could not test' }, async () =>
      successResult({ value: 'ok' }, 'Worked'),
    ),
  );
  assert.deepEqual(captured.result, {
    content: [{ type: 'text', text: 'Worked' }],
    structuredContent: { value: 'ok' },
  });
  assert.match(captured.stderr, /outcome=ok/);
});
