import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { BRIDGE_ERROR_CODES, BRIDGE_OPERATION_IDS, BRIDGE_ROUTES } from '../dist/bridge/contract.js';
import { MCP_TOOL_OPERATIONS } from '../dist/tools/configuration.js';
import type { components } from '../src/generated/openapi.ts';

const openApi = readFileSync(new URL('../../protocol/openapi.yaml', import.meta.url), 'utf8');
const generated = readFileSync(new URL('../src/generated/openapi.ts', import.meta.url), 'utf8');

type HasRequiredFields<T, RequiredFields> = [T] extends [RequiredFields] ? true : false;

const generatedRequiredFields: readonly [
  HasRequiredFields<components['schemas']['CapabilitiesResponse'], { readonly operations: readonly unknown[] }>,
  HasRequiredFields<
    components['schemas']['ServerStatusRequest'],
    {
      readonly includePlayers: boolean;
      readonly includeWorlds: boolean;
      readonly includeConfiguration: boolean;
    }
  >,
  HasRequiredFields<components['schemas']['ReplaceRegionBlocksResponse'], { readonly seed: number }>,
  HasRequiredFields<components['schemas']['SetBlocksResponse'], { readonly seed: number }>,
] = [true, true, true, true];

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
    if (route.path === BRIDGE_ROUTES.capabilities.path) {
      assert.match(pathDocument, /^      operationId: getCapabilities$/mu);
    }
  }
});

test('public tools map exactly to configurable OpenAPI operation IDs', () => {
  assert.deepEqual(Object.values(MCP_TOOL_OPERATIONS), [...BRIDGE_OPERATION_IDS]);
  assert.deepEqual(generatedStringUnion('BridgeOperationId'), [...BRIDGE_OPERATION_IDS]);
  for (const operationId of BRIDGE_OPERATION_IDS) {
    assert.match(openApi, new RegExp(`operationId: ${operationId}(?:\\n|$)`, 'u'));
  }
});

test('runtime bridge error catalog matches generated OpenAPI error codes', () => {
  assert.deepEqual(generatedStringUnion('BridgeErrorCode'), [...BRIDGE_ERROR_CODES]);
});

test('generated bridge types keep required control and response fields concrete', () => {
  assert.deepEqual(generatedRequiredFields, [true, true, true, true]);
});
