import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { BRIDGE_ERROR_CODES, BRIDGE_OPERATION_IDS, BRIDGE_ROUTES } from '../dist/bridge/contract.js';
import { MCP_TOOL_OPERATIONS } from '../dist/tools/configuration.js';

const openApi = readFileSync(new URL('../../protocol/openapi.yaml', import.meta.url), 'utf8');
const generated = readFileSync(new URL('../src/generated/openapi.ts', import.meta.url), 'utf8');

function generatedStringUnion(schemaName: string): readonly string[] {
  const union = new RegExp(`readonly ${schemaName}: ([^;]+);`, 'u').exec(generated)?.[1];
  assert.ok(union, `Missing generated ${schemaName} union.`);
  return [...union.matchAll(/"([^"]+)"/gu)].map((match) => match[1]!);
}

test('every MCP bridge route exists in the authoritative OpenAPI contract', () => {
  for (const route of Object.values(BRIDGE_ROUTES)) {
    assert.match(openApi, new RegExp(`^  ${route.path.replaceAll('/', '\\/')}:$`, 'mu'));
    const pathStart = openApi.indexOf(`  ${route.path}:`);
    const nextPath = openApi.indexOf('\n  /v1/', pathStart + 1);
    const pathDocument = openApi.slice(pathStart, nextPath < 0 ? undefined : nextPath);
    assert.match(pathDocument, new RegExp(`^    ${route.method.toLowerCase()}:$`, 'mu'));
    assert.match(pathDocument, /components\/parameters\/DirtCallId/u);
    assert.deepEqual(
      [...pathDocument.matchAll(/^        '(2\d\d)':/gmu)].map((match) => match[1]),
      ['200'],
      `${route.path} must have one exact HTTP 200 success response`,
    );
    const successStart = pathDocument.indexOf("        '200':");
    const nextResponse = pathDocument.indexOf("\n        '", successStart + 1);
    const successResponse = pathDocument.slice(successStart, nextResponse < 0 ? undefined : nextResponse);
    assert.match(successResponse, /^            application\/json:$/mu);
  }
  assert.equal(BRIDGE_ROUTES.capabilities.method, 'GET');
  assert.equal(BRIDGE_ROUTES.serverStatus.method, 'POST');
});

test('the twelve public tools map exactly to configurable OpenAPI operation IDs', () => {
  assert.equal(Object.keys(MCP_TOOL_OPERATIONS).length, 12);
  assert.deepEqual(Object.values(MCP_TOOL_OPERATIONS), [...BRIDGE_OPERATION_IDS]);
  assert.deepEqual(generatedStringUnion('BridgeOperationId'), [...BRIDGE_OPERATION_IDS]);
  for (const operationId of BRIDGE_OPERATION_IDS) {
    assert.match(openApi, new RegExp(`operationId: ${operationId}(?:\\n|$)`, 'u'));
  }
  assert.match(openApi, /operationId: getCapabilities/u);
  assert.equal(BRIDGE_OPERATION_IDS.includes('getCapabilities' as never), false);
});

test('capabilities and status requests expose their current control-plane fields', () => {
  const capabilitiesStart = openApi.indexOf('    CapabilitiesResponse:');
  const capabilitiesEnd = openApi.indexOf('\n    Uuid:', capabilitiesStart);
  const capabilities = openApi.slice(capabilitiesStart, capabilitiesEnd);
  assert.match(capabilities, /- operations/u);

  const statusStart = openApi.indexOf('    ServerStatusRequest:');
  const statusEnd = openApi.indexOf('\n    ServerStatusResponse:', statusStart);
  const statusRequest = openApi.slice(statusStart, statusEnd);
  assert.match(statusRequest, /- includePlayers/u);
  assert.match(statusRequest, /- includeWorlds/u);
  assert.match(statusRequest, /- includeConfiguration/u);
});

test('runtime bridge error catalog matches generated OpenAPI error codes', () => {
  assert.deepEqual(generatedStringUnion('BridgeErrorCode'), [...BRIDGE_ERROR_CODES]);
  assert.equal(BRIDGE_ERROR_CODES.includes('operation_disabled'), true);
  assert.equal(BRIDGE_ERROR_CODES.includes('route_not_found'), true);
  assert.equal(BRIDGE_ERROR_CODES.includes('not_found' as never), false);
});

test('generated bridge types include every route and required concrete policy field', () => {
  for (const route of Object.values(BRIDGE_ROUTES)) assert.match(generated, new RegExp(`"${route.path}"`, 'u'));
  assert.match(generated, /readonly maxResults: components\["schemas"\]\["PositiveInt32"\]/u);
  assert.match(generated, /readonly seed: components\["schemas"\]\["Int32"\]/u);
  assert.match(generated, /readonly maxChangedBlocks: components\["schemas"\]\["PositiveInt32"\] \| null/u);
  assert.doesNotMatch(generated, /readonly maxResults: components\["schemas"\]\["PositiveInt32"\] \| null/u);
  assert.doesNotMatch(generated, /readonly seed: components\["schemas"\]\["Int32"\] \| null/u);
});
