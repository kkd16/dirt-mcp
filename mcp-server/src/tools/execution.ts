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
  let resultFields: LogFields = {};
  let success = false;
  try {
    const result = await call(callId);
    success = result.isError !== true;
    if (success) resultFields = successfulResultLogFields(result);
    return result;
  } catch (error: unknown) {
    failure = toolFailure(error);
    if (!(error instanceof ToolFailure)) {
      callLogger.error('tool.unexpected_failure', 'Tool call failed unexpectedly.', safeErrorFields(error));
    }
    const structuredContent = ToolFailureResultSchema.parse({
      callId,
      error: failure.data,
    });
    return {
      content: [{ type: 'text', text: `${details.failureContext}: ${failure.message}` }],
      structuredContent,
      isError: true,
    };
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
      ...resultFields,
      ...failureFields,
    };
    const level = failure === undefined ? 'info' : toolFailureLogLevel(failure);
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

function successfulResultLogFields(result: CallToolResult): LogFields {
  const content = objectValue(result.structuredContent);
  if (content === undefined) return {};

  const edit = objectValue(propertyValue(content, 'edit'));
  const editId = stringValue(propertyValue(edit, 'editId'));
  let outcome = stringValue(propertyValue(content, 'outcome'));
  let changedBlockCount = integerValue(propertyValue(content, 'changedBlockCount'));
  if (edit !== undefined && stringValue(propertyValue(content, 'undoCallId')) !== undefined) {
    outcome = 'undone';
    changedBlockCount = integerValue(propertyValue(edit, 'changedBlockCount'));
  }

  let resultCount: number | undefined;
  const edits = propertyValue(content, 'edits');
  if (Array.isArray(edits)) {
    resultCount = edits.length;
  } else {
    const blockStateCounts = objectValue(propertyValue(content, 'blockStateCounts'));
    resultCount =
      blockStateCounts === undefined
        ? (integerValue(propertyValue(content, 'matchedBlockCount')) ??
          integerValue(propertyValue(content, 'visibleBlockCount')))
        : Object.keys(blockStateCounts).length;
  }

  return {
    ...(editId === undefined ? {} : { edit_id: editId }),
    ...(outcome === undefined ? {} : { outcome }),
    ...(changedBlockCount === undefined ? {} : { changed_block_count: changedBlockCount }),
    ...(resultCount === undefined ? {} : { result_count: resultCount }),
  };
}

function objectValue(value: unknown): object | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value : undefined;
}

function propertyValue(value: object | undefined, property: string): unknown {
  return value === undefined ? undefined : Reflect.get(value, property);
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function integerValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) ? value : undefined;
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
