import * as z from 'zod';
import type { BridgeConfig } from '../config.ts';
import { BodyTooLargeError, InvalidBodyEncodingError, readBoundedText } from '../http-body.ts';
import { BridgeErrorResponseSchema, bridgeErrorMatchesHttpStatus, type BridgeRoute } from './contract.ts';
import { ToolFailure } from './errors.ts';

// Default Paper inspection limits stay well below this. Keep the ceiling fixed
// and independent of Paper configuration so a faulty loopback peer cannot make
// the internet-facing web process buffer an arbitrary response.
const MAX_BRIDGE_RESPONSE_BYTES = 8 * 1_024 * 1_024;

export class BridgeClient {
  readonly #fetch: typeof globalThis.fetch;
  readonly #origin: string;
  readonly #token: string;

  constructor(config: BridgeConfig, fetchImplementation: typeof globalThis.fetch = globalThis.fetch) {
    this.#fetch = fetchImplementation;
    this.#origin = config.origin;
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
      response = await this.#fetch(new URL(route.path, this.#origin), request);
    } catch (error: unknown) {
      throw bridgeUnavailable(error, cancellationSignal, timeoutSignal);
    }

    if (response.status === 401) {
      await discardResponseBody(response);
      throw new ToolFailure({
        code: 'bridge_unauthorized',
        message: 'Paper bridge authentication failed.',
        details: { reason: 'authentication_failed' },
      });
    }

    if (response.status !== 200) {
      if (!hasJsonContentType(response)) {
        await discardResponseBody(response);
        throw bridgeHttpError(response.status);
      }
      const parsedBody = await readResponseJson(response, cancellationSignal, timeoutSignal);
      const body: unknown = parsedBody.valid ? parsedBody.value : undefined;
      const detail = BridgeErrorResponseSchema.safeParse(body);
      if (detail.success && bridgeErrorMatchesHttpStatus(detail.data.error, response.status)) {
        throw new ToolFailure(detail.data.error);
      }
      throw bridgeHttpError(response.status);
    }

    if (!hasJsonContentType(response)) {
      await discardResponseBody(response);
      throw new ToolFailure({
        code: 'bridge_invalid_response',
        message: 'Paper bridge response was not application/json.',
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

function hasJsonContentType(response: Response): boolean {
  return response.headers.get('Content-Type')?.split(';', 1)[0]?.trim().toLowerCase() === 'application/json';
}

async function discardResponseBody(response: Response): Promise<void> {
  if (response.body === null) return;
  try {
    await response.body.cancel();
  } catch {
    // Preserve the primary protocol failure if the transport also fails while releasing the body.
  }
}

function bridgeHttpError(status: number): ToolFailure {
  return new ToolFailure({
    code: 'bridge_http_error',
    message: `Paper bridge returned unexpected HTTP ${status}.`,
    details: { status },
  });
}

type ResponseJson = { readonly valid: true; readonly value: unknown } | { readonly valid: false };

async function readResponseJson(
  response: Response,
  cancellationSignal: AbortSignal | undefined,
  timeoutSignal: AbortSignal,
): Promise<ResponseJson> {
  if (response.body === null) return { valid: false };

  let text: string;
  try {
    text = await readBoundedText(response, MAX_BRIDGE_RESPONSE_BYTES);
  } catch (error: unknown) {
    if (error instanceof BodyTooLargeError || error instanceof InvalidBodyEncodingError) {
      return { valid: false };
    }
    throw bridgeUnavailable(error, cancellationSignal, timeoutSignal);
  }

  try {
    return { valid: true, value: JSON.parse(text) };
  } catch {
    return { valid: false };
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
