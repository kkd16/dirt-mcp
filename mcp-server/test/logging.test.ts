import assert from 'node:assert/strict';
import test from 'node:test';
import { createLogger, safeErrorFields } from '../dist/logging.js';

interface LogRecord extends Record<string, unknown> {
  readonly component: string;
  readonly event: string;
  readonly level: string;
  readonly message: string;
  readonly pid: number;
  readonly service: string;
  readonly timestamp: string;
}

function parseRecord(line: string): LogRecord {
  return JSON.parse(line) as LogRecord;
}

test('writes one structured JSON line with inherited and sanitized context', () => {
  const lines: string[] = [];
  const logger = createLogger('runtime', (line) => lines.push(line));

  logger
    .child({
      component: 'tool',
      call_id: 'call-1',
      authorization: 'Bearer secret',
      api_key: 'secret',
      bearer_value: 'secret',
    })
    .info('tool.completed', 'Tool call completed.\nStill one record.', {
      outcome: 'ok',
      duration_ms: Number.POSITIVE_INFINITY,
      token: 'secret',
      level: 'error',
    });

  assert.equal(lines.length, 1);
  assert.equal(lines[0]?.endsWith('\n'), true);
  assert.equal(lines[0]?.slice(0, -1).includes('\n'), false);
  const record = parseRecord(lines[0]!);
  assert.equal(Number.isNaN(Date.parse(record.timestamp)), false);
  assert.deepEqual(record, {
    timestamp: record.timestamp,
    level: 'info',
    service: 'dirt-mcp-web',
    component: 'tool',
    event: 'tool.completed',
    message: 'Tool call completed.\nStill one record.',
    pid: process.pid,
    call_id: 'call-1',
    authorization: '[redacted]',
    api_key: '[redacted]',
    bearer_value: '[redacted]',
    outcome: 'ok',
    duration_ms: null,
    token: '[redacted]',
  });
});

test('emits canonical levels and does not expose unexpected error messages', () => {
  const lines: string[] = [];
  const logger = createLogger('test', (line) => lines.push(line));
  logger.info('test.info', 'info');
  logger.warning('test.warning', 'warning');
  logger.error('test.error', 'error');

  assert.deepEqual(
    lines.map((line) => parseRecord(line).level),
    ['info', 'warning', 'error'],
  );

  const fields = safeErrorFields(new Error('sensitive implementation detail'));
  assert.equal(fields.error_type, 'Error');
  assert.equal(JSON.stringify(fields).includes('sensitive implementation detail'), false);
  assert.match(fields.stack_locations as string, /logging\.test/);
});

test('does not trust mutable error names or multiline messages as stack locations', () => {
  const error = new Error('first line\nsensitive-value:12:34');
  error.name = 'sensitive custom name';

  const fields = safeErrorFields(error);

  assert.equal(fields.error_type, 'Error');
  assert.equal(JSON.stringify(fields).includes('sensitive'), false);
  assert.match(fields.stack_locations as string, /logging\.test/);
});

test('handles an error whose stack metadata cannot be read', () => {
  const error = new Error('hidden');
  Object.defineProperty(error, 'stack', {
    get() {
      throw new Error('sensitive stack getter');
    },
  });

  assert.deepEqual(safeErrorFields(error), { error_type: 'Error' });
});

test('bounds free-form fields including the truncation marker', () => {
  const lines: string[] = [];
  const logger = createLogger('test', (line) => lines.push(line));

  logger.info('test.bounded', 'm'.repeat(3_000), { detail: 'd'.repeat(3_000) });

  const record = parseRecord(lines[0]!);
  assert.equal(record.message.length, 2_048);
  assert.equal((record.detail as string).length, 2_048);
  assert.equal(record.message.endsWith('...[truncated]'), true);
  assert.equal((record.detail as string).endsWith('...[truncated]'), true);
});
