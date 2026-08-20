import type { ChildProcessWithoutNullStreams } from 'node:child_process';
import type { Readable } from 'node:stream';
import { createInterface } from 'node:readline';
import {
  CLIENT_CAPABILITIES_META_KEY,
  CLIENT_INFO_META_KEY,
  PROTOCOL_VERSION_META_KEY,
  SERVER_INFO_META_KEY,
} from '@modelcontextprotocol/server';

export type JsonRpcId = number | string;

export interface JsonRpcResult {
  readonly content?: readonly { readonly type: string; readonly text: string }[];
  readonly isError?: boolean;
  readonly structuredContent?: {
    readonly error?: { readonly code: string; readonly message: string };
    readonly [key: string]: unknown;
  };
  readonly tools?: readonly Record<string, unknown>[];
  readonly [key: string]: unknown;
}

export interface JsonRpcResponse {
  readonly id?: JsonRpcId;
  readonly result: JsonRpcResult;
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
      reject(new Error('Timed out waiting for MCP process output'));
    }, timeoutMilliseconds);
    collected.waiters.push(receive);
  });
}

export function waitFor(messages: Collected<JsonRpcResponse>, id: JsonRpcId): Promise<JsonRpcResponse> {
  return waitForValue(messages, (message) => message.id === id);
}

export function send(child: ChildProcessWithoutNullStreams, message: unknown): void {
  child.stdin.write(`${JSON.stringify(message)}\n`);
}

export function modernParams<T extends Record<string, unknown>>(params: T): T & { readonly _meta: object } {
  return {
    ...params,
    _meta: {
      [PROTOCOL_VERSION_META_KEY]: '2026-07-28',
      [CLIENT_INFO_META_KEY]: { name: 'bridge-test', version: '1' },
      [CLIENT_CAPABILITIES_META_KEY]: {},
    },
  };
}

export function modernResult<T extends Record<string, unknown>>(
  result: T,
): T & { readonly resultType: 'complete'; readonly _meta: object } {
  return {
    ...result,
    resultType: 'complete',
    _meta: {
      [SERVER_INFO_META_KEY]: { name: 'dirt-mcp', version: '0.1.0' },
    },
  };
}
