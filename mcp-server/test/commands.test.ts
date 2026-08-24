import assert from 'node:assert/strict';
import test from 'node:test';
import {
  CommandResultSchema,
  RunMinecraftCommandsInputSchema,
  RunMinecraftCommandsOutputSchema,
  runMinecraftCommandsBridgeOutputSchema,
} from '../dist/tools/commands.js';

test('command input validation preserves text for Paper-owned normalization', () => {
  const command = '  /say hello  ';
  const parsed = RunMinecraftCommandsInputSchema.parse({ commands: [command] });
  assert.equal(parsed.commands[0], command);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: [] }).success, false);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: ['   '] }).success, false);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: ['/'] }).success, true);
});

test('command outputs require normalized commands and a valid attempted prefix', () => {
  const dispatched = {
    command: 'say hello',
    outcome: 'dispatched',
    feedback: [],
    message: null,
    rawMessage: null,
  } as const;
  const notFound = {
    command: 'missing',
    outcome: 'not_found',
    feedback: ['Unknown command'],
    message: 'Command was not found.',
    rawMessage: null,
  } as const;
  const failed = {
    command: 'fail',
    outcome: 'dispatch_failed',
    feedback: [],
    message: 'Dispatch failed.',
    rawMessage: 'Command exception.',
  } as const;
  assert.equal(CommandResultSchema.safeParse(dispatched).success, true);
  assert.equal(CommandResultSchema.safeParse(notFound).success, true);
  assert.equal(CommandResultSchema.safeParse(failed).success, true);
  assert.equal(CommandResultSchema.safeParse({ ...failed, rawMessage: null }).success, false);
  assert.equal(CommandResultSchema.safeParse({ ...dispatched, command: ' say hello' }).success, false);
  assert.equal(CommandResultSchema.safeParse({ ...dispatched, command: 'say\nhello' }).success, false);

  const output = {
    sender: { name: 'Dirt MCP', isOperator: true, isPlayer: false },
    feedbackTruncated: false,
    results: [notFound, dispatched],
  };
  assert.equal(RunMinecraftCommandsOutputSchema.safeParse(output).success, false);
  assert.equal(
    RunMinecraftCommandsOutputSchema.safeParse({ ...output, results: [dispatched, notFound] }).success,
    true,
  );
  assert.equal(RunMinecraftCommandsOutputSchema.safeParse({ ...output, results: [] }).success, false);
});

test('command bridge output accounts for the requested command count', () => {
  const dispatched = {
    command: 'say hello',
    outcome: 'dispatched',
    feedback: [],
    message: null,
    rawMessage: null,
  } as const;
  const notFound = {
    command: 'missing',
    outcome: 'not_found',
    feedback: [],
    message: 'Command was not found.',
    rawMessage: null,
  } as const;
  const output = {
    sender: { name: 'Dirt MCP', isOperator: true, isPlayer: false },
    feedbackTruncated: false,
    results: [dispatched, dispatched],
  } as const;
  const schema = runMinecraftCommandsBridgeOutputSchema({ commands: ['say hello', 'say again'] });

  assert.equal(schema.safeParse(output).success, true);
  assert.equal(schema.safeParse({ ...output, results: [dispatched] }).success, false);
  assert.equal(schema.safeParse({ ...output, results: [notFound] }).success, true);
  assert.equal(schema.safeParse({ ...output, results: [dispatched, dispatched, dispatched] }).success, false);
});
