import * as z from 'zod/v4';

export interface BridgeRoute {
  readonly method: 'GET' | 'POST';
  readonly path: `/v1/${string}`;
  readonly timeoutMilliseconds: number;
  readonly salvageEditId?: true;
}

export const BRIDGE_ROUTES = {
  ping: { method: 'GET', path: '/v1/ping', timeoutMilliseconds: 3_000 },
  serverStatus: { method: 'GET', path: '/v1/server-status', timeoutMilliseconds: 3_000 },
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
  getPlayerContext: {
    method: 'POST',
    path: '/v1/get-player-context',
    timeoutMilliseconds: 30_000,
  },
  getPerspectiveView: {
    method: 'POST',
    path: '/v1/get-perspective-view',
    timeoutMilliseconds: 30_000,
  },
  replaceRegionBlocks: {
    method: 'POST',
    path: '/v1/replace-region-blocks',
    timeoutMilliseconds: 120_000,
    salvageEditId: true,
  },
  setBlocks: { method: 'POST', path: '/v1/set-blocks', timeoutMilliseconds: 120_000, salvageEditId: true },
  getEditHistory: {
    method: 'POST',
    path: '/v1/get-edit-history',
    timeoutMilliseconds: 3_000,
  },
  undoEdit: {
    method: 'POST',
    path: '/v1/undo-edit',
    timeoutMilliseconds: 120_000,
    salvageEditId: true,
  },
  runMinecraftCommands: {
    method: 'POST',
    path: '/v1/run-minecraft-commands',
    timeoutMilliseconds: 120_000,
  },
} as const satisfies Record<string, BridgeRoute>;

export const BRIDGE_ERROR_CODES = [
  'bridge_busy',
  'change_limit_exceeded',
  'edit_not_found',
  'edit_not_latest',
  'history_capacity_exceeded',
  'internal_error',
  'invalid_request',
  'method_not_allowed',
  'not_found',
  'player_not_found',
  'player_unavailable',
  'region_too_large',
  'result_too_large',
  'server_unavailable',
  'unauthorized',
  'unhealthy',
  'world_busy',
  'world_not_found',
  'world_unavailable',
] as const;

const ErrorMessageSchema = z.string().min(1).describe('Human-readable explanation.');
const EditIdSchema = z
  .uuidv4()
  .optional()
  .describe('Edit transaction associated with a failure that may need reconciliation against retained history.');
const NonBlankStringSchema = z
  .string()
  .min(1)
  .refine((value) => value.trim().length > 0, 'Must contain a non-whitespace character.');
export const PlayerSelectorSchema = NonBlankStringSchema.max(36);
const FieldSchema = NonBlankStringSchema.describe('Request field associated with the failure.');
const TargetSchema = NonBlankStringSchema.describe(
  'Request input or derived operation target associated with the failure.',
);
const UniqueNonBlankStringsSchema = z
  .array(NonBlankStringSchema)
  .min(1)
  .refine((values) => new Set(values).size === values.length, 'Values must not contain duplicates.');
const FieldsSchema = UniqueNonBlankStringsSchema.describe(
  'Non-empty unique request fields associated with an aggregate item limit.',
);
const AllowedValuesSchema = UniqueNonBlankStringsSchema.describe('Non-empty unique list of supported values.');
const JsonSafeIntegerSchema = z.number().int().min(Number.MIN_SAFE_INTEGER).max(Number.MAX_SAFE_INTEGER);
const PositiveJsonSafeIntegerSchema = JsonSafeIntegerSchema.positive();
const SignedInt32Schema = JsonSafeIntegerSchema.min(-2_147_483_648).max(2_147_483_647);
const PositiveInt32Schema = SignedInt32Schema.positive();
const ChunkSchema = z
  .object({
    x: SignedInt32Schema.describe('Chunk X coordinate.'),
    z: SignedInt32Schema.describe('Chunk Z coordinate.'),
  })
  .strict();

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
  return z
    .object({
      code: z.literal(code),
      message: ErrorMessageSchema,
      details,
      editId: EditIdSchema,
    })
    .strict();
}

const InvalidRequestDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('unsupported_media_type', {
    expected: z.literal('application/json').describe('Required request media type.'),
  }),
  reasonWith('body_too_large', {
    maximumBytes: PositiveInt32Schema.describe('Maximum accepted request-body size in bytes.'),
  }),
  reason('malformed_json'),
  reasonWith('missing', { field: FieldSchema }),
  reasonWith('invalid_value', { field: FieldSchema }),
  reasonWith('unsupported_value', {
    target: TargetSchema,
    allowedValues: AllowedValuesSchema,
  }),
  reasonWith('duplicate', { field: FieldSchema }),
  reasonWith('unknown_fields', { field: FieldSchema }),
  reasonWith('out_of_range', {
    target: TargetSchema,
    value: JsonSafeIntegerSchema.describe('Rejected value.'),
    minimum: JsonSafeIntegerSchema.describe('Minimum accepted value.'),
    maximum: JsonSafeIntegerSchema.describe('Maximum accepted value.'),
  })
    .refine((details) => details.minimum <= details.maximum, 'minimum must not exceed maximum.')
    .refine(
      (details) => details.value < details.minimum || details.value > details.maximum,
      'value must be outside the allowed range.',
    ),
  reasonWith('too_many_items', {
    fields: FieldsSchema,
    maximum: PositiveInt32Schema.describe('Maximum accepted item count.'),
  }),
  reasonWith('palette_weights_mixed', { field: FieldSchema }),
  reasonWith('palette_weight_total', {
    field: FieldSchema,
    requested: PositiveJsonSafeIntegerSchema.describe('Rejected palette weight total.'),
    required: z.literal(100).describe('Required palette weight total.'),
  }).refine((details) => details.requested !== details.required, 'requested must differ from required.'),
]);

const RegionTooLargeDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('volume', {
    dimensions: z
      .object({
        x: PositiveJsonSafeIntegerSchema,
        y: PositiveJsonSafeIntegerSchema,
        z: PositiveJsonSafeIntegerSchema,
      })
      .strict()
      .describe('Exact normalized region dimensions in blocks.'),
    maximum: PositiveInt32Schema.describe('Maximum accepted region volume.'),
  }).refine(
    (details) =>
      BigInt(details.dimensions.x) * BigInt(details.dimensions.y) * BigInt(details.dimensions.z) >
      BigInt(details.maximum),
    'dimensions volume must exceed maximum.',
  ),
  reasonWith('touched_chunks', {
    minimumRequired: PositiveJsonSafeIntegerSchema.describe('Known lower bound on the number of touched chunks.'),
    maximum: PositiveInt32Schema.describe('Maximum accepted touched-chunk count.'),
  }).refine((details) => details.minimumRequired > details.maximum, 'minimumRequired must exceed maximum.'),
  reasonWith('perspective_chunks', {
    requested: PositiveInt32Schema.describe(
      'Exact size of the conservative loaded-chunk preflight for the sampled rays.',
    ),
    maximum: PositiveInt32Schema.describe('Maximum accepted perspective-view checked-chunk count.'),
  }).refine((details) => details.requested > details.maximum, 'requested must exceed maximum.'),
  reasonWith('block_count', {
    minimumRequired: PositiveJsonSafeIntegerSchema.describe('Known lower bound on the expanded block count.'),
    maximum: PositiveInt32Schema.describe('Maximum accepted expanded block count.'),
  }).refine((details) => details.minimumRequired > details.maximum, 'minimumRequired must exceed maximum.'),
]);

const ResultTooLargeDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('structure_entries', {
    minimumRequired: PositiveJsonSafeIntegerSchema.describe('Known lower bound on the required placements plus runs.'),
    maximum: PositiveInt32Schema.describe('Maximum accepted result entries.'),
  }).refine((details) => details.minimumRequired > details.maximum, 'minimumRequired must exceed maximum.'),
  reasonWith('palettes', {
    minimumRequired: PositiveJsonSafeIntegerSchema.describe('Known lower bound on the required exact palettes.'),
    maximum: PositiveInt32Schema.describe('Maximum accepted palette count.'),
  }).refine((details) => details.minimumRequired > details.maximum, 'minimumRequired must exceed maximum.'),
  reasonWith('visible_blocks', {
    minimumRequired: PositiveJsonSafeIntegerSchema.describe('Known lower bound on the required result entries.'),
    maximum: PositiveInt32Schema.describe('Maximum accepted result entries.'),
  }).refine((details) => details.minimumRequired > details.maximum, 'minimumRequired must exceed maximum.'),
  reasonWith('perspective_rays', {
    minimumRequired: PositiveJsonSafeIntegerSchema.describe('Requested perspective-ray count.'),
    maximum: PositiveInt32Schema.describe('Maximum accepted perspective-ray count.'),
  }).refine((details) => details.minimumRequired > details.maximum, 'minimumRequired must exceed maximum.'),
  reasonWith('perspective_ray_distance', {
    minimumRequired: PositiveJsonSafeIntegerSchema.describe('Requested perspective ray-distance budget.'),
    maximum: PositiveInt32Schema.describe('Maximum accepted perspective ray-distance budget.'),
  }).refine((details) => details.minimumRequired > details.maximum, 'minimumRequired must exceed maximum.'),
]);

const HistoryCapacityDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('entries_per_world', {
    maximum: PositiveJsonSafeIntegerSchema.describe('Maximum retained entries for one world.'),
  }),
  reasonWith('entries_total', {
    maximum: PositiveJsonSafeIntegerSchema.describe('Maximum retained entries across all worlds.'),
  }),
  reasonWith('retained_changed_blocks', {
    maximum: PositiveJsonSafeIntegerSchema.describe('Maximum changed blocks across retained edits.'),
  }),
]);

