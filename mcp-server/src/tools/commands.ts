import { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import { NON_IDEMPOTENT_MUTATION_ANNOTATIONS, NonBlankStringSchema } from './common.ts';
import { executeToolCall } from './execution.ts';

export const RunMinecraftCommandsInputSchema = z
  .object({
    commands: z
      .array(NonBlankStringSchema)
      .min(1)
      .describe('Registered Minecraft commands in execution order. Each may include one in-game leading slash.'),
  })
  .strict()
  .describe('An ordered batch of one or more commands to dispatch through Paper.');

export const CommandOutcomeSchema = z.enum(['dispatched', 'not_found', 'dispatch_failed']);

export const RunMinecraftCommandsOutputSchema = z
  .object({
    sender: z
      .object({
        name: z.string().min(1).describe('Actual Paper command-sender name.'),
        isOperator: z.literal(true).describe('The sender has operator/console-equivalent permissions.'),
        isPlayer: z.literal(false).describe('The supported Paper feedback sender is not a player entity.'),
      })
      .strict(),
    feedbackTruncated: z
      .boolean()
      .describe('Whether request-wide command feedback exceeded the configured character limit.'),
    results: z
      .array(
        z
          .object({
            command: z.string().min(1).describe('Normalized command dispatched without the in-game leading slash.'),
            outcome: CommandOutcomeSchema.describe(
              'Paper dispatch outcome; dispatched is not a semantic success signal.',
            ),
            feedback: z.array(z.string()).describe('Plain-text feedback emitted synchronously during dispatch.'),
            message: z.string().nullable().describe('Actionable underlying failure explanation, otherwise null.'),
            rawMessage: z
              .string()
              .nullable()
              .describe('Original Paper CommandException message for dispatch failures, otherwise null.'),
          })
          .strict(),
      )
      .min(1)
      .describe('One result per supplied command in the original order.'),
  })
  .strict()
  .describe('Ordered Paper command dispatch results and bounded feedback.');

export function registerCommandTools(server: McpServer, bridge: BridgeClient): void {
  server.registerTool(
    'run_minecraft_commands',
    {
      title: 'Run Minecraft commands',
      description:
        'Run registered vanilla, Paper, or plugin commands sequentially with operator-level permissions. The sender is not a player: player-only commands, @s, and relative context can differ. Every command is attempted once in order; arbitrary effects are immediate and are not covered by Dirt undo or edit limits.',
      inputSchema: RunMinecraftCommandsInputSchema,
      outputSchema: RunMinecraftCommandsOutputSchema,
      annotations: NON_IDEMPOTENT_MUTATION_ANNOTATIONS,
    },
    async (input, context) =>
      executeToolCall(
        { tool: 'run_minecraft_commands', context, failureContext: 'Could not run Minecraft commands' },
        async (callId) => {
          const result = await bridge.request(
            BRIDGE_ROUTES.runMinecraftCommands,
            callId,
            RunMinecraftCommandsOutputSchema,
            input,
          );
          const dispatched = result.results.filter((entry) => entry.outcome === 'dispatched').length;
          const failed = result.results.some(
            (entry) => entry.outcome === 'not_found' || entry.outcome === 'dispatch_failed',
          );
          const summary = failed
            ? `Dispatched ${dispatched} of ${result.results.length} command(s); see per-command outcomes.`
            : `Dispatched ${dispatched} command(s) in order.`;
          return {
            content: [{ type: 'text', text: summary }],
            structuredContent: result,
            ...(failed ? { isError: true } : {}),
          };
        },
      ),
  );
}
