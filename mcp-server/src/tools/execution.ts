import { randomUUID } from 'node:crypto';
import { CLIENT_INFO_META_KEY, type CallToolResult, type ServerContext } from '@modelcontextprotocol/server';
import { ToolFailure, ToolFailureResultSchema, toolFailureLogLevel } from '../bridge/errors.ts';
import { safeErrorFields, type DirtLogger, type LogFields } from '../logging.ts';

const INTERNAL_ERROR_MESSAGE = 'Dirt MCP encountered an unexpected internal error.';
interface ToolCallDetails {
  readonly context: ServerContext;
  readonly failureContext: string;
  readonly operation: string;
  readonly world?: string;
}

export function successResult(structuredContent: Record<string, unknown>, summary: string): CallToolResult {
  return { content: [{ type: 'text', text: summary }], structuredContent };
}

export async function executeToolCall(
  logger: DirtLogger,
  details: ToolCallDetails,
  call: (callId: string) => Promise<CallToolResult>,
): Promise<CallToolResult> {
  const callId = randomUUID();
  const started = performance.now();
  const callLogger = logger.child({
    component: 'tool',
    operation: details.operation,
    call_id: callId,
    request_id: details.context.mcpReq.id,
    client: clientLabel(details.context),
    ...(details.world === undefined ? {} : { world: details.world }),
  });
  let failure: ToolFailure | undefined;
  let success = false;
  try {
    const result = await call(callId);
    success = result.isError !== true;
    return result;
  } catch (error: unknown) {
    failure = toolFailure(error);
    if (!(error instanceof ToolFailure)) {
      callLogger.error('tool.unexpected_failure', 'Tool call failed unexpectedly.', safeErrorFields(error));
    }
    const baseStructuredContent = ToolFailureResultSchema.parse({
      callId,
      error: failure.data,
    });
    const result: CallToolResult = {
      content: [{ type: 'text', text: `${details.failureContext}: ${failure.message}` }],
      structuredContent: baseStructuredContent,
      isError: true,
    };
    return result;
  } finally {
    const failureFields: LogFields =
      failure === undefined
        ? {}
        : {
            error_code: failure.code,
            ...(failure.editId === undefined ? {} : { edit_id: failure.editId }),
          };
    const completionFields: LogFields = {
      success,
      duration_ms: Math.max(0, Math.round(performance.now() - started)),
      ...failureFields,
    };
    const level = failure === undefined ? (success ? 'info' : 'warning') : toolFailureLogLevel(failure);
    switch (level) {
      case 'error':
        callLogger.error('tool.completed', 'Tool call completed.', completionFields);
        break;
      case 'warning':
        callLogger.warning('tool.completed', 'Tool call completed.', completionFields);
        break;
      case 'info':
        callLogger.info('tool.completed', 'Tool call completed.', completionFields);
        break;
    }
  }
}

function toolFailure(error: unknown): ToolFailure {
  return error instanceof ToolFailure
    ? error
    : new ToolFailure({ code: 'dirt_internal_error', message: INTERNAL_ERROR_MESSAGE });
}

function clientLabel(context: ServerContext): string {
  const envelope = context.mcpReq.envelope as Record<string, unknown> | undefined;
  const current = envelope?.[CLIENT_INFO_META_KEY];
  if (typeof current === 'object' && current !== null) {
    const { name, version } = current as { name?: unknown; version?: unknown };
    if (typeof name === 'string' && typeof version === 'string') {
      return `${name}/${version}`;
    }
  }
  return 'unknown';
}
