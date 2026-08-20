import type { BRIDGE_ERROR_CODES } from './contract.ts';

export type BridgeErrorCode = (typeof BRIDGE_ERROR_CODES)[number];
export type LocalErrorCode =
  | 'bridge_unavailable'
  | 'bridge_unauthorized'
  | 'bridge_http_error'
  | 'bridge_invalid_response'
  | 'dirt_internal_error';
export type ToolFailureCode = BridgeErrorCode | LocalErrorCode;

export class ToolFailure extends Error {
  readonly code: ToolFailureCode;

  constructor(code: ToolFailureCode, message: string) {
    super(message);
    this.code = code;
    this.name = 'ToolFailure';
  }
}
