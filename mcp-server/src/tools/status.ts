import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES } from '../bridge/contract.ts';
import { toolOutputSchema } from '../bridge/errors.ts';
import type { DirtLogger } from '../logging.ts';
import {
  BlockPositionSchema,
  EmptyInputSchema,
  INT32_MAX,
  MAX_BLOCK_STATE_ENTRIES,
  READ_WORLD_ANNOTATIONS,
} from './common.ts';
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
    maxRequestBytes: PositiveInt32Schema.max(INT32_MAX - 1).describe(
      'Maximum JSON request-body size accepted by the bridge.',
    ),
    maxRegionVolume: PositiveInt32Schema.describe('Maximum cuboid mutation/count volume or explicit block placements.'),
    maxTouchedChunks: PositiveInt32Schema.describe('Maximum distinct loaded chunks one mutation may touch.'),
    maxInspectionTouchedChunks: PositiveInt32Schema.describe(
      'Maximum distinct loaded chunks one inspection may snapshot.',
    ),
    maxBlockStatePatterns: PositiveInt32Schema.max(MAX_BLOCK_STATE_ENTRIES).describe(
      'Maximum block-state patterns or palette entries in one operation; inspection include and exclude lists share this cap.',
    ),
    maxChangedBlocks: PositiveInt32Schema.describe('Maximum blocks one edit may change.'),
    maxInspectionVolume: PositiveInt32Schema.describe('Maximum blocks scanned by a detailed inspection.'),
    defaultInspectionResultLimit: PositiveInt32Schema.describe(
      'Default block, run, or visible-block result limit for detailed inspections.',
    ),
    maxInspectionResultLimit: PositiveInt32Schema.describe(
      'Maximum caller-selectable result limit for detailed inspections.',
    ),
  })
  .strict()
  .refine((limits) => limits.maxInspectionTouchedChunks <= limits.maxTouchedChunks, {
    message: 'maxInspectionTouchedChunks must not exceed maxTouchedChunks.',
    path: ['maxInspectionTouchedChunks'],
  })
  .refine((limits) => limits.maxChangedBlocks <= limits.maxRegionVolume, {
    message: 'maxChangedBlocks must not exceed maxRegionVolume.',
    path: ['maxChangedBlocks'],
  })
  .refine((limits) => limits.maxInspectionVolume <= limits.maxRegionVolume, {
    message: 'maxInspectionVolume must not exceed maxRegionVolume.',
    path: ['maxInspectionVolume'],
  })
  .refine((limits) => limits.defaultInspectionResultLimit <= limits.maxInspectionResultLimit, {
    message: 'defaultInspectionResultLimit must not exceed maxInspectionResultLimit.',
    path: ['defaultInspectionResultLimit'],
  })
  .refine((limits) => limits.maxInspectionResultLimit <= limits.maxInspectionVolume, {
    message: 'maxInspectionResultLimit must not exceed maxInspectionVolume.',
    path: ['maxInspectionResultLimit'],
  })
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

export const LoggingConfigurationSchema = z
  .object({
    consoleLevel: z.enum(['info', 'warning', 'error']).describe('Minimum severity shown in the Paper console.'),
    detailFileMaxBytes: PositiveInt32Schema.describe(
      'Approximate maximum bytes in each Paper JSONL detail-log generation before rotation.',
    ),
    detailFileRetainedFiles: PositiveInt32Schema.min(2)
      .max(100)
      .describe('Rotating Paper JSONL detail-log generations retained, including the active generation.'),
  })
  .strict()
  .describe('Active Paper console threshold and rotating detail-log limits.');

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
    logging: LoggingConfigurationSchema,
    tools: McpToolConfigurationSchema,
  })
  .strict()
  .refine((status) => status.editHistory.maxRetainedChangedBlocks >= status.limits.maxChangedBlocks, {
    message: 'maxRetainedChangedBlocks must be at least maxChangedBlocks.',
    path: ['editHistory', 'maxRetainedChangedBlocks'],
  })
  .describe(
    'Current lightweight Paper context, limits, edit-history retention, defaults, logging, and MCP tool availability for grounding subsequent Dirt calls.',
  );

export function registerStatusTools(
  server: McpServer,
  bridge: BridgeClient,
  toolConfiguration: McpToolConfiguration,
  logger: DirtLogger,
): void {
  const pingServer = server.registerTool(
    'ping_server',
    {
      title: 'Ping Dirt server',
      description:
        'Run a non-mutating end-to-end health check across the authenticated bridge, Dirt plugin, Paper, and a Paper-backed FAWE session. Returns only status ok on success.',
      inputSchema: EmptyInputSchema,
      outputSchema: toolOutputSchema(PingServerOutputSchema),
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
        'Return current Minecraft, Paper, Dirt MCP, and FAWE builds; TPS; online players, block positions, and cardinal facing directions; loaded worlds; and active Dirt limits, edit-history retention, defaults, logging, and MCP tool availability. Use this to ground later world operations.',
      inputSchema: EmptyInputSchema,
      outputSchema: toolOutputSchema(ServerStatusSchema),
      annotations: READ_WORLD_ANNOTATIONS,
    },
    async (_input, context) =>
      executeToolCall(
        logger,
        {
          operation: 'get_server_status',
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
