import type { Context, Hono } from 'hono';
import { createHash, timingSafeEqual } from 'node:crypto';
import * as z from 'zod/v4';
import type { RuntimeConfig } from '../config.ts';
import { BodyTooLargeError, InvalidBodyEncodingError, readBoundedText } from '../http-body.ts';
import { AccessError, type AccessRepository, type UserSummary } from './repository.ts';

const emptyBodySchema = z.object({}).strict();
const linkChallengeSchema = z
  .object({
    minecraftUuid: z.uuid(),
    minecraftName: z.string().trim().min(1).max(16),
  })
  .strict();
const CALL_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;

interface InternalRouteDependencies {
  readonly config: RuntimeConfig;
  readonly repository: AccessRepository;
  readonly isLoopback: (context: Context) => boolean;
}

export function registerInternalRoutes(app: Hono, dependencies: InternalRouteDependencies): void {
  const { config, repository } = dependencies;

  app.use('/internal/v1/access/*', async (context, next) => {
    context.header('Cache-Control', 'no-store');
    const callId = validCallIdOrNull(context.req.header('X-Dirt-Call-Id'));
    if (callId === null) {
      return context.json(
        { callId: null, error: { code: 'invalid_request', message: 'X-Dirt-Call-Id must be a UUIDv4.' } },
        400,
      );
    }
    if (!dependencies.isLoopback(context)) {
      return context.json({ callId, error: { code: 'unauthorized', message: 'Loopback access is required.' } }, 403);
    }
    if (!validBearer(context.req.header('Authorization'), config.controlToken)) {
      context.header('WWW-Authenticate', 'Bearer realm="dirt-mcp-control"');
      return context.json({ callId, error: { code: 'unauthorized', message: 'Authentication failed.' } }, 401);
    }
    return next();
  });

  app.get('/internal/v1/access/users', (context) => {
    const callId = requireCallId(context.req.header('X-Dirt-Call-Id'));
    const page = parsePage(context.req.query('page'));
    return context.json({ callId, ...repository.listUsers(page) });
  });

  app.get('/internal/v1/access/invitations', (context) => {
    const callId = requireCallId(context.req.header('X-Dirt-Call-Id'));
    const page = parsePage(context.req.query('page'));
    return context.json({ callId, ...repository.listInvitations(page) });
  });

  app.post('/internal/v1/access/invitations', async (context) => {
    const callId = requireCallId(context.req.header('X-Dirt-Call-Id'));
    await readJsonBody(context.req.raw, emptyBodySchema);
    const result = repository.createInvitation();
    return context.json({
      callId,
      invitation: result.invitation,
      inviteUrl: `${config.publicOrigin}/invite#token=${encodeURIComponent(result.secret)}`,
    });
  });

  app.post('/internal/v1/access/invitations/:id/revoke', async (context) => {
    const callId = requireCallId(context.req.header('X-Dirt-Call-Id'));
    await readJsonBody(context.req.raw, emptyBodySchema);
    return context.json({ callId, invitation: repository.revokeInvitation(requirePathValue(context.req.param('id'))) });
  });

  registerUserMutation(app, 'disable', (accessRepository, handle) => accessRepository.disableUser(handle));
  registerUserMutation(app, 'enable', (accessRepository, handle) => accessRepository.enableUser(handle));
  registerUserMutation(app, 'unlink', (accessRepository, handle) => accessRepository.unlinkUser(handle));

  app.post('/internal/v1/access/users/:handle/recovery', async (context) => {
    const callId = requireCallId(context.req.header('X-Dirt-Call-Id'));
    await readJsonBody(context.req.raw, emptyBodySchema);
    const result = repository.createRecovery(requirePathValue(context.req.param('handle')));
    return context.json({
      callId,
      user: result.user,
      recoveryUrl: `${config.publicOrigin}/recover#token=${encodeURIComponent(result.secret)}`,
      expiresAt: result.expiresAt,
    });
  });

  app.post('/internal/v1/access/minecraft-links/challenges', async (context) => {
    const callId = requireCallId(context.req.header('X-Dirt-Call-Id'));
    const body = await readJsonBody(context.req.raw, linkChallengeSchema);
    const result = repository.createMinecraftLinkChallenge(body.minecraftUuid, body.minecraftName);
    return context.json({
      callId,
      code: result.code,
      linkUrl: `${config.publicOrigin}/link#code=${encodeURIComponent(result.code)}`,
      expiresAt: result.expiresAt,
    });
  });

  function registerUserMutation(
    target: Hono,
    action: 'disable' | 'enable' | 'unlink',
    mutate: (repository: AccessRepository, handle: string) => UserSummary,
  ): void {
    target.post(`/internal/v1/access/users/:handle/${action}`, async (context) => {
      const callId = requireCallId(context.req.header('X-Dirt-Call-Id'));
      await readJsonBody(context.req.raw, emptyBodySchema);
      return context.json({ callId, user: mutate(repository, requirePathValue(context.req.param('handle'))) });
    });
  }
}

function validBearer(header: string | undefined, token: string): boolean {
  if (header === undefined) return false;
  const suppliedToken = /^Bearer +(\S+)$/iu.exec(header)?.[1];
  if (suppliedToken === undefined) return false;
  const supplied = createHash('sha256').update(suppliedToken, 'utf8').digest();
  const expected = createHash('sha256').update(token, 'utf8').digest();
  return timingSafeEqual(supplied, expected);
}

function parsePage(raw: string | undefined): number {
  if (raw === undefined) throw new AccessError('invalid', 'page is required.');
  if (!/^[1-9]\d{0,8}$/u.test(raw)) throw new AccessError('invalid', 'page must be a positive integer.');
  return Number(raw);
}

function requirePathValue(value: string): string {
  if (value.trim().length === 0 || value.length > 128) throw new AccessError('invalid', 'Invalid path value.');
  return value;
}

function requireCallId(value: string | undefined): string {
  const callId = validCallIdOrNull(value);
  if (callId === null) throw new AccessError('invalid', 'X-Dirt-Call-Id is required.');
  return callId;
}

export function validCallIdOrNull(value: string | undefined): string | null {
  return value !== undefined && CALL_ID_PATTERN.test(value) ? value : null;
}

export async function readJsonBody<T>(request: Request, schema: z.ZodType<T>): Promise<T> {
  const contentType = request.headers.get('Content-Type')?.split(';', 1)[0]?.trim().toLowerCase();
  if (contentType !== 'application/json') throw new AccessError('invalid', 'Content-Type must be application/json.');
  try {
    return schema.parse(JSON.parse(await readBoundedText(request, 16_384)));
  } catch (error: unknown) {
    if (error instanceof BodyTooLargeError) throw new AccessError('invalid', 'Request body is too large.');
    if (error instanceof InvalidBodyEncodingError) {
      throw new AccessError('invalid', 'Request body must be valid UTF-8 JSON.');
    }
    throw error;
  }
}

export function internalError(error: unknown): {
  readonly code: 'invalid_request' | 'not_found' | 'conflict' | 'internal_error';
  readonly message: string;
  readonly status: 400 | 404 | 409 | 500;
} {
  if (error instanceof AccessError) {
    if (error.code === 'not_found') return { code: 'not_found', message: error.message, status: 404 };
    if (error.code === 'conflict') return { code: 'conflict', message: error.message, status: 409 };
    return { code: 'invalid_request', message: error.message, status: 400 };
  }
  if (error instanceof z.ZodError || error instanceof SyntaxError) {
    return { code: 'invalid_request', message: 'The request is invalid.', status: 400 };
  }
  return { code: 'internal_error', message: 'An internal error occurred.', status: 500 };
}
