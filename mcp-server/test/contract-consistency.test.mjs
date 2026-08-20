import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import test from 'node:test';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const sorted = (values) => [...values].toSorted();

test('Java endpoints, OpenAPI paths, and MCP bridge calls stay synchronized', () => {
  const openapi = read('../../protocol/openapi.yaml');
  const tools = read('../src/tools.ts');
  const endpointDirectory = new URL(
    '../../paper-plugin/src/main/java/ca/deliyannides/dirtmcp/paper/bridge/endpoint/',
    import.meta.url,
  );

  const openapiPaths = new Set([...openapi.matchAll(/^  (\/v1\/[^:]+):$/gm)].map((match) => match[1]));
  const mcpPaths = new Set([...tools.matchAll(/bridgeRequest\(\s*config,\s*'([^']+)'/gs)].map((match) => match[1]));
  const javaPaths = new Set(
    readdirSync(endpointDirectory)
      .filter((name) => name.endsWith('Endpoint.java'))
      .flatMap((name) =>
        Array.from(readFileSync(new URL(name, endpointDirectory), 'utf8').matchAll(/return "(\/v1\/[^"]+)";/g)),
      )
      .map((match) => match[1]),
  );

  assert.deepEqual(sorted(javaPaths), sorted(openapiPaths));
  assert.deepEqual(sorted(mcpPaths), sorted(openapiPaths));
});

test('Java, OpenAPI, and Zod expose the same bridge error codes', () => {
  const openapi = read('../../protocol/openapi.yaml');
  const tools = read('../src/tools.ts');
  const operationFailures = read(
    '../../paper-plugin/src/main/java/ca/deliyannides/dirtmcp/paper/operation/OperationFailure.java',
  );
  const bridgeDirectory = new URL(
    '../../paper-plugin/src/main/java/ca/deliyannides/dirtmcp/paper/bridge/',
    import.meta.url,
  );

  const openapiBlock = /code:\n\s+type: string\n\s+enum:\n([\s\S]*?)\n\s+message:/.exec(openapi)?.[1];
  const zodBlock = /const BridgeErrorCodeSchema = z\.enum\(\[([\s\S]*?)\]\);/.exec(tools)?.[1];
  assert.ok(openapiBlock);
  assert.ok(zodBlock);

  const openapiCodes = new Set([...openapiBlock.matchAll(/- ([a-z_]+)/g)].map((match) => match[1]));
  const zodCodes = new Set([...zodBlock.matchAll(/'([a-z_]+)'/g)].map((match) => match[1]));
  const operationCodes = new Set(
    [...operationFailures.matchAll(/^    ([A-Z_]+)(?:,|$)/gm)].map((match) => match[1].toLowerCase()),
  );
  const transportCodes = new Set(
    readdirSync(bridgeDirectory)
      .filter((name) => name.endsWith('.java'))
      .flatMap((name) =>
        Array.from(readFileSync(new URL(name, bridgeDirectory), 'utf8').matchAll(/sendError\(\s*\d+,\s*"([a-z_]+)"/g)),
      )
      .map((match) => match[1]),
  );
  const javaCodes = new Set([...operationCodes, ...transportCodes]);

  assert.deepEqual(sorted(zodCodes), sorted(openapiCodes));
  assert.deepEqual(sorted(javaCodes), sorted(openapiCodes));
});
