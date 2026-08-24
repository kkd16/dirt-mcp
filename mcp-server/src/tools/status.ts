import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import type { components } from '../generated/openapi.ts';
import type { DirtLogger } from '../logging.ts';
import {
  BlockPositionSchema,
  EmptyInputSchema,
  INT32_MAX,
  MAX_BLOCK_STATE_PATTERNS,
  MAX_PALETTE_ENTRIES,
  READ_WORLD_ANNOTATIONS,
} from './common.ts';
import type { McpToolConfiguration } from './configuration.ts';
import { executeToolCall, successResult } from './execution.ts';

const PositiveInt32Schema = z.number().int().min(1).max(INT32_MAX);

const DEFAULT_STATUS_INCLUDE = {
  players: false,
  worlds: true,
  configuration: false,
} as const;

const ServerStatusIncludeOptionsSchema = z
  .object({
    players: z
      .boolean()
      .default(DEFAULT_STATUS_INCLUDE.players)
      .describe('Include online player names, locations, game modes, and facing directions.'),
    worlds: z
      .boolean()
      .default(DEFAULT_STATUS_INCLUDE.worlds)
      .describe('Include loaded worlds and their current world-level state.'),
    configuration: z
      .boolean()
      .default(DEFAULT_STATUS_INCLUDE.configuration)
      .describe('Include active operation limits and bounded edit-history settings.'),
  })
  .strict()
  .default(DEFAULT_STATUS_INCLUDE)
  .describe('Optional status sections; build and performance information are always returned.');

export const GetServerStatusInputSchema = z
  .object({ include: ServerStatusIncludeOptionsSchema })
  .strict()
  .describe('Status sections to return.');

const PingServerOutputSchema = z
  .object({
    status: z.literal('ok').describe('All Dirt, Paper, and FAWE health checks passed.'),
  })
  .strict()
  .describe('Successful end-to-end Dirt server health check.') satisfies z.ZodType<
  components['schemas']['PingResponse']
>;

const LimitConfigurationSchema = z
  .object({
    maxRegionVolume: PositiveInt32Schema.describe('Maximum cuboid mutation/count volume or explicit block placements.'),
    maxEditTouchedChunks: PositiveInt32Schema.describe('Maximum distinct loaded chunks one mutation may touch.'),
    maxInspectionTouchedChunks: PositiveInt32Schema.describe('Maximum loaded chunks a region inspection may snapshot.'),
    maxPerspectiveTouchedChunks: PositiveInt32Schema.describe('Maximum loaded chunks a perspective view may check.'),
    maxBlockStatePatterns: PositiveInt32Schema.max(MAX_BLOCK_STATE_PATTERNS).describe(
      'Maximum match patterns in one operation; inspection include and exclude lists share this cap.',
    ),
    maxPaletteEntries: PositiveInt32Schema.max(MAX_PALETTE_ENTRIES).describe(
      'Maximum exact block-state entries in one edit or exact inspection structure palette.',
    ),
    maxChangedBlocks: PositiveInt32Schema.describe('Maximum blocks one edit may change.'),
    maxInspectionVolume: PositiveInt32Schema.describe(
      'Maximum blocks scanned by an exact or orthographic region inspection.',
    ),
    maxPerspectiveRayDistanceBudget: PositiveInt32Schema.describe(
      'Maximum perspective ray count multiplied by maximum ray distance.',
    ),
    maxInspectionResultLimit: PositiveInt32Schema.describe(
      'Maximum caller-selected exact or orthographic inspection result limit.',
    ),
    maxPerspectiveRays: PositiveInt32Schema.describe('Maximum rays in one perspective view.'),
    maxCommandsPerRequest: PositiveInt32Schema.describe(
      'Maximum commands accepted in one ordered Minecraft command batch.',
    ),
    maxCommandFeedbackCharacters: PositiveInt32Schema.describe(
      'Maximum Unicode code points of synchronous plain-text feedback retained across one command batch.',
    ),
  })
  .strict()
  .describe('Active limits that constrain Dirt inspection, mutation, and command tools.');

export const EditHistoryConfigurationSchema = z
  .object({
    maxEntriesPerWorld: PositiveInt32Schema.describe('Maximum retained undoable edits in one loaded world.'),
    maxEntriesTotal: PositiveInt32Schema.describe('Maximum retained undoable edits across all loaded worlds.'),
    maxRetainedChangedBlocks: PositiveInt32Schema.describe(
      'Maximum sum of changed-block counts across all retained undoable edits; at least maxChangedBlocks.',
    ),
  })
  .strict()
  .describe('Active bounded in-memory edit-history retention settings.');

const BuildsSchema = z
  .object({
    minecraft: z.string().min(1).describe('Running Minecraft build.'),
    paper: z.string().min(1).describe('Full running Paper build identifier.'),
    dirtPlugin: z.string().min(1).describe('Running Dirt Paper plugin build.'),
    fawe: z.string().min(1).describe('Running FastAsyncWorldEdit build.'),
  })
  .strict()
  .describe('Exact runtime build identifiers.');

const PerformanceSchema = z
  .object({
    tpsOneMinute: z.number().nonnegative().describe('Paper one-minute ticks per second.'),
    averageTickTimeMillis: z.number().nonnegative().describe('Paper average tick duration in milliseconds.'),
  })
  .strict()
  .describe('Lightweight current Paper performance indicators.');

