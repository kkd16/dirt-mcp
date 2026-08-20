import assert from 'node:assert/strict';
import test from 'node:test';
import type { ServerContext } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { BRIDGE_ERROR_CODES } from '../dist/bridge/contract.js';
import {
  ToolFailure,
  ToolFailureDataSchema,
  ToolFailureResultSchema,
  toolOutputSchema,
} from '../dist/bridge/errors.js';
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

test('keeps MCP-local failure details strict and code-specific', () => {
  for (const failure of [
    { code: 'bridge_unavailable', message: 'Unavailable', details: { reason: 'timeout' } },
    { code: 'bridge_unauthorized', message: 'Unauthorized', details: { reason: 'authentication_failed' } },
    { code: 'bridge_http_error', message: 'Bad gateway', details: { status: 502 } },
    { code: 'bridge_invalid_response', message: 'Invalid response' },
    { code: 'dirt_internal_error', message: 'Internal error' },
  ]) {
    assert.equal(ToolFailureDataSchema.safeParse(failure).success, true, failure.code);
  }

  for (const failure of [
    { code: 'bridge_unavailable', message: 'Unavailable' },
    { code: 'bridge_unauthorized', message: 'Unauthorized', details: { reason: 'timeout' } },
    { code: 'bridge_http_error', message: 'Invalid status', details: { status: 600 } },
    { code: 'bridge_invalid_response', message: 'Invalid response', details: {} },
    { code: 'dirt_internal_error', message: 'Internal error', details: { implementation: 'secret' } },
  ]) {
    assert.equal(ToolFailureDataSchema.safeParse(failure).success, false, failure.code);
  }
});

test('advertises every failure code with a compact envelope', () => {
  const schema = toolOutputSchema(z.object({ value: z.string() }).strict());
  const callId = '22222222-2222-4222-8222-222222222222';

  assert.equal(schema.safeParse({ value: 'ok' }).success, true);
  for (const code of BRIDGE_ERROR_CODES) {
    assert.equal(
      schema.safeParse({
        callId,
        error: {
          code,
          message: 'Failure',
          ...(code === 'internal_error' ? {} : { details: {} }),
        },
      }).success,
      true,
      code,
    );
  }
  for (const error of [
    { code: 'bridge_unavailable', message: 'Unavailable', details: { reason: 'timeout' } },
    { code: 'bridge_unauthorized', message: 'Unauthorized', details: { reason: 'authentication_failed' } },
    { code: 'bridge_http_error', message: 'Bad gateway', details: { status: 502 } },
    { code: 'bridge_invalid_response', message: 'Invalid response' },
    { code: 'dirt_internal_error', message: 'Internal error' },
  ]) {
    assert.equal(schema.safeParse({ callId, error }).success, true, error.code);
  }

  for (const failure of [
    { callId, error: { code: 'world_busy', message: 'Busy' } },
    { callId, error: { code: 'internal_error', message: 'Internal', details: {} } },
    { callId, error: { code: 'bridge_unavailable', message: 'Unavailable', details: {} } },
    { callId, error: { code: 'unknown_error', message: 'Unknown', details: {} } },
    { error: { code: 'world_busy', message: 'Busy', details: {} } },
    { error: { code: 'world_busy', message: 'Busy', details: {}, callId } },
  ]) {
    assert.equal(schema.safeParse(failure).success, false);
  }
});

test('fully validates the canonical failure result at runtime', () => {
  const callId = '22222222-2222-4222-8222-222222222222';
  const failure = {
    callId,
    error: {
      code: 'world_busy',
      message: 'Busy',
      details: { reason: 'operation_in_progress', world: 'world' },
    },
  };

  assert.equal(ToolFailureResultSchema.safeParse(failure).success, true);
  assert.equal(
    ToolFailureResultSchema.safeParse({
      ...failure,
      error: { ...failure.error, details: { reason: 'operation_in_progress' } },
    }).success,
    false,
  );
  assert.equal(ToolFailureResultSchema.safeParse({ ...failure, error: { ...failure.error, callId } }).success, false);
  assert.doesNotThrow(() => z.toJSONSchema(ToolFailureResultSchema));
});

