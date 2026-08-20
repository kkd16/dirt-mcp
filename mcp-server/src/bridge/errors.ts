import * as z from 'zod/v4';
import { BridgeErrorSchema } from './contract.ts';

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

const LocalToolFailureSchema = z.discriminatedUnion('code', [
  localFailureWithDetails('bridge_unavailable', z.object({ reason: z.enum(['timeout', 'request_failed']) }).strict()),
  localFailureWithDetails('bridge_unauthorized', z.object({ reason: z.literal('authentication_failed') }).strict()),
  localFailureWithDetails('bridge_http_error', z.object({ status: z.number().int().min(100).max(599) }).strict()),
  localFailure('bridge_invalid_response'),
  localFailure('dirt_internal_error'),
]);

export const ToolFailureDataSchema = z.discriminatedUnion('code', [BridgeErrorSchema, LocalToolFailureSchema]);
export type ToolFailureData = z.infer<typeof ToolFailureDataSchema>;
type ToolFailureCode = ToolFailureData['code'];

const ToolFailureWireErrorSchema = z.preprocess(
  (value) => {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return value;
    const callId = Reflect.get(value, 'callId');
    const data = Object.fromEntries(Object.entries(value).filter(([key]) => key !== 'callId'));
    return { callId, data };
  },
  z
    .object({ callId: z.uuidv4(), data: ToolFailureDataSchema })
    .strict()
    .transform(({ callId, data }) => ({ ...data, callId })),
);

export const ToolFailureResultSchema = z
  .object({ error: ToolFailureWireErrorSchema })
  .strict()
  .describe('Structured MCP tool execution failure.');

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

  constructor(data: ToolFailureData) {
    const checked = ToolFailureDataSchema.parse(data);
    super(checked.message);
    this.data = checked;
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
