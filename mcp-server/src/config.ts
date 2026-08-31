import { readFileSync } from 'node:fs';
import { isIP } from 'node:net';
import { resolve } from 'node:path';

const DEFAULT_BRIDGE_URL = 'http://127.0.0.1:8765';
const DEFAULT_WEB_PORT = 3_000;
const SERVICE_TOKEN_PATTERN = /^[0-9a-f]{64}$/u;
const DNS_LABEL_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/u;

export interface BridgeConfig {
  readonly origin: string;
  readonly token: string;
}

export interface AuthConfig {
  readonly authSecret: string;
  readonly databasePath: string;
  readonly publicOrigin: string;
}

export interface RuntimeConfig extends AuthConfig {
  readonly bridge: BridgeConfig;
  readonly controlToken: string;
  readonly port: number;
}

type RuntimeConfigurationErrorCode =
  | 'bridge_token_required'
  | 'bridge_token_invalid'
  | 'bridge_token_file_invalid'
  | 'bridge_url_invalid'
  | 'auth_secret_required'
  | 'auth_secret_invalid'
  | 'auth_secret_file_invalid'
  | 'control_token_required'
  | 'control_token_invalid'
  | 'control_token_file_invalid'
  | 'database_path_required'
  | 'secrets_not_distinct'
  | 'public_origin_invalid'
  | 'public_origin_required'
  | 'web_port_invalid';

type ServiceTokenErrorReason = 'invalid' | 'required';

export class RuntimeConfigurationError extends Error {
  readonly code: RuntimeConfigurationErrorCode;

  constructor(code: RuntimeConfigurationErrorCode, message: string) {
    super(message);
    this.code = code;
    this.name = 'RuntimeConfigurationError';
  }
}

function readBridgeConfig(environment: Readonly<Record<string, string | undefined>>): BridgeConfig {
  const token = readServiceToken(environment, 'DIRT_BRIDGE_TOKEN_FILE', 'bridge');
  const rawUrl = environment.DIRT_BRIDGE_URL ?? DEFAULT_BRIDGE_URL;
  const url = /^http:\/\/127\.0\.0\.1(?::[1-9]\d{0,4})?\/?$/u.test(rawUrl) ? URL.parse(rawUrl) : null;
  if (url === null) {
    throw new RuntimeConfigurationError('bridge_url_invalid', 'DIRT_BRIDGE_URL must be an HTTP 127.0.0.1 origin');
  }
  return { origin: url.origin, token };
}

export function readRuntimeConfig(environment: Readonly<Record<string, string | undefined>>): RuntimeConfig {
  const auth = readAuthConfig(environment);
  const bridge = readBridgeConfig(environment);
  const controlToken = readServiceToken(environment, 'DIRT_CONTROL_TOKEN_FILE', 'control');
  if (bridge.token === controlToken || bridge.token === auth.authSecret || controlToken === auth.authSecret) {
    throw new RuntimeConfigurationError(
      'secrets_not_distinct',
      'Bridge, control, and authentication secrets must be pairwise distinct',
    );
  }
  return {
    ...auth,
    bridge,
    controlToken,
    port: readPort(environment.DIRT_WEB_PORT),
  };
}

export function readAuthConfig(environment: Readonly<Record<string, string | undefined>>): AuthConfig {
  return {
    authSecret: readAuthSecret(environment),
    databasePath: readDatabasePath(environment.DIRT_DATABASE_PATH),
    publicOrigin: readPublicOrigin(environment.DIRT_PUBLIC_ORIGIN),
  };
}

export function readDatabasePath(value: string | undefined): string {
  if (value === undefined || value.trim().length === 0 || value.includes('\0')) {
    throw new RuntimeConfigurationError('database_path_required', 'DIRT_DATABASE_PATH is required');
  }
  return resolve(value);
}