test('maps expected tool failures and emits an unknown-client audit', async () => {
  const captured = await captureLogs((logger) =>
    executeToolCall(
      logger,
      { operation: 'test_tool', context: context(17), failureContext: 'Could not test' },
      async () => {
        throw new ToolFailure({
          code: 'world_busy',
          message: 'World is busy',
          details: { reason: 'operation_in_progress', world: 'world' },
        });
      },
    ),
  );

  const structuredContent = captured.result.structuredContent as {
    callId: string;
    error: Record<string, unknown>;
  };
  assert.match(structuredContent.callId, UUID_V4_PATTERN);
  assert.deepEqual(captured.result.structuredContent, {
    callId: structuredContent.callId,
    error: {
      code: 'world_busy',
      message: 'World is busy',
      details: { reason: 'operation_in_progress', world: 'world' },
    },
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
    call_id: structuredContent.callId,
    request_id: 17,
    client: 'unknown',
    success: false,
    duration_ms: captured.records[0]?.duration_ms,
    error_code: 'world_busy',
  });
  assert.equal(JSON.stringify(captured.records).includes('operation_in_progress'), false);
  assert.equal(JSON.stringify(captured.records).includes('"world":"world"'), false);
});

test('exposes a retained edit ID on expected failures only when present', async () => {
  const captured = await captureLogs((logger) =>
    executeToolCall(
      logger,
      { operation: 'test_tool', context: context(18), failureContext: 'Could not test' },
      async () => {
        throw new ToolFailure({
          code: 'history_capacity_exceeded',
          message: 'Recovery is required',
          details: { reason: 'retained_changed_blocks', maximum: 100_000 },
          editId: EDIT_ID,
        });
      },
    ),
  );

  const structuredContent = captured.result.structuredContent as {
    callId: string;
    error: Record<string, unknown>;
  };
  assert.match(structuredContent.callId, UUID_V4_PATTERN);
  assert.deepEqual(captured.result.structuredContent, {
    callId: structuredContent.callId,
    error: {
      code: 'history_capacity_exceeded',
      message: 'Recovery is required',
      details: { reason: 'retained_changed_blocks', maximum: 100_000 },
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
        throw new ToolFailure({
          code: 'bridge_unavailable',
          message: 'Bridge is unavailable',
          details: { reason: 'request_failed' },
        });
      },
    ),
  );

  assert.equal(unavailable.records[0]?.level, 'warning');
  assert.equal(unavailable.records[0]?.error_code, 'bridge_unavailable');
});

test('reports unexpected bridge and protocol failures at error level', async () => {
  for (const data of [
    { code: 'bridge_http_error', message: 'Unexpected bridge failure', details: { status: 502 } },
    { code: 'bridge_invalid_response', message: 'Unexpected bridge failure' },
    { code: 'internal_error', message: 'Unexpected bridge failure' },
    {
      code: 'method_not_allowed',
      message: 'Unexpected bridge failure',
      details: { allowedMethod: 'POST' },
    },
    {
      code: 'not_found',
      message: 'Unexpected bridge failure',
      details: { reason: 'route_not_found' },
    },
  ] as const) {
    // Failure-severity cases are deliberately exercised serially for clear assertions.
    // oxlint-disable-next-line eslint/no-await-in-loop
    const captured = await captureLogs((logger) =>
      executeToolCall(
        logger,
        { operation: 'test_tool', context: context(20), failureContext: 'Could not test' },
        async () => {
          throw new ToolFailure(data);
        },
      ),
    );

    assert.equal(captured.records[0]?.level, 'error');
    assert.equal(captured.records[0]?.error_code, data.code);
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

  const structuredContent = captured.result.structuredContent as {
    callId: string;
    error: Record<string, unknown>;
  };
  assert.match(structuredContent.callId, UUID_V4_PATTERN);
  assert.deepEqual(captured.result.structuredContent, {
    callId: structuredContent.callId,
    error: {
      code: 'dirt_internal_error',
      message: 'Dirt MCP encountered an unexpected internal error.',
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
