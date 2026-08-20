import type { ChildProcessWithoutNullStreams } from 'node:child_process';
import type { Readable } from 'node:stream';
import { createInterface } from 'node:readline';
import {
  CLIENT_CAPABILITIES_META_KEY,
  CLIENT_INFO_META_KEY,
  PROTOCOL_VERSION_META_KEY,
  SERVER_INFO_META_KEY,
} from '@modelcontextprotocol/server';
import packageMetadata from '../../package.json' with { type: 'json' };

// Keep this explicit alongside the exact-pinned SDK so dependency upgrades must
// deliberately select the protocol revision exercised by the process tests.
const MCP_PROTOCOL_VERSION = '2026-07-28';

export type JsonRpcId = number | string;

export interface JsonRpcResult {
  readonly content?: readonly { readonly type: string; readonly text: string }[];
  readonly isError?: boolean;
  readonly structuredContent?: {
    readonly error?: {
      readonly callId: string;
      readonly code: string;
      readonly details?: Readonly<Record<string, unknown>>;
      readonly editId?: string;
      readonly message: string;
    };
    readonly [key: string]: unknown;
  };
  readonly tools?: readonly Record<string, unknown>[];
  readonly [key: string]: unknown;
}

export interface JsonRpcResponse {
  readonly jsonrpc?: string;
  readonly id?: JsonRpcId;
  readonly error?: { readonly code: number; readonly message: string };
  readonly result?: JsonRpcResult;
}

interface Collected<T> {
  readonly values: T[];
  waiters: ((value: T) => boolean)[];
}

export function collectLines<T>(stream: Readable, parse: (line: string) => T): Collected<T> {
  const collected: Collected<T> = { values: [], waiters: [] };
  createInterface({ input: stream }).on('line', (line) => {
    const value = parse(line);
    collected.values.push(value);
    collected.waiters = collected.waiters.filter((waiter) => !waiter(value));
  });
  return collected;
}

export function waitForValue<T>(
  collected: Collected<T>,
  predicate: (value: T) => boolean,
  expectation: string,
  timeoutMilliseconds = 5_000,
): Promise<T> {
  const existing = collected.values.find(predicate);
  if (existing !== undefined) {
    return Promise.resolve(existing);
  }
  return new Promise((resolve, reject) => {
    const receive = (value: T): boolean => {
      if (!predicate(value)) {
        return false;
      }
      clearTimeout(timer);
      resolve(value);
      return true;
    };
    const timer = setTimeout(() => {
      collected.waiters = collected.waiters.filter((waiter) => waiter !== receive);
      reject(
        new Error(
          `Timed out after ${timeoutMilliseconds} ms waiting for ${expectation}; collected ${collected.values.length} lines`,
        ),
      );
    }, timeoutMilliseconds);
    collected.waiters.push(receive);
  });
}

export function waitFor(messages: Collected<JsonRpcResponse>, id: JsonRpcId): Promise<JsonRpcResponse> {
  return waitForValue(messages, (message) => message.id === id, `JSON-RPC response id ${String(id)}`);
}

export function send(child: ChildProcessWithoutNullStreams, message: unknown): void {
  child.stdin.write(`${JSON.stringify(message)}\n`);
}

export function requestParams<T extends Record<string, unknown>>(params: T): T & { readonly _meta: object } {
  return {
    ...params,
    _meta: {
      [PROTOCOL_VERSION_META_KEY]: MCP_PROTOCOL_VERSION,
      [CLIENT_INFO_META_KEY]: { name: 'bridge-test', version: '1' },
      [CLIENT_CAPABILITIES_META_KEY]: {},
    },
  };
}

export function completeResult<T extends Record<string, unknown>>(
  result: T,
): T & { readonly resultType: 'complete'; readonly _meta: object } {
  return {
    ...result,
    resultType: 'complete',
    _meta: {
      [SERVER_INFO_META_KEY]: { name: 'dirt-mcp', version: packageMetadata.version },
    },
  };
}
