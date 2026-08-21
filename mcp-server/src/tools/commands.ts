import type { CallToolResult, McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import { ToolFailure, toolOutputSchema } from '../bridge/errors.ts';
import type { DirtLogger } from '../logging.ts';
import { NON_IDEMPOTENT_MUTATION_ANNOTATIONS } from './common.ts';
import type { McpToolConfiguration } from './configuration.ts';
import { executeToolCall, successResult } from './execution.ts';

function isIsoControl(codePoint: number): boolean {
  return codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f);
}

function containsIsoControl(value: string): boolean {
  for (const character of value) {
    const codePoint = character.codePointAt(0);
    if (codePoint !== undefined && isIsoControl(codePoint)) return true;
  }
  return false;
}

function isJavaWhitespace(codePoint: number): boolean {
  // Matches the code points used by Java Character.isWhitespace and String.strip.
  return (
    (codePoint >= 0x09 && codePoint <= 0x0d) ||
    (codePoint >= 0x1c && codePoint <= 0x20) ||
    codePoint === 0x1680 ||
    (codePoint >= 0x2000 && codePoint <= 0x2006) ||
    (codePoint >= 0x2008 && codePoint <= 0x200a) ||
    codePoint === 0x2028 ||
    codePoint === 0x2029 ||
    codePoint === 0x205f ||
    codePoint === 0x3000
  );
}

function stripJavaWhitespace(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && isJavaWhitespace(value.charCodeAt(start))) start++;
  while (end > start && isJavaWhitespace(value.charCodeAt(end - 1))) end--;
  return value.slice(start, end);
}

export function normalizeMinecraftCommand(command: string): string {
  const stripped = stripJavaWhitespace(command);
  return stripped.startsWith('/') ? stripJavaWhitespace(stripped.slice(1)) : stripped;
}

const MinecraftCommandInputSchema = z
  .string()
  .min(1)
  .refine((command) => !containsIsoControl(command), 'Command must not contain ISO control characters.')
  .refine((command) => normalizeMinecraftCommand(command).length > 0, 'Command must not be empty after normalization.')
  .describe(
    'One Minecraft command. Dirt removes outer whitespace and one optional leading slash before dispatch; control characters are forbidden.',
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

const NormalizedCommandSchema = z
  .string()
  .min(1)
  .refine((command) => !containsIsoControl(command), 'Normalized command must not contain ISO control characters.')
  .refine((command) => stripJavaWhitespace(command) === command, 'Command must not have outer whitespace.')
  .describe('Normalized command passed to Paper after removing one optional in-game leading slash.');
const CommandFeedbackSchema = z
  .array(z.string().min(1))
  .describe('Bounded non-empty plain-text messages synchronously sent to this command sender.');
const commandResultShape = {
  command: NormalizedCommandSchema,
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
      .describe(
        'Non-empty attempted prefix in request order. Every non-final result is dispatched; the final result is the first failure, or every supplied command was dispatched.',
      ),
  })
  .strict()
  .superRefine((output, context) => {
    for (let index = 0; index < output.results.length - 1; index++) {
      if (output.results[index]?.outcome !== 'dispatched') {
        context.addIssue({
          code: 'custom',
          path: ['results', index, 'outcome'],
          message: 'Only the final attempted command may have a failure outcome.',
        });
      }
    }
  })
  .describe('Ordered Minecraft command dispatch outcomes and bounded synchronous feedback.');

type RunMinecraftCommandsInput = z.infer<typeof RunMinecraftCommandsInputSchema>;
type RunMinecraftCommandsOutput = z.infer<typeof RunMinecraftCommandsOutputSchema>;

export function requireMatchingCommandResults(
  input: RunMinecraftCommandsInput,
  output: RunMinecraftCommandsOutput,
): void {
  if (output.results.length > input.commands.length) invalidCommandResponse();

  for (const [index, result] of output.results.entries()) {
    if (result.command !== normalizeMinecraftCommand(input.commands[index]!)) invalidCommandResponse();
  }

  const finalResult = output.results.at(-1)!;
  if (output.results.length < input.commands.length && finalResult.outcome === 'dispatched') invalidCommandResponse();
}

function invalidCommandResponse(): never {
  throw new ToolFailure({
    code: 'bridge_invalid_response',
    message: 'Paper bridge response was not the requested fail-fast command prefix.',
  });
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
  const runMinecraftCommands = server.registerTool(
    'run_minecraft_commands',
    {
      title: 'Run Minecraft commands',
      description:
        'Attempt registered Minecraft commands in order, no more than once each, as an operator-level non-player sender. Player-only commands, @s, and relative context can therefore behave differently. Execution stops at the first not_found or dispatch_failed result; later commands are not attempted. Dispatch is synchronous, but arbitrary non-atomic effects may outlive the response and are outside Dirt edit history and undo. A dispatched result means Paper invoked a target, not that the command reported semantic success. A timeout, disconnect, or unexpected internal failure can leave completion ambiguous; inspect state before retrying. Keep the batch and encoded request within active limits; captured feedback is truncated at its request-wide limit.' +
        (toolConfiguration.get_server_status
          ? ' Those limits are reported by get_server_status with include.configuration=true.'
          : ''),
      inputSchema: RunMinecraftCommandsInputSchema,
      outputSchema: toolOutputSchema(RunMinecraftCommandsOutputSchema),
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
          const output = await bridge.request(
            BRIDGE_ROUTES.runMinecraftCommands,
            callId,
            RunMinecraftCommandsOutputSchema,
            input,
          );
          requireMatchingCommandResults(input, output);
          return commandCallResult(input, output);
        },
      ),
  );
  if (!toolConfiguration.run_minecraft_commands) runMinecraftCommands.disable();
}
