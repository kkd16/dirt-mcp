import type { BridgeConfig } from '../config.ts';
import * as z from 'zod/v4';
import { BridgeErrorResponseSchema, type BridgeRoute } from './contract.ts';
import { ToolFailure } from './errors.ts';

export class BridgeClient {
  readonly #fetch: typeof globalThis.fetch;
  readonly #token: string;
  readonly origin: string;

  constructor(config: BridgeConfig, fetchImplementation: typeof globalThis.fetch = globalThis.fetch) {
    this.#fetch = fetchImplementation;
    this.origin = config.origin;
    this.#token = config.token;
  }

  async request<T>(
    route: BridgeRoute,
    callId: string,
    responseSchema: z.ZodType<T>,
    requestBody?: unknown,
    cancellationSignal?: AbortSignal,
  ): Promise<T> {
    const headers = new Headers({
      Accept: 'application/json',
      Authorization: `Bearer ${this.#token}`,
      'X-Dirt-Call-Id': callId,
    });
    const timeoutSignal = AbortSignal.timeout(route.timeoutMilliseconds);
    const request: RequestInit = {
      method: route.method,
      headers,
      redirect: 'error',
      signal: cancellationSignal === undefined ? timeoutSignal : AbortSignal.any([cancellationSignal, timeoutSignal]),
    };
    if (route.method === 'POST') {
      headers.set('Content-Type', 'application/json');
      request.body = JSON.stringify(requestBody);
    }

    let response: Response;
    try {
      response = await this.#fetch(new URL(route.path, this.origin), request);
    } catch (error: unknown) {
      throw bridgeUnavailable(error, cancellationSignal, timeoutSignal);
    }

    if (!response.ok) {
      const parsedBody = await readResponseJson(response, cancellationSignal, timeoutSignal);
      const body: unknown = parsedBody.valid ? parsedBody.value : undefined;
      if (response.status === 401) {
        throw new ToolFailure({
          code: 'bridge_unauthorized',
          message: 'Paper bridge authentication failed.',
          details: { reason: 'authentication_failed' },
        });
      }
      const detail = BridgeErrorResponseSchema.safeParse(body);
      if (detail.success) {
        throw new ToolFailure(detail.data.error);
      }
      throw new ToolFailure({
        code: 'bridge_http_error',
        message: `Paper bridge returned unstructured HTTP ${response.status}.`,
        details: { status: response.status },
      });
    }

    const parsedBody = await readResponseJson(response, cancellationSignal, timeoutSignal);
    if (!parsedBody.valid) {
      throw new ToolFailure({ code: 'bridge_invalid_response', message: 'Paper bridge returned invalid JSON.' });
    }
    const body = parsedBody.value;
    const parsed = responseSchema.safeParse(body);
    if (!parsed.success) {
      throw new ToolFailure({
        code: 'bridge_invalid_response',
        message: 'Paper bridge response did not match the documented schema.',
      });
    }
    return parsed.data;
  }
}

type ResponseJson = { readonly valid: true; readonly value: unknown } | { readonly valid: false };

async function readResponseJson(
  response: Response,
  cancellationSignal: AbortSignal | undefined,
  timeoutSignal: AbortSignal,
): Promise<ResponseJson> {
  try {
    return { valid: true, value: await response.json() };
  } catch (error: unknown) {
    if (error instanceof SyntaxError) return { valid: false };
    throw bridgeUnavailable(error, cancellationSignal, timeoutSignal);
  }
}

function bridgeUnavailable(
  error: unknown,
  cancellationSignal: AbortSignal | undefined,
  timeoutSignal: AbortSignal,
): ToolFailure {
  const reason = requestFailureReason(error, cancellationSignal, timeoutSignal);
  return new ToolFailure({
    code: 'bridge_unavailable',
    message:
      reason === 'cancelled'
        ? 'Paper bridge request was cancelled; completion may be ambiguous for state-changing calls.'
        : reason === 'timeout'
          ? 'Paper bridge request timed out; completion may be ambiguous for state-changing calls.'
          : 'Paper bridge request failed; completion may be ambiguous for state-changing calls.',
    details: { reason },
  });
}

function requestFailureReason(
  error: unknown,
  cancellationSignal: AbortSignal | undefined,
  timeoutSignal: AbortSignal,
): 'cancelled' | 'timeout' | 'request_failed' {
  if (cancellationSignal?.aborted === true) return 'cancelled';
  if (timeoutSignal.aborted) return 'timeout';
  return error instanceof DOMException && error.name === 'TimeoutError' ? 'timeout' : 'request_failed';
}
