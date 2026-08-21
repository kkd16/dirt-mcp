import assert from 'node:assert/strict';
import test from 'node:test';
import { BRIDGE_ROUTES } from '../dist/bridge/contract.js';
import { ToolFailure } from '../dist/bridge/errors.js';
import {
  CommandResultSchema,
  normalizeMinecraftCommand,
  requireMatchingCommandResults,
  RunMinecraftCommandsInputSchema,
  RunMinecraftCommandsOutputSchema,
} from '../dist/tools/commands.js';

const sender = { name: 'FeedbackForwardingSender', isOperator: true as const, isPlayer: false as const };
const successfulOutput = {
  sender,
  feedbackTruncated: false,
  results: [
    {
      command: 'say hello',
      outcome: 'dispatched' as const,
      feedback: ['hello'],
      message: null,
      rawMessage: null,
    },
    {
      command: '/time set day',
      outcome: 'dispatched' as const,
      feedback: [],
      message: null,
      rawMessage: null,
    },
  ],
};

const isInvalidBridgeResponse = (error: unknown): boolean =>
  error instanceof ToolFailure && error.code === 'bridge_invalid_response';

test('uses the 120-second command route without edit-ID salvage', () => {
  assert.deepEqual(BRIDGE_ROUTES.runMinecraftCommands, {
    method: 'POST',
    path: '/v1/run-minecraft-commands',
    timeoutMilliseconds: 120_000,
  });
});

test('normalizes commands with Java strip semantics and exactly one optional slash', () => {
  assert.equal(normalizeMinecraftCommand('\u3000 /  say hello \u3000'), 'say hello');
  assert.equal(normalizeMinecraftCommand('//time set day'), '/time set day');
  assert.equal(normalizeMinecraftCommand('say hello'), 'say hello');
  for (const nonJavaWhitespace of ['\u00a0', '\u2007', '\u202f', '\ufeff']) {
    const command = `${nonJavaWhitespace}/say hello${nonJavaWhitespace}`;
    assert.equal(normalizeMinecraftCommand(command), command);
  }
});

test('does not invent a command-length cap and rejects empty, control-bearing, and malformed batches', () => {
  const longCommand = `say ${'x'.repeat(100_000)}`;
  assert.deepEqual(RunMinecraftCommandsInputSchema.parse({ commands: [longCommand] }), { commands: [longCommand] });
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: [] }).success, false);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: [' \u3000/ \u3000'] }).success, false);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: ['\tsay hello\t'] }).success, false);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: ['say first\nsay second'] }).success, false);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: ['say\u007fhidden'] }).success, false);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: ['say\u0085hidden'] }).success, false);
  assert.equal(RunMinecraftCommandsInputSchema.safeParse({ commands: ['say hello'], extra: true }).success, false);
});

test('keeps command result variants strict and discriminated', () => {
  assert.equal(RunMinecraftCommandsOutputSchema.safeParse(successfulOutput).success, true);
  assert.equal(
    CommandResultSchema.safeParse({
      command: 'missing',
      outcome: 'not_found',
      feedback: [],
      message: 'Paper found no target for this command',
      rawMessage: null,
    }).success,
    true,
  );
  assert.equal(
    CommandResultSchema.safeParse({
      command: 'bad',
      outcome: 'dispatch_failed',
      feedback: ['Usage: /bad'],
      message: 'Incorrect argument',
      rawMessage: 'Unhandled exception executing command',
    }).success,
    true,
  );

  assert.equal(CommandResultSchema.safeParse({ ...successfulOutput.results[0], message: 'unexpected' }).success, false);
  assert.equal(
    CommandResultSchema.safeParse({
      command: 'missing',
      outcome: 'not_found',
      feedback: [],
      message: 'missing',
      rawMessage: 'unexpected',
    }).success,
    false,
  );
  assert.equal(
    CommandResultSchema.safeParse({
      command: 'bad',
      outcome: 'dispatch_failed',
      feedback: [],
      message: 'failed',
      rawMessage: null,
    }).success,
    false,
  );
  assert.equal(CommandResultSchema.safeParse({ ...successfulOutput.results[0], extra: true }).success, false);
  assert.equal(RunMinecraftCommandsOutputSchema.safeParse({ ...successfulOutput, results: [] }).success, false);
  assert.equal(
    RunMinecraftCommandsOutputSchema.safeParse({
      ...successfulOutput,
      results: [
        {
          command: 'missing',
          outcome: 'not_found',
          feedback: [],
          message: 'Paper found no target for this command',
          rawMessage: null,
        },
        successfulOutput.results[1],
      ],
    }).success,
    false,
  );
});

