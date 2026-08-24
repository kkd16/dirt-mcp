import * as z from 'zod/v4';
import type { components, paths } from '../generated/openapi.ts';

type BridgePath = Extract<keyof paths, `/v1/${string}`>;

export interface BridgeRoute {
  readonly method: 'GET' | 'POST';
  readonly path: BridgePath;
  readonly timeoutMilliseconds: number;
}

export const BRIDGE_ROUTES = {
  capabilities: { method: 'GET', path: '/v1/capabilities', timeoutMilliseconds: 3_000 },
  ping: { method: 'GET', path: '/v1/ping', timeoutMilliseconds: 3_000 },
  serverStatus: { method: 'POST', path: '/v1/server-status', timeoutMilliseconds: 3_000 },
  countRegionBlockStates: {
    method: 'POST',
    path: '/v1/count-region-block-states',
    timeoutMilliseconds: 30_000,
  },
  getBlocks: { method: 'POST', path: '/v1/get-blocks', timeoutMilliseconds: 30_000 },
  scanOrthographicView: {
    method: 'POST',
    path: '/v1/scan-orthographic-view',
    timeoutMilliseconds: 30_000,
  },
  getPlayerContext: { method: 'POST', path: '/v1/get-player-context', timeoutMilliseconds: 30_000 },
  getPerspectiveView: { method: 'POST', path: '/v1/get-perspective-view', timeoutMilliseconds: 30_000 },
  replaceRegionBlocks: { method: 'POST', path: '/v1/replace-region-blocks', timeoutMilliseconds: 300_000 },
  setBlocks: { method: 'POST', path: '/v1/set-blocks', timeoutMilliseconds: 300_000 },
  getEditHistory: { method: 'POST', path: '/v1/get-edit-history', timeoutMilliseconds: 3_000 },
  undoEdits: { method: 'POST', path: '/v1/undo-edits', timeoutMilliseconds: 300_000 },
  runMinecraftCommands: {
    method: 'POST',
    path: '/v1/run-minecraft-commands',
    timeoutMilliseconds: 120_000,
  },
} as const satisfies Record<string, BridgeRoute>;

type GeneratedBridgeOperationId = components['schemas']['BridgeOperationId'];

export const BRIDGE_OPERATION_IDS = [
  'pingServer',
  'getServerStatus',
  'countRegionBlockStates',
  'getBlocks',
  'scanOrthographicView',
  'getPlayerContext',
  'getPerspectiveView',
  'replaceRegionBlocks',
  'setBlocks',
  'getEditHistory',
  'undoEdits',
  'runMinecraftCommands',
] as const satisfies readonly GeneratedBridgeOperationId[];

export type BridgeOperationId = (typeof BRIDGE_OPERATION_IDS)[number];

export const BridgeCapabilitiesSchema: z.ZodType<components['schemas']['CapabilitiesResponse']> = z
  .object({
    operations: z
      .array(z.enum(BRIDGE_OPERATION_IDS))
      .refine((operations) => new Set(operations).size === operations.length, 'Operation IDs must be unique.'),
  })
  .strict();

export type BridgeCapabilities = z.infer<typeof BridgeCapabilitiesSchema>;

export const BRIDGE_ERROR_CODES = [
  'bridge_busy',
  'change_limit_exceeded',
  'edit_not_found',
  'edit_not_latest',
  'history_capacity_exceeded',
  'internal_error',
  'invalid_request',
  'method_not_allowed',
  'operation_disabled',
  'player_not_found',
  'player_unavailable',
  'region_too_large',
  'result_too_large',
  'route_not_found',
  'server_unavailable',
  'unauthorized',
  'unhealthy',
  'world_busy',
  'world_not_found',
  'world_unavailable',
] as const satisfies readonly components['schemas']['BridgeErrorCode'][];

const MessageSchema = z.string().min(1);
const NonBlankStringSchema = z
  .string()
  .min(1)
  .refine((value) => value.trim().length > 0);
const PlayerSelectorSchema = NonBlankStringSchema.max(36);
const JsonSafeIntegerSchema = z.number().int().min(Number.MIN_SAFE_INTEGER).max(Number.MAX_SAFE_INTEGER);
const PositiveJsonSafeIntegerSchema = JsonSafeIntegerSchema.positive();
const SignedInt32Schema = JsonSafeIntegerSchema.min(-2_147_483_648).max(2_147_483_647);
const PositiveInt32Schema = SignedInt32Schema.positive();
const FieldSchema = NonBlankStringSchema;

function uniqueStrings(values: readonly string[]): boolean {
  return new Set(values).size === values.length;
}

