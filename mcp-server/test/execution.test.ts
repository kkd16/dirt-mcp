import assert from 'node:assert/strict';
import test from 'node:test';
import type { ServerContext } from '@modelcontextprotocol/server';
import { ToolFailure } from '../dist/bridge/errors.js';
import { executeToolCall, successResult } from '../dist/tools/execution.js';

const EDIT_ID = '11111111-1111-4111-8111-111111111111';
const UUID_V4_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

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

  const error = (captured.result.structuredContent as { error: Record<string, unknown> }).error;
  assert.match(error.callId as string, UUID_V4_PATTERN);
  assert.deepEqual(captured.result.structuredContent, {
    error: { code: 'world_busy', message: 'World is busy', callId: error.callId },
  });
  assert.equal(captured.result.isError, true);
  assert.match(captured.stderr, /client="unknown" outcome=error/);
  assert.doesNotMatch(captured.stderr, /unexpected_failure/);
});

test('exposes a retained edit ID on expected failures only when present', async () => {
  const captured = await captureStderr(() =>
    executeToolCall({ tool: 'test_tool', context: context(18), failureContext: 'Could not test' }, async () => {
      throw new ToolFailure('history_capacity_exceeded', 'Recovery is required', EDIT_ID);
    }),
  );

  const error = (captured.result.structuredContent as { error: Record<string, unknown> }).error;
  assert.match(error.callId as string, UUID_V4_PATTERN);
  assert.deepEqual(captured.result.structuredContent, {
    error: {
      code: 'history_capacity_exceeded',
      message: 'Recovery is required',
      callId: error.callId,
      editId: EDIT_ID,
    },
  });
  assert.equal(captured.result.isError, true);
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

  const error = (captured.result.structuredContent as { error: Record<string, unknown> }).error;
  assert.match(error.callId as string, UUID_V4_PATTERN);
  assert.deepEqual(captured.result.structuredContent, {
    error: {
      code: 'dirt_internal_error',
      message: 'Dirt MCP encountered an unexpected internal error.',
      callId: error.callId,
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