const ServerUnavailableDetailsSchema = z.discriminatedUnion('reason', [
  reason('dependency_unavailable'),
  reason('paper_unavailable'),
  reasonWith('inspection_busy', {
    maximumConcurrentInspections: PositiveInt32Schema.describe('Maximum concurrent inspections.'),
  }),
]);

const UnhealthyDetailsSchema = z.discriminatedUnion('reason', [
  reason('plugin_disabled'),
  reason('dependency_unavailable'),
  reason('no_loaded_worlds'),
  reason('health_check_failed'),
  reason('paper_unavailable'),
]);

const WorldBusyDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('operation_in_progress', { world: NonBlankStringSchema }),
  reasonWith('recovery_required', {
    world: NonBlankStringSchema,
    newestEditId: z.uuidv4(),
  }),
]);

const WorldUnavailableDetailsSchema = z.discriminatedUnion('reason', [
  reason('stopping'),
  reason('interrupted'),
  reason('paper_unavailable'),
  reason('operation_failed'),
  reason('rollback_failed'),
  reason('rolled_back'),
  reasonWith('world_unloaded', { world: NonBlankStringSchema }),
  reasonWith('chunk_unloaded', { world: NonBlankStringSchema, chunk: ChunkSchema }),
  reasonWith('chunk_load_failed', { world: NonBlankStringSchema, chunk: ChunkSchema }),
]);

const PlayerUnavailableDetailsSchema = z.discriminatedUnion('reason', [
  reasonWith('spectating_entity', {
    player: PlayerSelectorSchema.describe('Requested online player selector.'),
  }),
  reasonWith('non_finite_state', {
    player: PlayerSelectorSchema.describe('Requested online player selector.'),
    field: z.enum([
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
    ]),
  }),
  reasonWith('position_out_of_range', {
    player: PlayerSelectorSchema.describe('Requested online player selector.'),
    field: z.enum([
      'feetPosition.x',
      'feetPosition.y',
      'feetPosition.z',
      'eyePosition.x',
      'eyePosition.y',
      'eyePosition.z',
      'perspectiveEndpoint.x',
      'perspectiveEndpoint.y',
      'perspectiveEndpoint.z',
    ]),
  }),
]);

export const BridgeErrorSchema = z.discriminatedUnion('code', [
  errorWithDetails(
    'bridge_busy',
    z
      .object({
        maximumConcurrentRequests: PositiveInt32Schema.describe('Maximum concurrent authenticated bridge requests.'),
      })
      .strict(),
  ),
  errorWithDetails(
    'change_limit_exceeded',
    z.object({ maximum: PositiveInt32Schema.describe('Maximum changed blocks per edit.') }).strict(),
  ),
  errorWithDetails('edit_not_found', z.object({ world: NonBlankStringSchema, requestedEditId: z.uuidv4() }).strict()),
  errorWithDetails(
    'edit_not_latest',
    z
      .object({
        world: NonBlankStringSchema,
        requestedEditId: z.uuidv4(),
        newestEditId: z.uuidv4(),
      })
      .strict()
      .refine(
        (details) => details.requestedEditId.toLowerCase() !== details.newestEditId.toLowerCase(),
        'requestedEditId must differ from newestEditId.',
      ),
  ),
  errorWithDetails('history_capacity_exceeded', HistoryCapacityDetailsSchema),
  z
    .object({
      code: z.literal('internal_error'),
      message: ErrorMessageSchema,
      editId: EditIdSchema,
    })
    .strict(),
  errorWithDetails('invalid_request', InvalidRequestDetailsSchema),
  errorWithDetails('method_not_allowed', z.object({ allowedMethod: z.enum(['GET', 'POST']) }).strict()),
  errorWithDetails('not_found', reason('route_not_found')),
  errorWithDetails(
    'player_not_found',
    z
      .object({ player: PlayerSelectorSchema.describe('Requested exact online player name or canonical UUID.') })
      .strict(),
  ),
  errorWithDetails('player_unavailable', PlayerUnavailableDetailsSchema),
  errorWithDetails('region_too_large', RegionTooLargeDetailsSchema),
  errorWithDetails('result_too_large', ResultTooLargeDetailsSchema),
  errorWithDetails('server_unavailable', ServerUnavailableDetailsSchema),
  errorWithDetails('unauthorized', reason('authentication_failed')),
  errorWithDetails('unhealthy', UnhealthyDetailsSchema),
  errorWithDetails('world_busy', WorldBusyDetailsSchema),
  errorWithDetails('world_not_found', z.object({ world: NonBlankStringSchema }).strict()),
  errorWithDetails('world_unavailable', WorldUnavailableDetailsSchema),
]);

export type BridgeError = z.infer<typeof BridgeErrorSchema>;

export const BridgeErrorResponseSchema = z
  .object({
    error: BridgeErrorSchema,
  })
  .strict()
  .describe('Structured Dirt error.');
