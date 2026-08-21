import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import test from 'node:test';
import { BRIDGE_ERROR_CODES, BRIDGE_ROUTES } from '../dist/bridge/contract.js';
import { MCP_TOOL_NAMES } from '../dist/tools/configuration.js';

const read = (relative: string): string => readFileSync(new URL(relative, import.meta.url), 'utf8');
const sorted = (values: Iterable<string>): string[] => [...values].toSorted();

function openapiPath(openapi: string, path: string): string {
  const marker = `  ${path}:\n`;
  const start = openapi.indexOf(marker);
  assert.notEqual(start, -1, `OpenAPI path is missing: ${path}`);
  const end = openapi.indexOf('\n  /v1/', start + marker.length);
  return openapi.slice(start, end === -1 ? openapi.length : end);
}

function openapiSchema(openapi: string, name: string): string {
  const marker = `    ${name}:\n`;
  const start = openapi.indexOf(marker);
  assert.notEqual(start, -1, `OpenAPI schema is missing: ${name}`);
  const following = openapi.slice(start + marker.length);
  const relativeEnd = following.search(/\n    [A-Z][A-Za-z0-9]+:\n/);
  return openapi.slice(start, relativeEnd === -1 ? openapi.length : start + marker.length + relativeEnd);
}

function schemaReferenceCount(schema: string, name: string): number {
  return schema.split(`#/components/schemas/${name}`).length - 1;
}

function openapiPattern(openapi: string, schemaName: string): RegExp {
  const pattern = /^      pattern: '([^']+)'$/m.exec(openapiSchema(openapi, schemaName))?.[1];
  assert.ok(pattern, `${schemaName} is missing its validation pattern`);
  return new RegExp(pattern, 'u');
}

function openapiObjectVariant(schema: string, discriminatorValue: string): string {
  const marker = `              const: ${discriminatorValue}\n`;
  const markerIndex = schema.indexOf(marker);
  assert.notEqual(markerIndex, -1, `OpenAPI schema is missing discriminator value: ${discriminatorValue}`);
  const start = schema.lastIndexOf('        - type: object\n', markerIndex);
  assert.notEqual(start, -1, `OpenAPI discriminator has no object variant: ${discriminatorValue}`);
  const end = schema.indexOf('        - type: object\n', markerIndex + marker.length);
  return schema.slice(start, end === -1 ? undefined : end);
}

