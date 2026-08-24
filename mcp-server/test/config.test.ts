import assert from 'node:assert/strict';
import test from 'node:test';
import { BridgeConfigurationError, readBridgeConfig } from '../dist/config.js';

const TOKEN = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

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
