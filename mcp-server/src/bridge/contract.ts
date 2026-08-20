import * as z from 'zod/v4';

export interface BridgeRoute {
  readonly method: 'GET' | 'POST';
  readonly path: `/v1/${string}`;
  readonly timeoutMilliseconds: number;
}

export const BRIDGE_ROUTES = {
  ping: { method: 'GET', path: '/v1/ping', timeoutMilliseconds: 3_000 },
  serverStatus: { method: 'GET', path: '/v1/server-status', timeoutMilliseconds: 3_000 },
  countRegionBlockStates: {
    method: 'POST',
    path: '/v1/count-region-block-states',
    timeoutMilliseconds: 30_000,
  },
  getRegionBlocks: { method: 'POST', path: '/v1/get-region-blocks', timeoutMilliseconds: 30_000 },
  scanOrthographicView: {
    method: 'POST',
    path: '/v1/scan-orthographic-view',
    timeoutMilliseconds: 30_000,
  },
  replaceRegionBlocks: {
    method: 'POST',
    path: '/v1/replace-region-blocks',
    timeoutMilliseconds: 120_000,
  },
  fillRegion: { method: 'POST', path: '/v1/fill-region', timeoutMilliseconds: 120_000 },
  setBlocks: { method: 'POST', path: '/v1/set-blocks', timeoutMilliseconds: 120_000 },
  undoLastDirtEdit: {
    method: 'POST',
    path: '/v1/undo-last-dirt-edit',
    timeoutMilliseconds: 120_000,
  },
  runMinecraftCommands: {
    method: 'POST',
    path: '/v1/run-minecraft-commands',
    timeoutMilliseconds: 120_000,
  },
} as const satisfies Record<string, BridgeRoute>;

export const BRIDGE_ERROR_CODES = [
  'bridge_busy',
  'change_limit_exceeded',
  'internal_error',
  'invalid_request',
  'method_not_allowed',
  'not_found',
  'nothing_to_undo',
  'region_too_large',
  'result_too_large',
  'server_unavailable',
  'unauthorized',
  'unhealthy',
  'world_busy',
  'world_not_found',
  'world_unavailable',
] as const;

export const BridgeErrorResponseSchema = z
  .object({
    error: z
      .object({
        code: z.enum(BRIDGE_ERROR_CODES).describe('Stable machine-readable error code.'),
        message: z.string().min(1).describe('Human-readable explanation.'),
      })
      .strict(),
  })
  .strict()
  .describe('Structured Dirt error.');
