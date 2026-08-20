import assert from 'node:assert/strict';
import test from 'node:test';
import { BridgeConfigurationError, readBridgeConfig } from '../dist/config.js';

test('reads the default and normalized loopback bridge origins', () => {
  assert.deepEqual(readBridgeConfig({ DIRT_MCP_BRIDGE_TOKEN: 'secret' }), {
    origin: 'http://127.0.0.1:8765',
    token: 'secret',
  });
  assert.deepEqual(
    readBridgeConfig({
      DIRT_MCP_BRIDGE_TOKEN: 'secret',
      DIRT_MCP_BRIDGE_URL: 'http://127.0.0.1:9876/',
    }),
    { origin: 'http://127.0.0.1:9876', token: 'secret' },
  );
});

test('requires a nonempty bridge token', () => {
  assert.throws(() => readBridgeConfig({}), /DIRT_MCP_BRIDGE_TOKEN is required/);
  assert.throws(() => readBridgeConfig({ DIRT_MCP_BRIDGE_TOKEN: '' }), /DIRT_MCP_BRIDGE_TOKEN is required/);
  assert.throws(
    () => readBridgeConfig({}),
    (error) => error instanceof BridgeConfigurationError && error.code === 'bridge_token_required',
  );
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
      () => readBridgeConfig({ DIRT_MCP_BRIDGE_TOKEN: 'secret', DIRT_MCP_BRIDGE_URL: url }),
      /DIRT_MCP_BRIDGE_URL must be an HTTP 127\.0\.0\.1 origin/,
    );
  }
  assert.throws(
    () => readBridgeConfig({ DIRT_MCP_BRIDGE_TOKEN: 'secret', DIRT_MCP_BRIDGE_URL: invalidUrls[0] }),
    (error) => error instanceof BridgeConfigurationError && error.code === 'bridge_url_invalid',
  );
});
