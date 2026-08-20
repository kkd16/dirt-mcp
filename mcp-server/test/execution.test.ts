import assert from 'node:assert/strict';
import test from 'node:test';
import type { ServerContext } from '@modelcontextprotocol/server';
import { ToolFailure } from '../dist/bridge/errors.js';
import { createLogger, type DirtLogger } from '../dist/logging.js';
import { executeToolCall, successResult } from '../dist/tools/execution.js';

const EDIT_ID = '11111111-1111-4111-8111-111111111111';
const UUID_V4_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function context(id: number): ServerContext {
  return { mcpReq: { id } } as unknown as ServerContext;
}

async function captureLogs<T>(
  operation: (logger: DirtLogger) => Promise<T>,
): Promise<{ result: T; records: readonly Record<string, unknown>[] }> {
  const records: Record<string, unknown>[] = [];
  const logger = createLogger('test', (line) => records.push(JSON.parse(line) as Record<string, unknown>));
  return { result: await operation(logger), records };
}

test('maps expected tool failures and emits an unknown-client audit', async () => {
  const captured = await captureLogs((logger) =>
    executeToolCall(
      logger,
      { operation: 'test_tool', context: context(17), failureContext: 'Could not test' },
      async () => {
        throw new ToolFailure('world_busy', 'World is busy');
      },
    ),
  );

  const error = (captured.result.structuredContent as { error: Record<string, unknown> }).error;
  assert.match(error.callId as string, UUID_V4_PATTERN);
  assert.deepEqual(captured.result.structuredContent, {
    error: { code: 'world_busy', message: 'World is busy', callId: error.callId },
  });
  assert.equal(captured.result.isError, true);
  assert.equal(captured.records.length, 1);
  assert.deepEqual(captured.records[0], {
    timestamp: captured.records[0]?.timestamp,
    level: 'info',
    service: 'dirt-mcp-stdio',
    component: 'tool',
    event: 'tool.completed',
    message: 'Tool call completed.',
    pid: process.pid,
    operation: 'test_tool',
    call_id: error.callId,
    request_id: 17,
    client: 'unknown',
    success: false,
    duration_ms: captured.records[0]?.duration_ms,
    error_code: 'world_busy',
  });
});

test('exposes a retained edit ID on expected failures only when present', async () => {
  const captured = await captureLogs((logger) =>
    executeToolCall(
      logger,
      { operation: 'test_tool', context: context(18), failureContext: 'Could not test' },
      async () => {
        throw new ToolFailure('history_capacity_exceeded', 'Recovery is required', EDIT_ID);
      },
    ),
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
  assert.equal(captured.records[0]?.error_code, 'history_capacity_exceeded');
  assert.equal(captured.records[0]?.edit_id, EDIT_ID);
  assert.equal(captured.records[0]?.level, 'warning');
});

test('elevates actionable bridge failures', async () => {
  const unavailable = await captureLogs((logger) =>
    executeToolCall(
      logger,
      { operation: 'test_tool', context: context(19), failureContext: 'Could not test' },
      async () => {
        throw new ToolFailure('bridge_unavailable', 'Bridge is unavailable');
      },
    ),
  );

  assert.equal(unavailable.records[0]?.level, 'warning');
  assert.equal(unavailable.records[0]?.error_code, 'bridge_unavailable');
});

test('reports unexpected bridge and protocol failures at error level', async () => {
  for (const code of [
    'bridge_http_error',
    'bridge_invalid_response',
    'internal_error',
    'method_not_allowed',
    'not_found',
  ] as const) {
    // Failure-severity cases are deliberately exercised serially for clear assertions.
    // oxlint-disable-next-line eslint/no-await-in-loop
    const captured = await captureLogs((logger) =>
      executeToolCall(
        logger,
        { operation: 'test_tool', context: context(20), failureContext: 'Could not test' },
        async () => {
          throw new ToolFailure(code, 'Unexpected bridge failure');
        },
      ),
    );

    assert.equal(captured.records[0]?.level, 'error');
    assert.equal(captured.records[0]?.error_code, code);
  }
});

test('sanitizes unexpected failures without logging their messages', async () => {
  const captured = await captureLogs((logger) =>
    executeToolCall(
      logger,
      { operation: 'test_tool', world: 'world', context: context(18), failureContext: 'Could not test' },
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
  assert.equal(captured.records.length, 2);
  assert.equal(captured.records[0]?.event, 'tool.unexpected_failure');
  assert.equal(captured.records[0]?.level, 'error');
  assert.equal(captured.records[0]?.error_type, 'Error');
  assert.equal(captured.records[0]?.world, 'world');
  assert.equal(captured.records[1]?.event, 'tool.completed');
  assert.equal(captured.records[1]?.level, 'error');
  assert.equal(captured.records[1]?.success, false);
  assert.equal(captured.records[1]?.error_code, 'dirt_internal_error');
  assert.equal(JSON.stringify(captured.records).includes('sensitive implementation detail'), false);
});

test('returns structured success results through the shared executor', async () => {
  const captured = await captureLogs((logger) =>
    executeToolCall(
      logger,
      { operation: 'test_tool', context: context(19), failureContext: 'Could not test' },
      async () => successResult({ value: 'ok' }, 'Worked'),
    ),
  );
  assert.deepEqual(captured.result, {
    content: [{ type: 'text', text: 'Worked' }],
    structuredContent: { value: 'ok' },
  });
  assert.equal(captured.records.length, 1);
  assert.equal(captured.records[0]?.event, 'tool.completed');
  assert.equal(captured.records[0]?.success, true);
});

test('adds only allowlisted edit and bounded result metadata to successful audits', async () => {
  const captured = await captureLogs((logger) =>
    executeToolCall(
      logger,
      { operation: 'test_tool', context: context(20), failureContext: 'Could not test' },
      async () =>
        successResult(
          {
            outcome: 'committed',
            changedBlockCount: 12,
            edit: { editId: EDIT_ID, changedBlockCount: 12, privateValue: 'not logged' },
            edits: [{}, {}],
            requestBody: 'not logged',
          },
          'Worked',
        ),
    ),
  );

  assert.equal(captured.records.length, 1);
  assert.equal(captured.records[0]?.edit_id, EDIT_ID);
  assert.equal(captured.records[0]?.outcome, 'committed');
  assert.equal(captured.records[0]?.changed_block_count, 12);
  assert.equal(captured.records[0]?.result_count, 2);
  assert.equal(JSON.stringify(captured.records).includes('not logged'), false);
});

test('identifies successful undo metadata without exposing the full result', async () => {
  const captured = await captureLogs((logger) =>
    executeToolCall(
      logger,
      { operation: 'undo_edit', context: context(21), failureContext: 'Could not undo' },
      async () =>
        successResult(
          {
            edit: { editId: EDIT_ID, changedBlockCount: 7 },
            undoCallId: '22222222-2222-4222-8222-222222222222',
          },
          'Undid edit',
        ),
    ),
  );

  assert.equal(captured.records[0]?.edit_id, EDIT_ID);
  assert.equal(captured.records[0]?.outcome, 'undone');
  assert.equal(captured.records[0]?.changed_block_count, 7);
});
