import type { BridgeConfig } from '../config.ts';
import * as z from 'zod/v4';
import { BridgeErrorResponseSchema, type BridgeError, type BridgeRoute } from './contract.ts';
import { ToolFailure } from './errors.ts';

interface BridgeFailureProgress<Response extends { readonly error: BridgeError }> {
  readonly schema: z.ZodType<Response>;
  readonly select: (response: Response) => Readonly<Record<string, unknown>>;
}

const EditIdContainerSchema = z.object({ editId: z.uuidv4() }).passthrough();
const EditIdEnvelopeSchema = z.object({ edit: z.unknown().optional(), error: z.unknown().optional() }).passthrough();

function salvageEditId(route: BridgeRoute, body: unknown): string | undefined {
  if (route.salvageEditId !== true) return undefined;

  const envelope = EditIdEnvelopeSchema.safeParse(body);
  if (!envelope.success) return undefined;
  const edit = EditIdContainerSchema.safeParse(envelope.data.edit);
  const error = EditIdContainerSchema.safeParse(envelope.data.error);
  const candidates = [edit, error].flatMap((candidate) => (candidate.success ? [candidate.data.editId] : []));
  if (candidates.length === 0) return undefined;
  const first = candidates[0]!;
  return candidates.every((candidate) => candidate.toLowerCase() === first.toLowerCase()) ? first : undefined;
}

export class BridgeClient {
  readonly #fetch: typeof globalThis.fetch;
  readonly #token: string;
  readonly origin: string;

  constructor(config: BridgeConfig, fetchImplementation: typeof globalThis.fetch = globalThis.fetch) {
    this.#fetch = fetchImplementation;
    this.origin = config.origin;
    this.#token = config.token;
  }

  async request<T, FailureResponse extends { readonly error: BridgeError } = never>(
    route: BridgeRoute,
    callId: string,
    responseSchema: z.ZodType<T>,
    requestBody?: unknown,
    failureProgress?: BridgeFailureProgress<FailureResponse>,
  ): Promise<T> {
    const headers = new Headers({
      Accept: 'application/json',
      Authorization: `Bearer ${this.#token}`,
      'X-Dirt-Call-Id': callId,
    });
    const request: RequestInit = {
      method: route.method,
      headers,
      redirect: 'error',
      signal: AbortSignal.timeout(route.timeoutMilliseconds),
    };
    if (route.method === 'POST') {
      headers.set('Content-Type', 'application/json');
      request.body = JSON.stringify(requestBody);
    }

    let response: Response;
    try {
      response = await this.#fetch(new URL(route.path, this.origin), request);
    } catch (error: unknown) {
      throw bridgeUnavailable(error);
    }

    if (!response.ok) {
      const parsedBody = await readResponseJson(response);
      const body: unknown = parsedBody.valid ? parsedBody.value : undefined;
      const editId = salvageEditId(route, body);
      if (response.status === 401) {
        throw new ToolFailure({
          code: 'bridge_unauthorized',
          message: 'Paper bridge rejected DIRT_MCP_BRIDGE_TOKEN.',
          details: { reason: 'authentication_failed' },
          ...(editId === undefined ? {} : { editId }),
        });
      }
      if (failureProgress !== undefined) {
        const progressDetail = failureProgress.schema.safeParse(body);
        if (progressDetail.success) {
          throw new ToolFailure(progressDetail.data.error, failureProgress.select(progressDetail.data));
        }
      }
      const detail = BridgeErrorResponseSchema.safeParse(body);
      if (detail.success) {
        if (failureProgress !== undefined && detail.data.error.editId !== undefined) {
          throw new ToolFailure({
            code: 'bridge_invalid_response',
            message: 'Paper bridge runtime failure omitted required progress.',
            editId: detail.data.error.editId,
          });
        }
        throw new ToolFailure(detail.data.error);
      }
      throw new ToolFailure({
        code: 'bridge_http_error',
        message: `Paper bridge returned unstructured HTTP ${response.status}.`,
        details: { status: response.status },
        ...(editId === undefined ? {} : { editId }),
      });
    }

    const parsedBody = await readResponseJson(response);
    if (!parsedBody.valid) {
      throw new ToolFailure({ code: 'bridge_invalid_response', message: 'Paper bridge returned invalid JSON.' });
    }
    const body = parsedBody.value;
    const parsed = responseSchema.safeParse(body);
    if (!parsed.success) {
      const editId = salvageEditId(route, body);
      throw new ToolFailure({
        code: 'bridge_invalid_response',
        message: 'Paper bridge response did not match the documented schema.',
        ...(editId === undefined ? {} : { editId }),
      });
    }
    return parsed.data;
  }
}

type ResponseJson = { readonly valid: true; readonly value: unknown } | { readonly valid: false };

async function readResponseJson(response: Response): Promise<ResponseJson> {
  try {
    return { valid: true, value: await response.json() };
  } catch (error: unknown) {
    if (error instanceof SyntaxError) return { valid: false };
    throw bridgeUnavailable(error);
  }
}

function bridgeUnavailable(error: unknown): ToolFailure {
  const message = error instanceof Error ? error.message : String(error);
  return new ToolFailure({
    code: 'bridge_unavailable',
    message: `Paper bridge request failed: ${message}`,
    details: { reason: requestFailureReason(error) },
  });
}

function requestFailureReason(error: unknown): 'timeout' | 'request_failed' {
  return error instanceof DOMException && (error.name === 'TimeoutError' || error.name === 'AbortError')
    ? 'timeout'
    : 'request_failed';
}
