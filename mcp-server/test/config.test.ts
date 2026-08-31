import assert from 'node:assert/strict';
import test from 'node:test';
import {
  BridgeConfigurationError,
  readBridgeConfig,
  readRuntimeConfig,
  RuntimeConfigurationError,
} from '../dist/config.js';

const TOKEN = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
const CONTROL_TOKEN = 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789';
const AUTH_SECRET = 'fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210';

test('reads the default and normalized loopback bridge origins', () => {
  assert.deepEqual(readBridgeConfig({ DIRT_MCP_BRIDGE_TOKEN: TOKEN }), {
    origin: 'http://127.0.0.1:8765',
    token: TOKEN,
  });
  assert.deepEqual(
    readBridgeConfig({
      DIRT_MCP_BRIDGE_TOKEN: TOKEN,
      DIRT_MCP_BRIDGE_URL: 'http://127.0.0.1:9876/',
    }),
    { origin: 'http://127.0.0.1:9876', token: TOKEN },
  );
});

test('requires a nonempty bridge token', () => {
  assert.throws(() => readBridgeConfig({}), /DIRT_MCP_BRIDGE_TOKEN is required/);
  assert.throws(() => readBridgeConfig({ DIRT_MCP_BRIDGE_TOKEN: '' }), /DIRT_MCP_BRIDGE_TOKEN is required/);
  assert.throws(() => readBridgeConfig({ DIRT_MCP_BRIDGE_TOKEN: ' '.repeat(32) }), /DIRT_MCP_BRIDGE_TOKEN is required/);
  assert.throws(
    () => readBridgeConfig({}),
    (error) => error instanceof BridgeConfigurationError && error.code === 'bridge_token_required',
  );
});

test('requires exactly 64 lowercase hexadecimal token characters', () => {
  for (const token of [
    'a'.repeat(63),
    'a'.repeat(65),
    'A'.repeat(64),
    `${'a'.repeat(63)}g`,
    '🔒'.repeat(16),
    `${TOKEN}\n`,
  ]) {
    assert.throws(
      () => readBridgeConfig({ DIRT_MCP_BRIDGE_TOKEN: token }),
      (error) => error instanceof BridgeConfigurationError && error.code === 'bridge_token_invalid',
    );
  }
});

test('rejects bridge URLs that are not a bare HTTP IPv4 loopback origin', () => {
  const invalidUrls = [
    'not a url',
    'https://127.0.0.1:8765',
    'http://localhost:8765',
    'http://[::1]:8765',
    'http://192.0.2.1:8765',
    'http://127.1:8765',
    'http://2130706433:8765',
    'http://127.0.0.1:0',
    'http://127.0.0.1:99999',
    'http://user:password@127.0.0.1:8765',
    'http://127.0.0.1:8765/v1',
    'http://127.0.0.1:8765/?debug=true',
    'http://127.0.0.1:8765/#fragment',
    ' http://127.0.0.1:8765 ',
  ];
  for (const url of invalidUrls) {
    assert.throws(
      () => readBridgeConfig({ DIRT_MCP_BRIDGE_TOKEN: TOKEN, DIRT_MCP_BRIDGE_URL: url }),
      /DIRT_MCP_BRIDGE_URL must be an HTTP 127\.0\.0\.1 origin/,
    );
  }
  assert.throws(
    () => readBridgeConfig({ DIRT_MCP_BRIDGE_TOKEN: TOKEN, DIRT_MCP_BRIDGE_URL: invalidUrls[0] }),
    (error) => error instanceof BridgeConfigurationError && error.code === 'bridge_url_invalid',
  );
});

test('runtime config prefers secret files and requires pairwise-distinct trust secrets', () => {
  const files: Record<string, string> = {
    '/bridge': `${TOKEN}\n`,
    '/control': `${CONTROL_TOKEN}\r\n`,
    '/auth': AUTH_SECRET,
  };
  const config = readRuntimeConfig(
    {
      DIRT_BRIDGE_TOKEN_FILE: '/bridge',
      DIRT_CONTROL_TOKEN_FILE: '/control',
      DIRT_AUTH_SECRET_FILE: '/auth',
      DIRT_MCP_BRIDGE_TOKEN: CONTROL_TOKEN,
      DIRT_CONTROL_TOKEN: TOKEN,
      DIRT_PUBLIC_ORIGIN: 'http://localhost:3000',
      DIRT_DATABASE_PATH: './data/dirt.sqlite',
    },
    (path) =>
      files[path] ??
      (() => {
        throw new Error('missing');
      })(),
  );
  assert.equal(config.bridge.token, TOKEN);
  assert.equal(config.controlToken, CONTROL_TOKEN);
  assert.equal(config.authSecret, AUTH_SECRET);
  assert.equal(config.port, 3000);
  assert.equal(config.rpId, 'localhost');

  for (const [bridge, control, auth] of [
    [TOKEN, TOKEN, AUTH_SECRET],
    [TOKEN, CONTROL_TOKEN, TOKEN],
    [TOKEN, CONTROL_TOKEN, CONTROL_TOKEN],
  ]) {
    assert.throws(
      () =>
        readRuntimeConfig({
          DIRT_MCP_BRIDGE_TOKEN: bridge,
          DIRT_CONTROL_TOKEN: control,
          DIRT_AUTH_SECRET: auth,
          DIRT_PUBLIC_ORIGIN: 'https://dirt.example',
          DIRT_DATABASE_PATH: './data/dirt.sqlite',
        }),
      (error) => error instanceof RuntimeConfigurationError && error.code === 'secrets_not_distinct',
    );
  }
});

test('public origin is a WebAuthn-compatible domain origin', () => {
  const base = {
    DIRT_MCP_BRIDGE_TOKEN: TOKEN,
    DIRT_CONTROL_TOKEN: CONTROL_TOKEN,
    DIRT_AUTH_SECRET: AUTH_SECRET,
    DIRT_DATABASE_PATH: './data/dirt.sqlite',
  };
  assert.equal(readRuntimeConfig({ ...base, DIRT_PUBLIC_ORIGIN: 'https://dirt.example' }).rpId, 'dirt.example');
  assert.equal(readRuntimeConfig({ ...base, DIRT_PUBLIC_ORIGIN: 'http://localhost:3000' }).rpId, 'localhost');
  assert.equal(readRuntimeConfig({ ...base, DIRT_PUBLIC_ORIGIN: 'https://dirt.internal' }).rpId, 'dirt.internal');
  assert.equal(
    readRuntimeConfig({ ...base, DIRT_PUBLIC_ORIGIN: 'https://xn--bcher-kva.example' }).rpId,
    'xn--bcher-kva.example',
  );

  for (const publicOrigin of [
    'http://127.0.0.1:3000',
    'https://127.0.0.1',
    'https://[::1]',
    'https://dirt.example.',
    'https://bad_label.example',
    'https://-dirt.example',
    'https://dirt-.example',
    `https://${'a'.repeat(64)}.example`,
    `https://${`${'a'.repeat(63)}.`.repeat(4)}example`,
  ]) {
    assert.throws(
      () => readRuntimeConfig({ ...base, DIRT_PUBLIC_ORIGIN: publicOrigin }),
      (error) => error instanceof RuntimeConfigurationError && error.code === 'public_origin_invalid',
    );
  }
});
