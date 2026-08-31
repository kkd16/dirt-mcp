import { createHash, randomUUID } from 'node:crypto';
import { CLIENT_INFO_META_KEY, type AuthInfo, type CallToolResult } from '@modelcontextprotocol/server';
import { ToolFailure, ToolFailureResultSchema, toolFailureLogLevel } from '../bridge/errors.ts';
import { safeErrorFields, type DirtLogger, type LogFields } from '../logging.ts';

const INTERNAL_ERROR_MESSAGE = 'Dirt MCP encountered an unexpected internal error.';
interface ToolExecutionContext {
  readonly mcpReq: { readonly envelope?: object };
  readonly http?: { readonly authInfo?: Pick<AuthInfo, 'clientId' | 'extra'> };
}
interface ToolCallDetails {
  readonly context: ToolExecutionContext;
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
    client: clientLabel(details.context),
    ...authenticationLogContext(details.context),
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

function clientLabel(context: ToolExecutionContext): string {
  const envelope = context.mcpReq.envelope;
  const current =
    envelope !== undefined && CLIENT_INFO_META_KEY in envelope ? envelope[CLIENT_INFO_META_KEY] : undefined;
  if (typeof current === 'object' && current !== null) {
    const name = 'name' in current ? current.name : undefined;
    const version = 'version' in current ? current.version : undefined;
    if (typeof name === 'string' && typeof version === 'string') {
      return `${name}/${version}`;
    }
  }
  return 'unknown';
}

function authenticationLogContext(context: ToolExecutionContext): LogFields {
  const authInfo = context.http?.authInfo;
  const userId = authInfo?.extra?.userId;
  return {
    ...(typeof userId === 'string' ? { user_id: userId } : {}),
    ...(authInfo?.clientId === undefined ? {} : { client_id_fingerprint: fingerprint(authInfo.clientId) }),
  };
}

function fingerprint(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}