const PlayerSummarySchema = z
  .object({
    online: z.number().int().nonnegative().describe('Current online player count.'),
    maximum: z.number().int().nonnegative().describe('Configured player capacity.'),
    entries: z
      .array(
        z
          .object({
            name: z.string().min(1).describe('Current player name.'),
            world: z.string().min(1).describe('Loaded world containing the player.'),
            gameMode: z.enum(['survival', 'creative', 'adventure', 'spectator']).describe('Current game mode.'),
            facing: z
              .enum(['north', 'east', 'south', 'west'])
              .describe('Current horizontal cardinal direction the player is facing.'),
            blockPosition: BlockPositionSchema.describe('Current integer block position.'),
          })
          .strict(),
      )
      .describe('Online players sorted by name, with location and facing context.'),
  })
  .strict()
  .describe('Current player presence.');

const WorldsSchema = z
  .array(
    z
      .object({
        name: z.string().min(1).describe('Exact loaded world name accepted by world tools.'),
        environment: z.string().min(1).describe('Paper world environment, such as normal or nether.'),
        minY: z.number().int().describe('Minimum valid block Y.'),
        maxY: z.number().int().describe('Maximum valid block Y, inclusive.'),
        spawn: BlockPositionSchema.describe('Current world spawn block.'),
        timeOfDay: z.number().int().min(0).max(23_999).describe('Current Minecraft time of day.'),
        storm: z.boolean().describe('Whether the world currently has a storm.'),
        thundering: z.boolean().describe('Whether the world is currently thundering.'),
        playerCount: z.number().int().nonnegative().describe('Players currently in this world.'),
      })
      .strict(),
  )
  .describe('All currently loaded worlds in Paper order.');

const ServerConfigurationShape = {
  limits: LimitConfigurationSchema,
  editHistory: EditHistoryConfigurationSchema,
};

const ServerConfigurationSchema = z
  .object(ServerConfigurationShape)
  .strict()
  .describe('Active Dirt operation limits and edit-history retention.');

export const GetServerStatusOutputSchema = z
  .object({
    builds: BuildsSchema,
    performance: PerformanceSchema,
    players: PlayerSummarySchema.nullable().describe('Current player presence, or null when not requested.'),
    worlds: WorldsSchema.nullable().describe('Loaded worlds, or null when not requested.'),
    configuration: ServerConfigurationSchema.nullable().describe(
      'Active Dirt configuration, or null when not requested.',
    ),
  })
  .strict()
  .describe('Current server status projected to the requested optional sections.') satisfies z.ZodType<
  components['schemas']['ServerStatusResponse']
>;

type GetServerStatusInput = z.infer<typeof GetServerStatusInputSchema>;

function bridgeStatusRequest(input: GetServerStatusInput): components['schemas']['ServerStatusRequest'] {
  return {
    includePlayers: input.include.players,
    includeWorlds: input.include.worlds,
    includeConfiguration: input.include.configuration,
  };
}

export function registerStatusTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  if (toolConfiguration.ping_server) {
    server.registerTool(
      'ping_server',
      {
        title: 'Ping Dirt server',
        description:
          'Run a non-mutating end-to-end health check across the authenticated bridge, Dirt plugin, Paper, and a Paper-backed FAWE session. Returns only status ok on success.',
        inputSchema: EmptyInputSchema,
        outputSchema: PingServerOutputSchema,
        annotations: READ_WORLD_ANNOTATIONS,
      },
      async (_input, context) =>
        executeToolCall(
          logger,
          {
            operation: 'ping_server',
            context,
            failureContext: `Dirt server health check failed at ${bridge.origin}`,
          },
          async (callId) => {
            const result = await bridge.request(
              BRIDGE_ROUTES.ping,
              callId,
              PingServerOutputSchema,
              undefined,
              context.mcpReq.signal,
            );
            return successResult(result, 'ok');
          },
        ),
    );
  }

  if (toolConfiguration.get_server_status) {
    server.registerTool(
      'get_server_status',
      {
        title: 'Get Dirt server status',
        description:
          'Return current builds and performance plus selected player, world, and configuration sections. Worlds default on; players and configuration default off.',
        inputSchema: GetServerStatusInputSchema,
        outputSchema: GetServerStatusOutputSchema,
        annotations: READ_WORLD_ANNOTATIONS,
      },
      async (input, context) =>
        executeToolCall(
          logger,
          {
            operation: 'get_server_status',
            context,
            failureContext: `Could not get Dirt server status from ${bridge.origin}`,
          },
          async (callId) => {
            const result = await bridge.request(
              BRIDGE_ROUTES.serverStatus,
              callId,
              GetServerStatusOutputSchema,
              bridgeStatusRequest(input),
              context.mcpReq.signal,
            );
            const summary = [`Paper ${result.builds.paper}`];
            if (result.players !== null) summary.push(`players ${result.players.online}/${result.players.maximum}`);
            if (result.worlds !== null) {
              const worldNames = result.worlds.map((world) => world.name).join(', ') || 'none';
              summary.push(`loaded worlds: ${worldNames}`);
            }
            return successResult(result, `${summary.join('; ')}.`);
          },
        ),
    );
  }
}