function yamlTopLevelMappingBlock(yaml: string, key: string): string | undefined {
  const marker = new RegExp(`^${key}:\\s*(?:#.*)?$`, 'm');
  const match = marker.exec(yaml);
  if (match === null) return undefined;
  const start = match.index + match[0].length;
  const following = yaml.slice(start);
  const relativeEnd = following.search(/^[^\s#][^:\n]*:\s*(?:#.*)?$/m);
  return following.slice(0, relativeEnd === -1 ? undefined : relativeEnd);
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
  const directBridgeErrors = read(
    '../../paper-plugin/src/main/java/ca/deliyannides/dirtmcp/paper/bridge/ErrorDetailsJson.java',
  );

  const codeSchema = openapiSchema(openapi, 'BridgeErrorCode');
  const openapiCodes = new Set([...codeSchema.matchAll(/^        - ([a-z_]+)$/gm)].flatMap((match) => match[1] ?? []));
  const bridgeErrorSchema = openapiSchema(openapi, 'BridgeError');
  const errorAlternatives = [...bridgeErrorSchema.matchAll(/\$ref: '#\/components\/schemas\/([A-Za-z0-9]+)'/g)].flatMap(
    (match) => match[1] ?? [],
  );
  const alternativeCodes = errorAlternatives.map((schemaName) => {
    const matches = [
      ...openapiSchema(openapi, schemaName).matchAll(/^            code:\n              const: ([a-z_]+)$/gm),
    ];
    assert.equal(matches.length, 1, `${schemaName} must constrain exactly one error code`);
    return matches[0]?.[1] ?? '';
  });
  const operationCodes = new Set(
    [...operationFailures.matchAll(/^    ([A-Z_]+)(?:,|$)/gm)].flatMap((match) =>
      match[1] === undefined ? [] : [match[1].toLowerCase()],
    ),
  );
  const directCodes = new Set([...directBridgeErrors.matchAll(/-> "([a-z_]+)";/g)].flatMap((match) => match[1] ?? []));
  const javaCodes = new Set([...operationCodes, ...directCodes]);

  assert.deepEqual(sorted(BRIDGE_ERROR_CODES), sorted(openapiCodes));
  assert.deepEqual(sorted(alternativeCodes), sorted(openapiCodes));
  assert.equal(new Set(alternativeCodes).size, alternativeCodes.length, 'Each bridge error code needs one alternative');
  assert.deepEqual(sorted(javaCodes), sorted(openapiCodes));
});

test('OpenAPI error numbers preserve Java ranges and documented relationships', () => {
  const openapi = read('../../protocol/openapi.yaml');
  assert.match(openapiSchema(openapi, 'Int32'), /format: int32\n      minimum: -2147483648\n      maximum: 2147483647/);
  assert.match(openapiSchema(openapi, 'PositiveInt32'), /format: int32\n      minimum: 1\n      maximum: 2147483647/);
  assert.match(
    openapiSchema(openapi, 'JsonSafeInteger'),
    /format: int64\n      minimum: -9007199254740991\n      maximum: 9007199254740991/,
  );
  assert.match(
    openapiSchema(openapi, 'PositiveJsonSafeInteger'),
    /format: int64\n      minimum: 1\n      maximum: 9007199254740991/,
  );

  const invalidRequest = openapiSchema(openapi, 'InvalidRequestDetails');
  const unsupportedValue = openapiObjectVariant(invalidRequest, 'unsupported_value');
  const outOfRange = openapiObjectVariant(invalidRequest, 'out_of_range');
  const tooManyItems = openapiObjectVariant(invalidRequest, 'too_many_items');
  const paletteWeightTotal = openapiObjectVariant(invalidRequest, 'palette_weight_total');
  const regionTooLarge = openapiSchema(openapi, 'RegionTooLargeDetails');
  const resultTooLarge = openapiSchema(openapi, 'ResultTooLargeError');
  const perspectiveView = openapiSchema(openapi, 'GetPerspectiveViewResponse');
  const placement = openapiSchema(openapi, 'PalettePlacement');
  const run = openapiSchema(openapi, 'PaletteRun');
  assert.equal(schemaReferenceCount(openapiSchema(openapi, 'BridgeBusyError'), 'PositiveInt32'), 1);
  assert.equal(schemaReferenceCount(openapiSchema(openapi, 'ChangeLimitExceededError'), 'PositiveInt32'), 1);
  assert.equal(schemaReferenceCount(invalidRequest, 'PositiveInt32'), 2);
  assert.equal(schemaReferenceCount(invalidRequest, 'JsonSafeInteger'), 3);
  assert.equal(schemaReferenceCount(invalidRequest, 'PositiveJsonSafeInteger'), 1);
  assert.equal(schemaReferenceCount(regionTooLarge, 'PositiveInt32'), 5);
  assert.equal(schemaReferenceCount(regionTooLarge, 'PositiveJsonSafeInteger'), 2);
  assert.equal(schemaReferenceCount(resultTooLarge, 'PositiveInt32'), 1);
  assert.equal(schemaReferenceCount(resultTooLarge, 'PositiveJsonSafeInteger'), 1);
  assert.equal(schemaReferenceCount(openapiSchema(openapi, 'HistoryCapacityDetails'), 'PositiveJsonSafeInteger'), 3);
  assert.equal(schemaReferenceCount(openapiSchema(openapi, 'ServerUnavailableDetails'), 'PositiveInt32'), 1);
  assert.equal(schemaReferenceCount(openapiSchema(openapi, 'Dimensions'), 'PositiveJsonSafeInteger'), 3);
  assert.equal(schemaReferenceCount(openapiSchema(openapi, 'ChunkPosition'), 'Int32'), 2);
  assert.equal(schemaReferenceCount(openapiSchema(openapi, 'PlayerItemStack'), 'PositiveInt32'), 3);
  assert.equal(schemaReferenceCount(openapiSchema(openapi, 'PlayerInventory'), 'PositiveInt32'), 1);
  assert.equal(schemaReferenceCount(placement, 'Int32'), 3);
  assert.equal(schemaReferenceCount(run, 'Int32'), 6);
  for (const tuple of [placement, run]) {
    assert.match(tuple, /prefixItems:\n        - type: integer\n          format: int32\n          minimum: 0/);
    assert.match(tuple, /^      items: false$/m);
  }
  assert.match(perspectiveView, /checkedChunkCount:\n\s+type: integer\n\s+format: int32\n\s+minimum: 0/);
  assert.equal(schemaReferenceCount(openapiSchema(openapi, 'GetPlayerContextResponse'), 'PlayerEffect'), 1);
  assert.match(openapiSchema(openapi, 'PlayerSelector'), /maxLength: 36/);
  assert.equal(schemaReferenceCount(outOfRange, 'JsonSafeInteger'), 3);
  assert.match(
    outOfRange,
    /description: value must be outside the inclusive range from minimum through maximum, and minimum must be less than or equal to maximum\./,
  );
  assert.match(outOfRange, /required:\n(?:\s+- [a-z]+\n)*\s+- target\n/);
  assert.doesNotMatch(outOfRange, /^            field:$/m);
  assert.match(unsupportedValue, /description: allowedValues is the non-empty unique list/);
  assert.match(
    unsupportedValue,
    /allowedValues:\n              type: array\n              minItems: 1\n              uniqueItems: true/,
  );
  assert.match(tooManyItems, /description: fields is a non-empty unique list/);
  assert.match(
    tooManyItems,
    /fields:\n              type: array\n              minItems: 1\n              uniqueItems: true/,
  );
  assert.doesNotMatch(tooManyItems, /^            field:$/m);
  assert.match(paletteWeightTotal, /description: requested is the positive palette weight total and must differ/);
  assert.match(paletteWeightTotal, /\$ref: '#\/components\/schemas\/PositiveJsonSafeInteger'/);
  assert.match(paletteWeightTotal, /not:\n\s+const: 100/);
  assert.match(
    openapiSchema(openapi, 'EditNotLatestError'),
    /requestedEditId and newestEditId identify distinct edits/,
  );
  assert.match(openapiSchema(openapi, 'WorldUnavailableDetails'), /^                - rolled_back$/m);
  assert.match(regionTooLarge, /description: The exact product of dimensions must be greater than maximum\./);
  assert.match(
    regionTooLarge,
    /description: minimumRequired is a known lower bound and must be greater than maximum\./,
  );
  assert.match(regionTooLarge, /description: requested must be greater than maximum\./);
  assert.match(
    resultTooLarge,
    /description: minimumRequired is a known lower bound on the required entries and must be greater than maximum\./,
  );
});

test('Paper, OpenAPI, shipped YAML, and MCP expose one exact tool catalog', () => {
  const openapi = read('../../protocol/openapi.yaml');
  const toolSchema = openapiSchema(openapi, 'ToolConfiguration');
  const requiredBlock = /required:\n([\s\S]*?)\n      properties:/.exec(toolSchema)?.[1];
  assert.ok(requiredBlock, 'ToolConfiguration is missing its required-property block');
  const openapiRequired = [...requiredBlock.matchAll(/- ([a-z_]+)/g)].flatMap((match) => match[1] ?? []);
  const openapiProperties = [...toolSchema.matchAll(/^        ([a-z_]+):$/gm)].flatMap((match) => match[1] ?? []);

  const shippedConfig = read('../../paper-plugin/src/main/resources/config.yml');
  const shippedTools = yamlTopLevelMappingBlock(shippedConfig, 'tools');
  assert.ok(shippedTools, 'Shipped config is missing its top-level tools section');
  const shippedKeys = [...shippedTools.matchAll(/^  ([a-z_]+): true$/gm)].flatMap((match) => match[1] ?? []);

  const javaToolSource = read('../../paper-plugin/src/main/java/ca/deliyannides/dirtmcp/paper/config/McpTool.java');
  const javaIds = [...javaToolSource.matchAll(/^\s*[A-Z][A-Z0-9_]*\s*\(\s*"([a-z_]+)"[\s\S]*?\)\s*[,;]/gm)].flatMap(
    (match) => match[1] ?? [],
  );

  assert.deepEqual(openapiRequired, [...MCP_TOOL_NAMES]);
  assert.deepEqual(openapiProperties, [...MCP_TOOL_NAMES]);
  assert.deepEqual(shippedKeys, [...MCP_TOOL_NAMES]);
  assert.deepEqual(javaIds, [...MCP_TOOL_NAMES]);
});

test('OpenAPI command patterns preserve Java control and outer-whitespace semantics', () => {
  const openapi = read('../../protocol/openapi.yaml');
  const input = openapiPattern(openapi, 'MinecraftCommandInput');
  const normalized = openapiPattern(openapi, 'NormalizedMinecraftCommand');

  for (const command of ['say hello', ' say hello ', '//help', ' / / ', '\u00a0']) {
    assert.match(command, input);
  }
  for (const command of ['', ' ', '\u1680', ' / ', '\u3000/\u3000', 'say\nstop', 'say\u0085stop']) {
    assert.doesNotMatch(command, input);
  }

  for (const command of ['say hello', '/help', '\u00a0']) assert.match(command, normalized);
  for (const command of [' say hello', 'say hello ', '\u1680say hello', 'say hello\u3000', 'say\nstop']) {
    assert.doesNotMatch(command, normalized);
  }
});

test('requires a UUIDv4 call ID on mutations and only salvages retained edit IDs', () => {
  const openapi = read('../../protocol/openapi.yaml');
  const editPaths = ['/v1/replace-region-blocks', '/v1/set-blocks', '/v1/undo-edit'];
  const callIdPaths = [...editPaths, '/v1/run-minecraft-commands'];
  for (const path of callIdPaths) {
    assert.match(openapiPath(openapi, path), /#\/components\/parameters\/DirtCallId/);
  }
  assert.deepEqual(
    Object.values(BRIDGE_ROUTES)
      .filter((route) => 'salvageEditId' in route && route.salvageEditId)
      .map((route) => route.path),
    editPaths,
  );
  assert.doesNotMatch(openapiPath(openapi, '/v1/get-edit-history'), /#\/components\/parameters\/DirtCallId/);
  const uuidV4Pattern = /^    UuidV4:\n(?:      .*\n)*?      pattern: '([^']+)'$/m.exec(openapi)?.[1];
  assert.ok(uuidV4Pattern, 'OpenAPI UuidV4 schema is missing its validation pattern');
  const uuidV4 = new RegExp(uuidV4Pattern);
  assert.match('123E4567-E89B-42D3-A456-426614174000', uuidV4);
  assert.match('123e4567-e89b-42d3-a456-426614174000', uuidV4);
  assert.doesNotMatch('123e4567-e89b-12d3-a456-426614174000', uuidV4);
  assert.doesNotMatch('123e4567-e89b-42d3-7456-426614174000', uuidV4);
});

test('pins every committed edit response to its operation and committed status', () => {
  const openapi = read('../../protocol/openapi.yaml');
  for (const [schemaName, operation] of [
    ['ReplaceRegionBlocksResponse', 'replace_region_blocks'],
    ['SetBlocksResponse', 'set_blocks'],
  ] as const) {
    const schema = openapiSchema(openapi, schemaName);
    assert.match(schema, new RegExp(`const: ${operation}`));
    assert.match(schema, /status:\n\s+const: committed/);
  }
});