function reason<const Reason extends string>(value: Reason) {
  return z.object({ reason: z.literal(value) }).strict();
}

function reasonWith<const Reason extends string, const Shape extends z.ZodRawShape>(value: Reason, shape: Shape) {
  return z.object({ reason: z.literal(value), ...shape }).strict();
}

function errorWithDetails<const Code extends (typeof BRIDGE_ERROR_CODES)[number], Details extends z.ZodType>(
  code: Code,
  details: Details,
) {
  return z.object({ code: z.literal(code), message: MessageSchema, details }).strict();
}

const InvalidRequestDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('unsupported_media_type', { expected: z.literal('application/json') }),
  reasonWith('body_too_large', { maximumBytes: PositiveInt32Schema }),
  reason('malformed_json'),
  reasonWith('missing', { field: FieldSchema }),
  reasonWith('invalid_value', { field: FieldSchema }),
  reasonWith('unsupported_value', {
    target: NonBlankStringSchema,
    allowedValues: z.array(NonBlankStringSchema).min(1).refine(uniqueStrings, 'Allowed values must be distinct.'),
  }),
  reasonWith('duplicate', { field: FieldSchema }),
  reasonWith('unknown_fields', { field: FieldSchema }),
  reasonWith('out_of_range', {
    target: NonBlankStringSchema,
    value: JsonSafeIntegerSchema,
    minimum: JsonSafeIntegerSchema,
    maximum: JsonSafeIntegerSchema,
  }),
  reasonWith('too_many_items', {
    fields: z.array(NonBlankStringSchema).min(1).refine(uniqueStrings, 'Fields must be distinct.'),
    maximum: PositiveInt32Schema,
  }),
  reasonWith('palette_weights_mixed', { field: FieldSchema }),
  reasonWith('palette_weight_total', {
    field: FieldSchema,
    requested: PositiveJsonSafeIntegerSchema.refine(
      (requested) => requested !== 100,
      'Requested palette weight total must differ from 100.',
    ),
    required: z.literal(100),
  }),
]);

const RegionTooLargeDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('volume', {
    dimensions: z
      .object({
        x: PositiveJsonSafeIntegerSchema,
        y: PositiveJsonSafeIntegerSchema,
        z: PositiveJsonSafeIntegerSchema,
      })
      .strict(),
    maximum: PositiveInt32Schema,
  }),
  reasonWith('touched_chunks', {
    minimumRequired: PositiveJsonSafeIntegerSchema,
    maximum: PositiveInt32Schema,
  }),
  reasonWith('perspective_chunks', { requested: PositiveInt32Schema, maximum: PositiveInt32Schema }),
  reasonWith('block_count', {
    minimumRequired: PositiveJsonSafeIntegerSchema,
    maximum: PositiveInt32Schema,
  }),
]);

const ResultTooLargeDetailsSchema = z
  .object({
    reason: z.enum(['structure_entries', 'palettes', 'perspective_rays', 'perspective_ray_distance']),
    minimumRequired: PositiveJsonSafeIntegerSchema,
    maximum: PositiveInt32Schema,
  })
  .strict();

const HistoryCapacityDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('entries_per_world', { maximum: PositiveJsonSafeIntegerSchema }),
  reasonWith('entries_total', { maximum: PositiveJsonSafeIntegerSchema }),
  reasonWith('retained_changed_blocks', { maximum: PositiveJsonSafeIntegerSchema }),
]);

const ServerUnavailableDetailsSchema = z.discriminatedUnion('reason', [
  reason('dependency_unavailable'),
  reason('paper_unavailable'),
  reasonWith('inspection_busy', { maximumConcurrentInspections: PositiveInt32Schema }),
]);

const UnhealthyDetailsSchema = z
  .object({
    reason: z.enum([
      'plugin_disabled',
      'dependency_unavailable',
      'no_loaded_worlds',
      'health_check_failed',
      'paper_unavailable',
    ]),
  })
  .strict();

const WorldBusyDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('operation_in_progress', { world: NonBlankStringSchema }),
  reasonWith('recovery_required', { world: NonBlankStringSchema, newestEditId: z.uuidv4() }),
]);

const ChunkSchema = z.object({ x: SignedInt32Schema, z: SignedInt32Schema }).strict();
const WorldUnavailableDetailsSchema = z.discriminatedUnion('reason', [
  reason('stopping'),
  reason('interrupted'),
  reason('paper_unavailable'),
  reasonWith('world_unloaded', { world: NonBlankStringSchema }),
  reasonWith('chunk_unloaded', { world: NonBlankStringSchema, chunk: ChunkSchema }),
  reasonWith('chunk_load_failed', { world: NonBlankStringSchema, chunk: ChunkSchema }),
]);
const MutationWorldUnavailableDetailsSchema = z.union([
  WorldUnavailableDetailsSchema,
  z.discriminatedUnion('reason', [reason('operation_failed'), reason('rollback_failed'), reason('rolled_back')]),
]);

