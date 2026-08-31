import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createRequire, syncBuiltinESMExports } from 'node:module';
import type { LookupFunction } from 'node:net';
import test from 'node:test';
import type { request as httpsRequest } from 'node:https';

const require = createRequire(import.meta.url);

test('patched CIMD transport answers Node all-address lookups with the pinned address list', async () => {
  const dnsPromises = require('node:dns/promises') as {
    lookup: typeof import('node:dns/promises').lookup;
  };
  const https = require('node:https') as {
    request: typeof httpsRequest;
  };
  const originalLookup = dnsPromises.lookup;
  const originalRequest = https.request;
  const pinnedAddress = { address: '93.184.216.34', family: 4 as const };
  const intentionalFailure = new Error('request stopped after exercising the lookup callback');
  let lookupAnswer: unknown[] | undefined;

  dnsPromises.lookup = (async () => [pinnedAddress]) as unknown as typeof dnsPromises.lookup;
  https.request = ((_url: unknown, options: import('node:https').RequestOptions) => {
    const request = new EventEmitter() as ReturnType<typeof httpsRequest>;
    request.end = (() => {
      const lookup = options.lookup as LookupFunction;
      lookup('client.example.com', { all: true }, (...answer) => {
        lookupAnswer = answer;
        request.emit('error', intentionalFailure);
      });
      return request;
    }) as typeof request.end;
    return request;
  }) as unknown as typeof https.request;
  syncBuiltinESMExports();

  try {
    const { fetchClientMetadataResource } = await import('@better-auth/cimd/node');
    await assert.rejects(
      async () => fetchClientMetadataResource('https://client.example.com/client.json'),
      intentionalFailure,
    );
    assert.deepEqual(lookupAnswer, [null, [pinnedAddress]]);
  } finally {
    dnsPromises.lookup = originalLookup;
    https.request = originalRequest;
    syncBuiltinESMExports();
  }
});
