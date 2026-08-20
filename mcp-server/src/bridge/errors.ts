import type { BRIDGE_ERROR_CODES } from './contract.ts';

export type ToolFailureCode =
  | (typeof BRIDGE_ERROR_CODES)[number]
  | 'bridge_unavailable'
  | 'bridge_unauthorized'
  | 'bridge_http_error'
  | 'bridge_invalid_response'
  | 'dirt_internal_error';

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
  readonly code: ToolFailureCode;
  readonly editId?: string;

  constructor(code: ToolFailureCode, message: string, editId?: string) {
    super(message);
    this.code = code;
    if (editId !== undefined) this.editId = editId;
    this.name = 'ToolFailure';
  }
}

export function toolFailureLogLevel(failure: ToolFailure): ToolFailureLogLevel {
  if (failure.editId !== undefined) return 'warning';
  if (ERROR_FAILURE_CODES.has(failure.code)) return 'error';
  return WARNING_FAILURE_CODES.has(failure.code) ? 'warning' : 'info';
}