test('correlates a fail-fast attempted prefix to normalized request order', () => {
  const successfulInput = RunMinecraftCommandsInputSchema.parse({
    commands: ['\u3000/say hello\u3000', '//time set day'],
  });
  const allDispatched = RunMinecraftCommandsOutputSchema.parse(successfulOutput);
  assert.doesNotThrow(() => requireMatchingCommandResults(successfulInput, allDispatched));

  const prefixInput = RunMinecraftCommandsInputSchema.parse({
    commands: ['say hello', 'bad syntax', 'say skipped'],
  });
  const stoppedPrefix = RunMinecraftCommandsOutputSchema.parse({
    sender,
    feedbackTruncated: false,
    results: [
      successfulOutput.results[0],
      {
        command: 'bad syntax',
        outcome: 'dispatch_failed',
        feedback: ['Usage: /bad'],
        message: 'Incorrect argument',
        rawMessage: 'Unhandled exception executing command',
      },
    ],
  });
  assert.doesNotThrow(() => requireMatchingCommandResults(prefixInput, stoppedPrefix));
  assert.doesNotThrow(() =>
    requireMatchingCommandResults(
      RunMinecraftCommandsInputSchema.parse({ commands: prefixInput.commands.slice(0, 2) }),
      stoppedPrefix,
    ),
  );

  const stoppedImmediately = RunMinecraftCommandsOutputSchema.parse({
    sender,
    feedbackTruncated: false,
    results: [
      {
        command: 'missing',
        outcome: 'not_found',
        feedback: [],
        message: 'Paper found no target for this command',
        rawMessage: null,
      },
    ],
  });
  assert.doesNotThrow(() =>
    requireMatchingCommandResults(
      RunMinecraftCommandsInputSchema.parse({ commands: ['missing', 'say skipped'] }),
      stoppedImmediately,
    ),
  );

  assert.throws(
    () =>
      requireMatchingCommandResults(RunMinecraftCommandsInputSchema.parse({ commands: ['say hello'] }), allDispatched),
    isInvalidBridgeResponse,
  );
  assert.throws(
    () =>
      requireMatchingCommandResults(
        prefixInput,
        RunMinecraftCommandsOutputSchema.parse({
          ...allDispatched,
          results: [allDispatched.results[1]!, allDispatched.results[0]!],
        }),
      ),
    isInvalidBridgeResponse,
  );
  assert.throws(
    () =>
      requireMatchingCommandResults(
        prefixInput,
        RunMinecraftCommandsOutputSchema.parse({
          ...stoppedPrefix,
          results: [{ ...stoppedPrefix.results[0]!, command: 'say something-else' }, stoppedPrefix.results[1]!],
        }),
      ),
    isInvalidBridgeResponse,
  );
  assert.throws(
    () =>
      requireMatchingCommandResults(
        prefixInput,
        RunMinecraftCommandsOutputSchema.parse({
          ...allDispatched,
          results: [
            allDispatched.results[0],
            {
              command: 'bad syntax',
              outcome: 'dispatched',
              feedback: [],
              message: null,
              rawMessage: null,
            },
          ],
        }),
      ),
    isInvalidBridgeResponse,
  );
});
