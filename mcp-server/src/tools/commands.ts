import type { CallToolResult, McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import type { components } from '../generated/openapi.ts';
import type { DirtLogger } from '../logging.ts';
import { NON_IDEMPOTENT_MUTATION_ANNOTATIONS, NonBlankStringSchema } from './common.ts';
import type { McpToolConfiguration } from './configuration.ts';
import { executeToolCall, successResult } from './execution.ts';

const MinecraftCommandInputSchema = NonBlankStringSchema.describe(
  'One Minecraft command. Paper owns command normalization, parsing, and dispatch validation.',
);

export const RunMinecraftCommandsInputSchema = z
  .object({
    commands: z
      .array(MinecraftCommandInputSchema)
      .min(1)
      .describe('Commands in execution order. The Paper bridge enforces active batch-size and request-size limits.'),
  })
  .strict()
  .describe('An ordered, non-empty Minecraft command batch.');

const CommandFeedbackSchema = z
  .array(z.string().min(1))
  .describe('Bounded non-empty plain-text messages synchronously sent to this command sender.');
const NormalizedMinecraftCommandSchema = z
  .string()
  .min(1)
  .regex(
    // oxlint-disable-next-line eslint/no-control-regex -- The wire contract excludes precisely these ISO controls.
    /^[^ \u0000-\u001F\u007F-\u009F\u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000](?:[^\u0000-\u001F\u007F-\u009F]*[^ \u0000-\u001F\u007F-\u009F\u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000])?$/u,
  )
  .describe('Command returned after Paper-owned normalization.');
const JAVA_STRIP_WHITESPACE =
  /^[ \u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]+|[ \u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]+$/gu;
const commandResultShape = {
  command: NormalizedMinecraftCommandSchema,
  feedback: CommandFeedbackSchema,
};

export const CommandResultSchema = z.discriminatedUnion('outcome', [
  z
    .object({
      ...commandResultShape,
      outcome: z
        .literal('dispatched')
        .describe('Paper found and invoked a target; this does not assert semantic command success.'),
      message: z.null(),
      rawMessage: z.null(),
    })
    .strict(),
  z
    .object({
      ...commandResultShape,
      outcome: z
        .literal('not_found')
        .describe('Paper found no command target; this result ends the batch and later commands are not attempted.'),
      message: z.string().min(1).describe('Actionable explanation of the missing target.'),
      rawMessage: z.null(),
    })
    .strict(),
  z
    .object({
      ...commandResultShape,
      outcome: z
        .literal('dispatch_failed')
        .describe(
          'The target raised a Paper command exception; this result ends the batch and later commands are not attempted.',
        ),
      message: z.string().min(1).describe('Deepest non-empty cause message found within bounded traversal.'),
      rawMessage: z
        .string()
        .min(1)
        .describe('Original non-empty Paper command-exception message, or a stable fallback.'),
    })
    .strict(),
]);

export const RunMinecraftCommandsOutputSchema = z
  .object({
    sender: z
      .object({
        name: z.string().min(1).describe('Actual Paper feedback-forwarding sender name.'),
        isOperator: z.literal(true).describe('The sender has console-equivalent permissions.'),
        isPlayer: z.literal(false).describe('The sender has no player entity or location context.'),
      })
      .strict(),
    feedbackTruncated: z
      .boolean()
      .describe('Whether feedback exceeded the active request-wide Unicode-code-point limit.'),
    results: z
      .array(CommandResultSchema)
      .min(1)
      .superRefine((results, context) => {
        const failureIndex = results.slice(0, -1).findIndex((result) => result.outcome !== 'dispatched');
        if (failureIndex !== -1) {
          context.addIssue({
            code: 'custom',
            message: 'Only the final command result may report a dispatch failure.',
            path: [failureIndex, 'outcome'],
          });
        }
      })
      .describe(
        'Non-empty attempted prefix in request order. Every non-final result is dispatched; the final result is the first failure, or every supplied command was dispatched.',
      ),
  })
  .strict()
  .describe('Ordered Minecraft command dispatch outcomes and bounded synchronous feedback.') satisfies z.ZodType<
  components['schemas']['RunMinecraftCommandsResponse']
>;

type RunMinecraftCommandsInput = z.infer<typeof RunMinecraftCommandsInputSchema>;
type RunMinecraftCommandsOutput = z.infer<typeof RunMinecraftCommandsOutputSchema>;

export function runMinecraftCommandsBridgeOutputSchema(input: RunMinecraftCommandsInput) {
  return RunMinecraftCommandsOutputSchema.superRefine((output, context) => {
    if (output.results.length > input.commands.length) {
      context.addIssue({
        code: 'custom',
        message: 'The bridge returned more command results than were requested.',
        path: ['results'],
      });
      return;
    }
    if (output.results.at(-1)?.outcome === 'dispatched' && output.results.length !== input.commands.length) {
      context.addIssue({
        code: 'custom',
        message: 'A fully dispatched result must account for every requested command.',
        path: ['results'],
      });
    }
    for (const [index, result] of output.results.entries()) {
      const requestedCommand = input.commands[index];
      if (requestedCommand === undefined || result.command !== normalizeMinecraftCommand(requestedCommand)) {
        context.addIssue({
          code: 'custom',
          message: 'Each command result must identify the corresponding normalized request command.',
          path: ['results', index, 'command'],
        });
      }
    }
  });
}

function normalizeMinecraftCommand(command: string): string {
  let normalized = command.replace(JAVA_STRIP_WHITESPACE, '');
  if (normalized.startsWith('/')) normalized = normalized.slice(1).replace(JAVA_STRIP_WHITESPACE, '');
  return normalized;
}

function commandCallResult(input: RunMinecraftCommandsInput, output: RunMinecraftCommandsOutput): CallToolResult {
  const finalResult = output.results.at(-1)!;
  if (finalResult.outcome === 'dispatched') {
    const noun = output.results.length === 1 ? 'command' : 'commands';
    return successResult(output, `Dispatched ${output.results.length} ${noun} in order.`);
  }

  const dispatchedCount = output.results.length - 1;
  const failure = finalResult.outcome === 'not_found' ? 'was not found' : 'failed during dispatch';
  const priorSummary =
    dispatchedCount === 0
      ? 'No prior commands were dispatched.'
      : `${dispatchedCount} prior ${dispatchedCount === 1 ? 'command was' : 'commands were'} dispatched.`;
  const remainingCount = input.commands.length - output.results.length;
  const remainingSummary =
    remainingCount === 0
      ? ''
      : ` ${remainingCount} remaining ${remainingCount === 1 ? 'command was' : 'commands were'} not attempted.`;
  return {
    ...successResult(
      output,
      `Command ${output.results.length} of ${input.commands.length} ${failure}. ${priorSummary}${remainingSummary}`,
    ),
    isError: true,
  };
}

export function registerCommandTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  if (toolConfiguration.run_minecraft_commands) {
    server.registerTool(
      'run_minecraft_commands',
      {
        title: 'Run Minecraft commands',
        description:
          'Run Minecraft commands in order as an operator-level non-player sender. Execution stops at the first dispatch failure. Command effects are not atomic or undoable; after an ambiguous transport failure, inspect state before retrying.',
        inputSchema: RunMinecraftCommandsInputSchema,
        outputSchema: RunMinecraftCommandsOutputSchema,
        annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
      },
      async (input, context) =>
        executeToolCall(
          logger,
          {
            operation: 'run_minecraft_commands',
            context,
            failureContext: 'Could not run Minecraft commands',
          },
          async (callId) => {
            const request: components['schemas']['RunMinecraftCommandsRequest'] = input;
            const output = await bridge.request(
              BRIDGE_ROUTES.runMinecraftCommands,
              callId,
              runMinecraftCommandsBridgeOutputSchema(input),
              request,
              context.mcpReq.signal,
            );
            return commandCallResult(input, output);
          },
        ),
    );
  }
}
