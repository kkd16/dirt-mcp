const DEFAULT_BRIDGE_URL = 'http://127.0.0.1:8765';

export interface BridgeConfig {
  readonly origin: string;
  readonly token: string;
}

export function readBridgeConfig(environment: Readonly<Record<string, string | undefined>>): BridgeConfig {
  const token = environment.DIRT_MCP_BRIDGE_TOKEN;
  if (token === undefined || token.length === 0) {
    throw new Error('DIRT_MCP_BRIDGE_TOKEN is required');
  }

  const rawUrl = environment.DIRT_MCP_BRIDGE_URL ?? DEFAULT_BRIDGE_URL;
  if (!/^http:\/\/127\.0\.0\.1(?::[1-9]\d{0,4})?\/?$/.test(rawUrl)) {
    throw new Error('DIRT_MCP_BRIDGE_URL must be an HTTP 127.0.0.1 origin');
  }

  try {
    return { origin: new URL(rawUrl).origin, token };
  } catch {
    throw new Error('DIRT_MCP_BRIDGE_URL must be an HTTP 127.0.0.1 origin');
  }
}
