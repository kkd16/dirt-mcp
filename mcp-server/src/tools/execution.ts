import { randomUUID } from 'node:crypto';
import { CLIENT_INFO_META_KEY, type CallToolResult, type ServerContext } from '@modelcontextprotocol/server';
import { ToolFailure } from '../bridge/errors.ts';

const INTERNAL_ERROR_MESSAGE = 'Dirt MCP encountered an unexpected internal error.';

interface ToolCallDetails {
  readonly context: ServerContext;
  readonly failureContext: string;
  readonly tool: string;
  readonly world?: string;
}

export function successResult(structuredContent: Record<string, unknown>, summary: string): CallToolResult {
  return { content: [{ type: 'text', text: summary }], structuredContent };
}

export async function executeToolCall(
  details: ToolCallDetails,
  call: (callId: string) => Promise<CallToolResult>,
): Promise<CallToolResult> {
  const callId = randomUUID();
  const started = performance.now();
  let outcome = 'error';
  try {
    const result = await call(callId);
    outcome = result.isError === true ? 'error' : 'ok';
    return result;
  } catch (error: unknown) {
    const failure = toolFailure(error);
    if (!(error instanceof ToolFailure)) {
      const errorType = error instanceof Error ? error.name : typeof error;
      process.stderr.write(
        `Dirt MCP unexpected_failure tool=${details.tool} call=${callId} error_type=${logValue(errorType)}\n`,
      );
    }
    return {
      content: [{ type: 'text', text: `${details.failureContext}: ${failure.message}` }],
      structuredContent: {
        error: {
          code: failure.code,
          message: failure.message,
          callId,
          ...(failure.editId === undefined ? {} : { editId: failure.editId }),
        },
      },
      isError: true,
    };
  } finally {
    const fields = [
      `tool=${details.tool}`,
      `call=${callId}`,
      `request=${logValue(details.context.mcpReq.id)}`,
      `client=${logValue(clientLabel(details.context))}`,
    ];
    if (details.world !== undefined) fields.push(`world=${logValue(details.world)}`);
    fields.push(`outcome=${outcome}`);
    fields.push(`duration_ms=${Math.max(0, Math.round(performance.now() - started))}`);
    process.stderr.write(`Dirt MCP tool_call ${fields.join(' ')}\n`);
  }
}

function toolFailure(error: unknown): ToolFailure {
  return error instanceof ToolFailure ? error : new ToolFailure('dirt_internal_error', INTERNAL_ERROR_MESSAGE);
}

function logValue(value: string | number): string {
  return JSON.stringify(value).replaceAll('\u2028', '\\u2028').replaceAll('\u2029', '\\u2029');
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
