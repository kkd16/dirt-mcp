import assert from 'node:assert/strict';
import test from 'node:test';
import {
  CommandResultSchema,
  RunMinecraftCommandsInputSchema,
  RunMinecraftCommandsOutputSchema,
  runMinecraftCommandsBridgeOutputSchema,
} from '../dist/tools/commands.js';

const DISPATCHED = {
  command: 'say hello',
  outcome: 'dispatched',
  feedback: [],
  message: null,
  rawMessage: null,
} as const;
const NOT_FOUND = {
  command: 'missing',
  outcome: 'not_found',
  feedback: ['Unknown command'],
  message: 'Command was not found.',
  rawMessage: null,
} as const;

test('command input validation preserves text for Paper-owned normalization', () => {
  const command = '  /say hello  ';
  const parsed = RunMinecraftCommandsInputSchema.parse({ commands: [command] });
  assert.equal(parsed.commands[0], command);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: [] }).success, false);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: ['   '] }).success, false);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: ['/'] }).success, true);
});

test('command outputs require normalized commands and a valid attempted prefix', () => {
  const failed = {
    command: 'fail',
    outcome: 'dispatch_failed',
    feedback: [],
    message: 'Dispatch failed.',
    rawMessage: 'Command exception.',
  } as const;
  assert.equal(CommandResultSchema.safeParse(DISPATCHED).success, true);
  assert.equal(CommandResultSchema.safeParse(NOT_FOUND).success, true);
  assert.equal(CommandResultSchema.safeParse(failed).success, true);
  assert.equal(CommandResultSchema.safeParse({ ...failed, rawMessage: null }).success, false);
  assert.equal(CommandResultSchema.safeParse({ ...DISPATCHED, command: ' say hello' }).success, false);
  assert.equal(CommandResultSchema.safeParse({ ...DISPATCHED, command: 'say\nhello' }).success, false);

  const output = {
    sender: { name: 'Dirt MCP', isOperator: true, isPlayer: false },
    feedbackTruncated: false,
    results: [NOT_FOUND, DISPATCHED],
  };
  assert.equal(RunMinecraftCommandsOutputSchema.safeParse(output).success, false);
  assert.equal(
    RunMinecraftCommandsOutputSchema.safeParse({ ...output, results: [DISPATCHED, NOT_FOUND] }).success,
    true,
  );
  assert.equal(RunMinecraftCommandsOutputSchema.safeParse({ ...output, results: [] }).success, false);
});

test('command bridge output accounts for the requested command count', () => {
  const secondDispatched = { ...DISPATCHED, command: 'say again' } as const;
  const requestedNotFound = { ...NOT_FOUND, command: 'say hello', feedback: [] } as const;
  const output = {
    sender: { name: 'Dirt MCP', isOperator: true, isPlayer: false },
    feedbackTruncated: false,
    results: [DISPATCHED, secondDispatched],
  } as const;
  const schema = runMinecraftCommandsBridgeOutputSchema({ commands: ['say hello', 'say again'] });

  assert.equal(schema.safeParse(output).success, true);
  assert.equal(schema.safeParse({ ...output, results: [DISPATCHED] }).success, false);
  assert.equal(schema.safeParse({ ...output, results: [requestedNotFound] }).success, true);
  assert.equal(schema.safeParse({ ...output, results: [DISPATCHED, DISPATCHED] }).success, false);
  assert.equal(
    runMinecraftCommandsBridgeOutputSchema({ commands: [' \u1680/\u2000say hello\u3000'] }).safeParse({
      ...output,
      results: [DISPATCHED],
    }).success,
    true,
  );
  assert.equal(
    schema.safeParse({ ...output, results: [DISPATCHED, secondDispatched, secondDispatched] }).success,
    false,
  );
});
