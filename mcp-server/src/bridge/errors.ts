import * as z from 'zod/v4';
import { BRIDGE_ERROR_CODES, BridgeErrorSchema } from './contract.ts';

const MessageSchema = z.string().min(1);
const EditIdSchema = z.uuidv4().optional();

function localFailure<const Code extends string>(code: Code) {
  return z
    .object({
      code: z.literal(code),
      message: MessageSchema,
      editId: EditIdSchema,
    })
    .strict();
}

function localFailureWithDetails<const Code extends string, Details extends z.ZodType>(code: Code, details: Details) {
  return z
    .object({
      code: z.literal(code),
      message: MessageSchema,
      details,
      editId: EditIdSchema,
    })
    .strict();
}

const BridgeUnavailableFailureSchema = localFailureWithDetails(
  'bridge_unavailable',
  z.object({ reason: z.enum(['timeout', 'request_failed']) }).strict(),
);
const BridgeUnauthorizedFailureSchema = localFailureWithDetails(
  'bridge_unauthorized',
  z.object({ reason: z.literal('authentication_failed') }).strict(),
);
const BridgeHttpFailureSchema = localFailureWithDetails(
  'bridge_http_error',
  z.object({ status: z.number().int().min(100).max(599) }).strict(),
);

const LocalToolFailureSchema = z.discriminatedUnion('code', [
  BridgeUnavailableFailureSchema,
  BridgeUnauthorizedFailureSchema,
  BridgeHttpFailureSchema,
  localFailure('bridge_invalid_response'),
  localFailure('dirt_internal_error'),
]);

const AdvertisedCorrectableBridgeFailureSchema = z
  .object({
    code: z.enum(BRIDGE_ERROR_CODES).exclude(['internal_error']),
    message: MessageSchema,
    details: z
      .record(z.string(), z.unknown())
      .describe('Code-specific details validated against the full bridge contract before emission.'),
    editId: EditIdSchema,
  })
  .strict();

const AdvertisedInternalFailureSchema = z
  .object({
    code: z.enum(['internal_error', 'bridge_invalid_response', 'dirt_internal_error']),
    message: MessageSchema,
    editId: EditIdSchema,
  })
  .strict();

const AdvertisedProgressFailureDataSchema = z.discriminatedUnion('code', [
  AdvertisedCorrectableBridgeFailureSchema.extend({ editId: z.uuidv4() }),
  z
    .object({
      code: z.literal('internal_error'),
      message: MessageSchema,
      editId: z.uuidv4(),
    })
    .strict(),
]);

const AdvertisedToolFailureDataSchema = z.discriminatedUnion('code', [
  AdvertisedCorrectableBridgeFailureSchema,
  BridgeUnavailableFailureSchema,
  BridgeUnauthorizedFailureSchema,
  BridgeHttpFailureSchema,
  AdvertisedInternalFailureSchema,
]);

const AdvertisedToolFailureResultSchema = z
  .object({
    callId: z.uuidv4(),
    error: AdvertisedToolFailureDataSchema,
  })
  .strict()
  .describe('Compact advertised envelope for a fully validated Dirt tool failure.');

export const ToolFailureDataSchema = z.discriminatedUnion('code', [BridgeErrorSchema, LocalToolFailureSchema]);
export type ToolFailureData = z.infer<typeof ToolFailureDataSchema>;
type ToolFailureCode = ToolFailureData['code'];

export const ToolFailureResultSchema = z
  .object({ callId: z.uuidv4(), error: ToolFailureDataSchema })
  .strict()
  .describe('Structured MCP tool execution failure.');

export function toolOutputSchema(success: z.ZodType, progressShape?: z.ZodRawShape): z.ZodType {
  if (progressShape === undefined) return z.union([success, AdvertisedToolFailureResultSchema]);
  const progressFailure = AdvertisedToolFailureResultSchema.extend({
    ...progressShape,
    error: AdvertisedProgressFailureDataSchema,
  });
  return z.union([success, AdvertisedToolFailureResultSchema, progressFailure]);
}

type ToolFailureLogLevel = 'info' | 'warning' | 'error';

const ERROR_FAILURE_CODES: ReadonlySet<ToolFailureCode> = new Set([
  'bridge_http_error',
  'bridge_invalid_response',
  'dirt_internal_error',
  'internal_error',
  'method_not_allowed',
  'not_found',
]);
const WARNING_FAILURE_CODES: ReadonlySet<ToolFailureCode> = new Set([
  'bridge_busy',
  'bridge_unauthorized',
  'bridge_unavailable',
  'history_capacity_exceeded',
  'server_unavailable',
  'unhealthy',
  'world_unavailable',
]);

export class ToolFailure extends Error {
  readonly data: ToolFailureData;
  readonly progress: Readonly<Record<string, unknown>> | undefined;

  constructor(data: ToolFailureData, progress?: Readonly<Record<string, unknown>>) {
    const checked = ToolFailureDataSchema.parse(data);
    if (progress !== undefined && checked.editId === undefined) {
      throw new TypeError('Tool failure progress requires an editId.');
    }
    super(checked.message);
    this.data = checked;
    this.progress = progress;
    this.name = 'ToolFailure';
  }

  get code(): ToolFailureCode {
    return this.data.code;
  }

  get editId(): string | undefined {
    return this.data.editId;
  }
}

export function toolFailureLogLevel(failure: ToolFailure): ToolFailureLogLevel {
  if (failure.editId !== undefined) return 'warning';
  if (ERROR_FAILURE_CODES.has(failure.code)) return 'error';
  return WARNING_FAILURE_CODES.has(failure.code) ? 'warning' : 'info';
}
