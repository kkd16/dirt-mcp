const DEFAULT_BRIDGE_URL = 'http://127.0.0.1:8765';
const MINIMUM_TOKEN_BYTES = 32;

export interface BridgeConfig {
  readonly origin: string;
  readonly token: string;
}

type BridgeConfigurationErrorCode = 'bridge_token_required' | 'bridge_token_too_short' | 'bridge_url_invalid';

export class BridgeConfigurationError extends Error {
  readonly code: BridgeConfigurationErrorCode;

  constructor(code: BridgeConfigurationErrorCode, message: string) {
    super(message);
    this.code = code;
    this.name = 'BridgeConfigurationError';
  }
}

export function readBridgeConfig(environment: Readonly<Record<string, string | undefined>>): BridgeConfig {
  const token = environment.DIRT_MCP_BRIDGE_TOKEN;
  if (token === undefined || token.trim().length === 0) {
    throw new BridgeConfigurationError('bridge_token_required', 'DIRT_MCP_BRIDGE_TOKEN is required');
  }
  if (Buffer.byteLength(token, 'utf8') < MINIMUM_TOKEN_BYTES) {
    throw new BridgeConfigurationError(
      'bridge_token_too_short',
      `DIRT_MCP_BRIDGE_TOKEN must contain at least ${MINIMUM_TOKEN_BYTES} UTF-8 bytes`,
    );
  }

  const rawUrl = environment.DIRT_MCP_BRIDGE_URL ?? DEFAULT_BRIDGE_URL;
  if (!/^http:\/\/127\.0\.0\.1(?::[1-9]\d{0,4})?\/?$/.test(rawUrl)) {
    throw new BridgeConfigurationError('bridge_url_invalid', 'DIRT_MCP_BRIDGE_URL must be an HTTP 127.0.0.1 origin');
  }

  try {
    return { origin: new URL(rawUrl).origin, token };
  } catch {
    throw new BridgeConfigurationError('bridge_url_invalid', 'DIRT_MCP_BRIDGE_URL must be an HTTP 127.0.0.1 origin');
  }
}
