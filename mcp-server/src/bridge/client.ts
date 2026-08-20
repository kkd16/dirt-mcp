import type { BridgeConfig } from '../config.ts';
import * as z from 'zod/v4';
import { BridgeErrorResponseSchema, type BridgeRoute } from './contract.ts';
import { ToolFailure } from './errors.ts';

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

  async request<T>(
    route: BridgeRoute,
    callId: string,
    responseSchema: z.ZodType<T>,
    requestBody?: unknown,
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
      const message = error instanceof Error ? error.message : String(error);
      throw new ToolFailure('bridge_unavailable', `Paper bridge request failed: ${message}`);
    }

    if (!response.ok) {
      const body: unknown = await response.json().catch(() => undefined);
      const editId = salvageEditId(route, body);
      if (response.status === 401) {
        throw new ToolFailure('bridge_unauthorized', 'Paper bridge rejected DIRT_MCP_BRIDGE_TOKEN.', editId);
      }
      const detail = BridgeErrorResponseSchema.safeParse(body);
      if (detail.success) {
        throw new ToolFailure(detail.data.error.code, detail.data.error.message, detail.data.error.editId);
      }
      throw new ToolFailure('bridge_http_error', `Paper bridge returned unstructured HTTP ${response.status}.`, editId);
    }

    let body: unknown;
    try {
      body = await response.json();
    } catch {
      throw new ToolFailure('bridge_invalid_response', 'Paper bridge returned invalid JSON.');
    }
    const parsed = responseSchema.safeParse(body);
    if (!parsed.success) {
      throw new ToolFailure(
        'bridge_invalid_response',
        'Paper bridge response did not match the documented schema.',
        salvageEditId(route, body),
      );
    }
    return parsed.data;
  }
}