const PlayerStateFieldSchema = z.enum([
  'feetPosition.x',
  'feetPosition.y',
  'feetPosition.z',
  'eyePosition.x',
  'eyePosition.y',
  'eyePosition.z',
  'rotation.yaw',
  'rotation.pitch',
  'vitals.health',
  'vitals.maxHealth',
  'vitals.absorptionAmount',
  'vitals.saturation',
  'vitals.exhaustion',
  'vitals.experienceProgress',
  'movement.velocity.x',
  'movement.velocity.y',
  'movement.velocity.z',
  'movement.fallDistance',
]);

const PlayerPositionFieldSchema = z.enum([
  'feetPosition.x',
  'feetPosition.y',
  'feetPosition.z',
  'eyePosition.x',
  'eyePosition.y',
  'eyePosition.z',
  'perspectiveEndpoint.x',
  'perspectiveEndpoint.y',
  'perspectiveEndpoint.z',
]);

const PlayerUnavailableDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('spectating_entity', { player: PlayerSelectorSchema }),
  reasonWith('non_finite_state', { player: PlayerSelectorSchema, field: PlayerStateFieldSchema }),
  reasonWith('position_out_of_range', { player: PlayerSelectorSchema, field: PlayerPositionFieldSchema }),
]);

const InternalErrorSchema = z.object({ code: z.literal('internal_error'), message: MessageSchema }).strict();
const MutationInternalErrorSchema = z
  .object({ code: z.literal('internal_error'), message: MessageSchema, editId: z.uuidv4() })
  .strict();
const WorldUnavailableErrorSchema = z
  .object({ code: z.literal('world_unavailable'), message: MessageSchema, details: WorldUnavailableDetailsSchema })
  .strict();
const MutationWorldUnavailableErrorSchema = z
  .object({
    code: z.literal('world_unavailable'),
    message: MessageSchema,
    details: MutationWorldUnavailableDetailsSchema,
    editId: z.uuidv4(),
  })
  .strict();

export const MutationBridgeErrorSchema = z.union([MutationInternalErrorSchema, MutationWorldUnavailableErrorSchema]);

export const BridgeErrorSchema = z.union([
  errorWithDetails('bridge_busy', z.object({ maximumConcurrentRequests: PositiveInt32Schema }).strict()),
  errorWithDetails('change_limit_exceeded', z.object({ maximum: PositiveInt32Schema }).strict()),
  errorWithDetails('edit_not_found', z.object({ world: NonBlankStringSchema, requestedEditId: z.uuidv4() }).strict()),
  errorWithDetails(
    'edit_not_latest',
    z.object({ world: NonBlankStringSchema, requestedEditId: z.uuidv4(), newestEditId: z.uuidv4() }).strict(),
  ),
  errorWithDetails('history_capacity_exceeded', HistoryCapacityDetailsSchema),
  InternalErrorSchema,
  MutationInternalErrorSchema,
  errorWithDetails('invalid_request', InvalidRequestDetailsSchema),
  errorWithDetails('method_not_allowed', z.object({ allowedMethod: z.enum(['GET', 'POST']) }).strict()),
  errorWithDetails('operation_disabled', z.object({ operationId: z.enum(BRIDGE_OPERATION_IDS) }).strict()),
  errorWithDetails('player_not_found', z.object({ player: PlayerSelectorSchema }).strict()),
  errorWithDetails('player_unavailable', PlayerUnavailableDetailsSchema),
  errorWithDetails('region_too_large', RegionTooLargeDetailsSchema),
  errorWithDetails('result_too_large', ResultTooLargeDetailsSchema),
  errorWithDetails('route_not_found', reason('route_not_found')),
  errorWithDetails('server_unavailable', ServerUnavailableDetailsSchema),
  errorWithDetails('unauthorized', reason('authentication_failed')),
  errorWithDetails('unhealthy', UnhealthyDetailsSchema),
  errorWithDetails('world_busy', WorldBusyDetailsSchema),
  errorWithDetails('world_not_found', z.object({ world: NonBlankStringSchema }).strict()),
  WorldUnavailableErrorSchema,
  MutationWorldUnavailableErrorSchema,
]);

export type BridgeError = z.infer<typeof BridgeErrorSchema>;

export const BridgeErrorResponseSchema = z.object({ error: BridgeErrorSchema }).strict();
