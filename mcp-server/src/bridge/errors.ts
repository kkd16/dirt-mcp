import type { BRIDGE_ERROR_CODES } from './contract.ts';

type ToolFailureCode =
  | (typeof BRIDGE_ERROR_CODES)[number]
  | 'bridge_unavailable'
  | 'bridge_unauthorized'
  | 'bridge_http_error'
  | 'bridge_invalid_response'
  | 'dirt_internal_error';

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