function readServiceToken(
  environment: Readonly<Record<string, string | undefined>>,
  fileVariable: string,
  kind: 'bridge' | 'control',
): string {
  const token = readSecretValue(environment, fileVariable, `${kind}_token_file_invalid`);
  if (token === undefined || token.trim().length === 0) {
    throw configurationError(kind, 'required', `${fileVariable} is required`);
  }
  if (!SERVICE_TOKEN_PATTERN.test(token)) {
    throw configurationError(
      kind,
      'invalid',
      `${fileVariable} must point to a file containing exactly 64 lowercase hexadecimal characters`,
    );
  }
  return token;
}

function configurationError(
  kind: 'bridge' | 'control',
  reason: ServiceTokenErrorReason,
  message: string,
): RuntimeConfigurationError {
  return new RuntimeConfigurationError(`${kind}_token_${reason}`, message);
}

function readAuthSecret(environment: Readonly<Record<string, string | undefined>>): string {
  const secret = readSecretValue(environment, 'DIRT_AUTH_SECRET_FILE', 'auth_secret_file_invalid');
  if (secret === undefined || secret.length === 0) {
    throw new RuntimeConfigurationError('auth_secret_required', 'DIRT_AUTH_SECRET_FILE is required');
  }
  if (secret.length < 32 || secret.length > 4_096 || /\s/u.test(secret)) {
    throw new RuntimeConfigurationError(
      'auth_secret_invalid',
      'DIRT_AUTH_SECRET_FILE must point to a file containing 32 to 4096 non-whitespace characters',
    );
  }
  return secret;
}

function readPublicOrigin(value: string | undefined): string {
  if (value === undefined || value.trim().length === 0) {
    throw new RuntimeConfigurationError('public_origin_required', 'DIRT_PUBLIC_ORIGIN is required');
  }
  const url = URL.parse(value);
  if (url === null) throw invalidPublicOrigin();
  const localDevelopment = url.protocol === 'http:' && url.hostname === 'localhost';
  const hostIsIpLiteral = isIP(url.hostname.replace(/^\[|\]$/gu, '')) !== 0;
  if (
    (url.protocol !== 'https:' && !localDevelopment) ||
    hostIsIpLiteral ||
    !isDnsDomainName(url.hostname) ||
    url.username.length > 0 ||
    url.password.length > 0 ||
    url.pathname !== '/' ||
    url.search.length > 0 ||
    url.hash.length > 0 ||
    value !== url.origin
  ) {
    throw invalidPublicOrigin();
  }
  return url.origin;
}

function invalidPublicOrigin(): RuntimeConfigurationError {
  return new RuntimeConfigurationError(
    'public_origin_invalid',
    'DIRT_PUBLIC_ORIGIN must be a bare HTTPS origin with a domain host (HTTP localhost is allowed for development)',
  );
}

function isDnsDomainName(hostname: string): boolean {
  return (
    hostname.length <= 253 &&
    !hostname.endsWith('.') &&
    hostname.split('.').every((label) => DNS_LABEL_PATTERN.test(label))
  );
}

function readPort(value: string | undefined): number {
  if (value === undefined) return DEFAULT_WEB_PORT;
  if (!/^[1-9]\d{0,4}$/u.test(value) || Number(value) > 65_535) {
    throw new RuntimeConfigurationError('web_port_invalid', 'DIRT_WEB_PORT must be an integer from 1 to 65535');
  }
  return Number(value);
}

function readSecretValue(
  environment: Readonly<Record<string, string | undefined>>,
  fileVariable: string,
  fileErrorCode: 'auth_secret_file_invalid' | 'bridge_token_file_invalid' | 'control_token_file_invalid',
): string | undefined {
  const secretFile = environment[fileVariable];
  if (secretFile === undefined) return undefined;
  if (secretFile.trim().length === 0) {
    throw new RuntimeConfigurationError(fileErrorCode, `${fileVariable} must name a readable secret file`);
  }
  try {
    return stripSingleLineEnding(readFileSync(secretFile, 'utf8'));
  } catch {
    throw new RuntimeConfigurationError(fileErrorCode, `${fileVariable} must name a readable secret file`);
  }
}

function stripSingleLineEnding(value: string): string {
  return value.endsWith('\r\n') ? value.slice(0, -2) : value.endsWith('\n') ? value.slice(0, -1) : value;
}
