import { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import { BlockPositionSchema, EmptyInputSchema, INT32_MAX, READ_WORLD_ANNOTATIONS } from './common.ts';
import { McpToolConfigurationSchema, type McpToolConfiguration } from './configuration.ts';
import { executeToolCall, successResult } from './execution.ts';

const PositiveInt32Schema = z.number().int().min(1).max(INT32_MAX);

const PingServerOutputSchema = z
  .object({
    status: z.literal('ok').describe('All Dirt, Paper, and FAWE health checks passed.'),
  })
  .strict()
  .describe('Successful end-to-end Dirt server health check.');

const LimitConfigurationSchema = z
  .object({
    maxRequestBytes: z.number().int().positive().describe('Maximum JSON request-body size accepted by the bridge.'),
    maxRegionVolume: z
      .number()
      .int()
      .positive()
      .describe('Maximum cuboid mutation/count volume or explicit block placements.'),
    maxTouchedChunks: z.number().int().positive().describe('Maximum distinct loaded chunks one mutation may touch.'),
    maxInspectionTouchedChunks: z
      .number()
      .int()
      .positive()
      .describe('Maximum distinct loaded chunks one inspection may snapshot.'),
    maxBlockStatePatterns: z
      .number()
      .int()
      .positive()
      .describe(
        'Maximum block-state patterns or palette entries in one operation; inspection include and exclude lists share this cap.',
      ),
    maxChangedBlocks: z.number().int().positive().describe('Maximum blocks one edit may change.'),
    maxInspectionVolume: z.number().int().positive().describe('Maximum blocks scanned by a detailed inspection.'),
    defaultInspectionResultLimit: z
      .number()
      .int()
      .positive()
      .describe('Default block, run, or visible-block result limit for detailed inspections.'),
    maxInspectionResultLimit: z
      .number()
      .int()
      .positive()
      .describe('Maximum caller-selectable result limit for detailed inspections.'),
  })
  .strict()
  .describe('Active limits that constrain Dirt inspection and mutation tools.');

export const EditHistoryConfigurationSchema = z
  .object({
    maxEntriesPerWorld: PositiveInt32Schema.describe('Maximum retained undoable edits in one loaded world.'),
    maxEntriesTotal: PositiveInt32Schema.describe('Maximum retained undoable edits across all loaded worlds.'),
    maxRetainedChangedBlocks: PositiveInt32Schema.describe(
      'Maximum sum of changed-block counts across all retained undoable edits; at least maxChangedBlocks.',
    ),
  })
  .strict()
  .refine(
    (history) => history.maxEntriesPerWorld <= history.maxEntriesTotal,
    'maxEntriesPerWorld must not exceed maxEntriesTotal.',
  )
  .describe('Active bounded in-memory edit-history retention settings.');

const DefaultConfigurationSchema = z
  .object({
    regionBlocksIncludeAir: z.boolean().describe('Default air inclusion for exact region retrieval.'),
    regionBlocksFormat: z.enum(['blocks', 'runs']).describe('Default exact region-retrieval format.'),
    editDryRun: z.boolean().describe('Default dry-run behavior for every Dirt block-edit tool.'),
  })
  .strict()
  .describe('Active optional-argument defaults for Dirt tools.');

export const ServerStatusSchema = z
  .object({
    builds: z
      .object({
        minecraft: z.string().min(1).describe('Running Minecraft build.'),
        paper: z.string().min(1).describe('Full running Paper build identifier.'),
        dirtMcp: z.string().min(1).describe('Running Dirt MCP Paper plugin build.'),
        fawe: z.string().min(1).describe('Running FastAsyncWorldEdit build.'),
      })
      .strict()
      .describe('Exact runtime build identifiers.'),
    performance: z
      .object({
        tpsOneMinute: z.number().nonnegative().describe('Paper one-minute ticks per second.'),
        averageTickTimeMillis: z.number().nonnegative().describe('Paper average tick duration in milliseconds.'),
      })
      .strict()
      .describe('Lightweight current Paper performance indicators.'),
    players: z
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
      .describe('Current player presence.'),
    worlds: z
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
      .describe('All currently loaded worlds in Paper order.'),
    limits: LimitConfigurationSchema,
    editHistory: EditHistoryConfigurationSchema,
    defaults: DefaultConfigurationSchema,
    tools: McpToolConfigurationSchema,
  })
  .strict()
  .refine((status) => status.editHistory.maxRetainedChangedBlocks >= status.limits.maxChangedBlocks, {
    message: 'maxRetainedChangedBlocks must be at least maxChangedBlocks.',
    path: ['editHistory', 'maxRetainedChangedBlocks'],
  })
  .describe(
    'Current lightweight Paper context, limits, edit-history retention, defaults, and MCP tool availability for grounding subsequent Dirt calls.',
  );

export function registerStatusTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
): void {
  const pingServer = server.registerTool(
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
        {
          tool: 'ping_server',
          context,
          failureContext: `Dirt server health check failed at ${bridge.origin}`,
        },
        async (callId) => {
          const result = await bridge.request(BRIDGE_ROUTES.ping, callId, PingServerOutputSchema);
          return successResult(result, 'ok');
        },
      ),
  );
  if (!toolConfiguration.ping_server) pingServer.disable();

  const getServerStatus = server.registerTool(
    'get_server_status',
    {
      title: 'Get Dirt server status',
      description:
        'Return current Minecraft, Paper, Dirt MCP, and FAWE builds; TPS; online players, block positions, and cardinal facing directions; loaded worlds; and active Dirt limits, edit-history retention, defaults, and MCP tool availability. Use this to ground later world operations.',
      inputSchema: EmptyInputSchema,
      outputSchema: ServerStatusSchema,
      annotations: READ_WORLD_ANNOTATIONS,
    },
    async (_input, context) =>
      executeToolCall(
        {
          tool: 'get_server_status',
          context,
          failureContext: `Could not get Dirt server status from ${bridge.origin}`,
        },
        async (callId) => {
          const status = await bridge.request(BRIDGE_ROUTES.serverStatus, callId, ServerStatusSchema);
          const worldNames = status.worlds.map((world) => world.name).join(', ') || 'none';
          return successResult(
            status,
            `Paper ${status.builds.paper}; players ${status.players.online}/${status.players.maximum}; loaded worlds: ${worldNames}.`,
          );
        },
      ),
  );
  if (!toolConfiguration.get_server_status) getServerStatus.disable();
}
