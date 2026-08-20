import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import test from 'node:test';
import { BRIDGE_ERROR_CODES, BRIDGE_ROUTES } from '../dist/bridge/contract.js';

const read = (relative: string): string => readFileSync(new URL(relative, import.meta.url), 'utf8');
const sorted = (values: Iterable<string>): string[] => [...values].toSorted();

function openapiPath(openapi: string, path: string): string {
  const marker = `  ${path}:\n`;
  const start = openapi.indexOf(marker);
  assert.notEqual(start, -1);
  const end = openapi.indexOf('\n  /v1/', start + marker.length);
  return openapi.slice(start, end === -1 ? openapi.length : end);
}

test('Java endpoints, OpenAPI operations, and MCP routes stay synchronized', () => {
  const openapi = read('../../protocol/openapi.yaml');
  const endpointDirectory = new URL(
    '../../paper-plugin/src/main/java/ca/deliyannides/dirtmcp/paper/bridge/endpoint/',
    import.meta.url,
  );

  const openapiRoutes = new Set(
    [...openapi.matchAll(/^  (\/v1\/[^:]+):\n    (get|post):$/gm)].flatMap((match) =>
      match[1] === undefined || match[2] === undefined ? [] : [`${match[2].toUpperCase()} ${match[1]}`],
    ),
  );
  const mcpRoutes = new Set(Object.values(BRIDGE_ROUTES).map((route) => `${route.method} ${route.path}`));
  const javaRoutes = new Set(
    readdirSync(endpointDirectory)
      .filter((name) => name.endsWith('Endpoint.java'))
      .flatMap((name) => {
        const source = readFileSync(new URL(name, endpointDirectory), 'utf8');
        const method = /public String method\(\) \{\s+return "([A-Z]+)";/m.exec(source)?.[1];
        const path = /public String path\(\) \{\s+return "(\/v1\/[^"]+)";/m.exec(source)?.[1];
        return method === undefined || path === undefined ? [] : [`${method} ${path}`];
      }),
  );

  assert.deepEqual(sorted(javaRoutes), sorted(openapiRoutes));
  assert.deepEqual(sorted(mcpRoutes), sorted(openapiRoutes));
});

test('Java, OpenAPI, and Zod expose the same bridge error codes', () => {
  const openapi = read('../../protocol/openapi.yaml');
  const operationFailures = read(
    '../../paper-plugin/src/main/java/ca/deliyannides/dirtmcp/paper/operation/OperationFailure.java',
  );
  const bridgeDirectory = new URL(
    '../../paper-plugin/src/main/java/ca/deliyannides/dirtmcp/paper/bridge/',
    import.meta.url,
  );

  const openapiBlock = /code:\n\s+type: string\n\s+enum:\n([\s\S]*?)\n\s+message:/.exec(openapi)?.[1];
  assert.ok(openapiBlock);

  const openapiCodes = new Set([...openapiBlock.matchAll(/- ([a-z_]+)/g)].flatMap((match) => match[1] ?? []));
  const operationCodes = new Set(
    [...operationFailures.matchAll(/^    ([A-Z_]+)(?:,|$)/gm)].flatMap((match) =>
      match[1] === undefined ? [] : [match[1].toLowerCase()],
    ),
  );
  const transportCodes = new Set(
    readdirSync(bridgeDirectory)
      .filter((name) => name.endsWith('.java'))
      .flatMap((name) =>
        Array.from(readFileSync(new URL(name, bridgeDirectory), 'utf8').matchAll(/sendError\(\s*\d+,\s*"([a-z_]+)"/g)),
      )
      .flatMap((match) => match[1] ?? []),
  );
  const javaCodes = new Set([...operationCodes, ...transportCodes]);

  assert.deepEqual(sorted(BRIDGE_ERROR_CODES), sorted(openapiCodes));
  assert.deepEqual(sorted(javaCodes), sorted(openapiCodes));
});

test('does not retain legacy last-edit undo contract aliases', () => {
  const openapi = read('../../protocol/openapi.yaml');
  assert.doesNotMatch(openapi, /undo-last-dirt-edit|UndoLastDirtEdit|nothing_to_undo|undoHistoryPerWorld/);
  assert.equal(
    Object.values(BRIDGE_ROUTES).some((route) => route.path.includes('undo-last')),
    false,
  );
  assert.equal(new Set<string>(BRIDGE_ERROR_CODES).has('nothing_to_undo'), false);
});

test('requires a UUIDv4 call ID on edits and undo but not history lookup', () => {
  const openapi = read('../../protocol/openapi.yaml');
  for (const path of ['/v1/replace-region-blocks', '/v1/fill-region', '/v1/set-blocks', '/v1/undo-edit']) {
    assert.match(openapiPath(openapi, path), /#\/components\/parameters\/DirtCallId/);
  }
  assert.doesNotMatch(openapiPath(openapi, '/v1/get-edit-history'), /#\/components\/parameters\/DirtCallId/);
  assert.match(openapi, /name: X-Dirt-Call-Id[\s\S]*?pattern: '\^\[0-9A-Fa-f\]\{8\}.*\$'/);
});
