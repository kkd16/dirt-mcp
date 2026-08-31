import assert from 'node:assert/strict';
import test from 'node:test';
import { CLIENT_INFO_META_KEY } from '@modelcontextprotocol/server';
import { ToolFailure, ToolFailureResultSchema, toolFailureLogLevel } from '../dist/bridge/errors.js';
import type { DirtLogger, LogFields } from '../dist/logging.js';
import { executeToolCall, successResult } from '../dist/tools/execution.js';

interface Entry {
  readonly level: 'info' | 'warning' | 'error';
  readonly event: string;
  readonly message: string;
  readonly fields: LogFields | undefined;
  readonly context: LogFields;
}

function recordingLogger(entries: Entry[], loggerContext: LogFields = {}): DirtLogger {
  const record = (level: Entry['level'], event: string, message: string, fields?: LogFields): void => {
    entries.push({ level, event, message, fields, context: loggerContext });
  };
  return {
    child(additional) {
      return recordingLogger(entries, { ...loggerContext, ...additional });
    },
    info(event, message, fields) {
      record('info', event, message, fields);
    },
    warning(event, message, fields) {
      record('warning', event, message, fields);
    },
    error(event, message, fields) {
      record('error', event, message, fields);
    },
  };
}

function context(
  envelope?: Record<string, unknown>,
  authentication?: { readonly clientId: string; readonly userId: string },
): Parameters<typeof executeToolCall>[1]['context'] {
  return {
    mcpReq: envelope === undefined ? {} : { envelope },
    ...(authentication === undefined
      ? {}
      : {
          http: {
            authInfo: {
              clientId: authentication.clientId,
              extra: { userId: authentication.userId },
            },
          },
        }),
  };
}

test('successResult keeps structured content canonical and text concise', () => {
  assert.deepEqual(successResult({ value: 42 }, 'Done.'), {
    content: [{ type: 'text', text: 'Done.' }],
    structuredContent: { value: 42 },
  });
});

test('executeToolCall returns successes and records bounded call metadata', async () => {
  const entries: Entry[] = [];
  const result = await executeToolCall(
    recordingLogger(entries),
    {
      operation: 'get_blocks',
      world: 'world',
      context: context(
        { [CLIENT_INFO_META_KEY]: { name: 'test-client', version: '1.2.3' } },
        { clientId: 'https://client.example/client.json?secret=hidden', userId: 'user-one' },
      ),
      failureContext: 'Could not get blocks',
    },
    async (callId) => {
      assert.match(callId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u);
      return successResult({ value: 'ok' }, 'Done.');
    },
  );
  assert.deepEqual(result.structuredContent, { value: 'ok' });
  assert.equal(entries.length, 1);
  assert.equal(entries[0]?.level, 'info');
  assert.equal(entries[0]?.event, 'tool.completed');
  assert.equal(entries[0]?.context.client, 'test-client/1.2.3');
  assert.equal(entries[0]?.context.user_id, 'user-one');
  assert.equal(
    entries[0]?.context.client_id_fingerprint,
    '4bc931e7b27f79e68a407ebb5401a4e3c96bea72e5906be22e9f5ee600500acf',
  );
  assert.doesNotMatch(JSON.stringify(entries[0]?.context), /secret=hidden/u);
  assert.equal(entries[0]?.context.world, 'world');
  assert.equal(entries[0]?.fields?.success, true);
  assert.equal(typeof entries[0]?.fields?.duration_ms, 'number');
});

test('recognized failures become sanitized structured MCP error results', async () => {
  const entries: Entry[] = [];
  const failure = new ToolFailure({
    code: 'world_not_found',
    message: 'World is not loaded.',
    details: { world: 'missing' },
  });
  const result = await executeToolCall(
    recordingLogger(entries),
    {
      operation: 'get_blocks',
      context: context(),
      failureContext: 'Could not get blocks',
    },
    async () => {
      throw failure;
    },
  );
  assert.equal(result.isError, true);
  assert.equal(result.content[0]?.type, 'text');
  assert.match(result.content[0]?.type === 'text' ? result.content[0].text : '', /World is not loaded/u);
  assert.equal(ToolFailureResultSchema.safeParse(result.structuredContent).success, true);
  const structuredContent = result.structuredContent as { readonly error: { readonly code: string } };
  assert.equal(structuredContent.error.code, 'world_not_found');
  assert.equal(entries[0]?.level, 'info');
  assert.equal(entries[0]?.fields?.error_code, 'world_not_found');
});

test('unexpected failures never expose exception messages and are logged separately', async () => {
  const entries: Entry[] = [];
  const result = await executeToolCall(
    recordingLogger(entries),
    {
      operation: 'ping_server',
      context: context({}),
      failureContext: 'Ping failed',
    },
    async () => {
      throw new Error('secret implementation detail');
    },
  );
  assert.equal(result.isError, true);
  const text = result.content[0]?.type === 'text' ? result.content[0].text : '';
  assert.doesNotMatch(text, /secret implementation detail/u);
  const structuredContent = result.structuredContent as { readonly error: { readonly code: string } };
  assert.equal(structuredContent.error.code, 'dirt_internal_error');
  assert.deepEqual(
    entries.map((entry) => [entry.level, entry.event]),
    [
      ['error', 'tool.unexpected_failure'],
      ['error', 'tool.completed'],
    ],
  );
});

test('semantic isError results are logged as warnings without rewriting content', async () => {
  const entries: Entry[] = [];
  const expected = { ...successResult({ outcome: 'partial' }, 'Partially completed.'), isError: true };
  const result = await executeToolCall(
    recordingLogger(entries),
    { operation: 'undo_edits', context: context(), failureContext: 'Undo failed' },
    async () => expected,
  );
  assert.deepEqual(result, expected);
  assert.equal(entries[0]?.level, 'warning');
  assert.equal(entries[0]?.fields?.success, false);
});

test('failure log levels distinguish operational, contract, and retained-edit failures', () => {
  assert.equal(
    toolFailureLogLevel(
      new ToolFailure({ code: 'bridge_unavailable', message: 'Unavailable', details: { reason: 'timeout' } }),
    ),
    'warning',
  );
  assert.equal(
    toolFailureLogLevel(new ToolFailure({ code: 'bridge_invalid_response', message: 'Invalid response' })),
    'error',
  );
  assert.equal(
    toolFailureLogLevel(
      new ToolFailure({ code: 'invalid_request', message: 'Invalid', details: { reason: 'missing', field: 'world' } }),
    ),
    'info',
  );
  assert.equal(
    toolFailureLogLevel(
      new ToolFailure({
        code: 'internal_error',
        message: 'Mutation uncertain',
        editId: '22222222-2222-4222-8222-222222222222',
      }),
    ),
    'warning',
  );
  assert.throws(() => new ToolFailure({ code: 'invented', message: 'Nope' } as never));
});
