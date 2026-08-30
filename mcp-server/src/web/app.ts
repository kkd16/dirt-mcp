import { getConnInfo } from '@hono/node-server/conninfo';
import { readFileSync } from 'node:fs';
import { Hono, type Context } from 'hono';
import { secureHeaders } from 'hono/secure-headers';
import * as z from 'zod/v4';
import { AccessError, type AccessRepository, type UserSummary } from '../access/repository.ts';
import { internalError, registerInternalRoutes } from '../access/routes.ts';
import { createOnboardingTicket, normalizeHandle, onboardingCookieHeader, type DirtAuth } from '../auth.ts';
import type { RuntimeConfig } from '../config.ts';
import type { DirtLogger } from '../logging.ts';
import type { DirtMcpHandler } from '../mcp-http.ts';
import { consentPage, dashboardPage, errorPage, linkPage, onboardingPage, signInPage, stylesheet } from './pages.ts';

const onboardingExchangeSchema = z
  .object({
    kind: z.enum(['invitation', 'recovery']),
    token: z.string().min(20).max(256),
    handle: z.string().max(64).optional(),
  })
  .strict();
const linkSchema = z.object({ code: z.string().trim().min(8).max(24) }).strict();
const MAX_BROWSER_BODY_BYTES = 16_384;
const RECENT_AUTHENTICATION_MS = 5 * 60 * 1_000;

export interface WebAppDependencies {
  readonly auth: DirtAuth;
  readonly config: RuntimeConfig;
  readonly logger: DirtLogger;
  readonly mcp: DirtMcpHandler;
  readonly repository: AccessRepository;
  readonly isLoopback?: (context: Context) => boolean;
  readonly clientScript?: string;
}

export function createWebApp(dependencies: WebAppDependencies): Hono {
  const { auth, config, mcp, repository } = dependencies;
  const expectedHost = new URL(config.publicOrigin).host;
  const loopbackHost = `127.0.0.1:${config.port}`;
  const clientScript =
    dependencies.clientScript ?? readFileSync(new URL('../public/app.js', import.meta.url), { encoding: 'utf8' });
  const app = new Hono();

  app.use(
    '*',
    secureHeaders({
      contentSecurityPolicy: {
        defaultSrc: ["'none'"],
        scriptSrc: ["'self'"],
        styleSrc: ["'self'"],
        connectSrc: ["'self'"],
        imgSrc: ["'self'", 'data:'],
        fontSrc: ["'self'"],
        baseUri: ["'none'"],
        formAction: ["'self'"],
        frameAncestors: ["'none'"],
      },
      referrerPolicy: 'no-referrer',
      strictTransportSecurity: config.publicOrigin.startsWith('https:') ? 'max-age=63072000; includeSubDomains' : false,
    }),
  );

  app.get('/healthz', (context) => context.json({ status: 'ok' }));

  app.use('*', async (context, next) => {
    if (context.req.path.startsWith('/internal/')) return next();
    if (
      context.req.method === 'GET' &&
      context.req.path === '/api/auth/jwks' &&
      context.req.header('Host') === loopbackHost
    ) {
      return next();
    }
    if (context.req.header('Host') !== expectedHost) return context.text('Invalid Host header.', 400);
    const origin = context.req.header('Origin');
    if (origin !== undefined && origin !== config.publicOrigin) return context.text('Invalid Origin header.', 403);
    await next();
  });

  app.use('*', async (context, next) => {
    if (
      context.req.method === 'GET' &&
      ['/', '/sign-in', '/invite', '/recover', '/dashboard', '/link', '/consent'].includes(context.req.path)
    ) {
      context.header('Cache-Control', 'no-store');
    }
    return next();
  });

  app.use('/api/*', async (context, next) => {
    context.header('Cache-Control', 'no-store');
    return next();
  });
  app.use('/mcp', async (context, next) => {
    context.header('Cache-Control', 'no-store');
    return next();
  });

  registerInternalRoutes(app, {
    config,
    repository,
    isLoopback: dependencies.isLoopback ?? isLoopbackConnection,
  });

  app.get('/assets/app.css', (context) => {
    context.header('Cache-Control', 'no-cache');
    return context.body(stylesheet, 200, { 'Content-Type': 'text/css; charset=utf-8' });
  });
  app.get('/assets/app.js', (context) => {
    context.header('Cache-Control', 'no-cache');
    return context.body(clientScript, 200, { 'Content-Type': 'text/javascript; charset=utf-8' });
  });

  app.all('/api/auth/*', async (context) =>
    withNoStore(await auth.handler(canonicalPublicRequest(context.req.raw, config.publicOrigin))),
  );
  app.all('/.well-known/*', async (context) =>
    withNoStore(await auth.handler(canonicalPublicRequest(context.req.raw, config.publicOrigin))),
  );

  app.post('/api/onboarding/exchange', async (context) => {
    requireSameOrigin(context, config.publicOrigin);
    const body = onboardingExchangeSchema.parse(await readSmallJson(context.req.raw));
    const claim =
      body.kind === 'invitation' ? repository.resolveInvitation(body.token) : repository.resolveRecovery(body.token);
    const handle = body.kind === 'invitation' ? normalizeHandle(body.handle ?? '') : claim.handle;
    if (body.kind === 'invitation' && !repository.isHandleAvailable(handle)) {
      throw new AccessError('conflict', 'That handle is already in use.');
    }
    const ticket = createOnboardingTicket({ ...claim, handle }, config.authSecret);
    context.header('Set-Cookie', onboardingCookieHeader(config.publicOrigin, ticket));
    context.header('Cache-Control', 'no-store');
    return context.json({ ok: true, handle });
  });

  app.post('/api/access/minecraft-link', async (context) => {
    requireSameOrigin(context, config.publicOrigin);
    const body = linkSchema.parse(await readSmallJson(context.req.raw));
    const session = await auth.api.getSession({ headers: context.req.raw.headers });
    if (session === null) return context.json({ error: 'Sign in again before linking Minecraft.' }, 401);
    if (Date.now() - new Date(session.session.createdAt).getTime() > RECENT_AUTHENTICATION_MS) {
      return context.json({ error: 'Sign in again before linking Minecraft.' }, 403);
    }
    const user = repository.consumeMinecraftLinkChallenge(session.user.id, body.code);
    return context.json({ user });
  });

  app.all('/mcp', async (context) =>
    withNoStore(await mcp.fetch(canonicalPublicRequest(context.req.raw, config.publicOrigin))),
  );

  app.get('/', async (context) => {
    const user = await currentUser(auth, repository, context.req.raw.headers);
    return context.redirect(user === null ? '/sign-in' : '/dashboard', 303);
  });
  app.get('/sign-in', (context) => context.html(signInPage()));
  app.get('/invite', (context) => context.html(onboardingPage('invitation')));
  app.get('/recover', (context) => context.html(onboardingPage('recovery')));
  app.get('/dashboard', async (context) => {
    const user = await currentUser(auth, repository, context.req.raw.headers);
    if (user === null) return context.redirect('/sign-in', 303);
    context.header('Set-Cookie', expiredOnboardingCookie(config.publicOrigin));
    return context.html(dashboardPage(user, repository.hasPasskey(user.id)));
  });
  app.get('/link', async (context) => {
    const user = await currentUser(auth, repository, context.req.raw.headers);
    if (user === null) return context.redirect('/sign-in', 303);
    return context.html(linkPage(user));
  });
  app.get('/consent', async (context) => {
    const user = await currentUser(auth, repository, context.req.raw.headers);
    if (user === null) return context.redirect(`/sign-in${new URL(context.req.url).search}`, 303);
    if (user.minecraftAccount === null) {
      return context.html(
        errorPage('Minecraft link required', 'Link a Minecraft account before authorizing MCP.'),
        403,
      );
    }
    const query = new URL(context.req.url).searchParams;
    const clientName = query.get('client_id') ?? 'this MCP client';
    const scopes = (query.get('scope') ?? 'dirt:mcp').split(' ').filter((scope) => scope.length > 0);
    return context.html(consentPage(user, clientName, scopes));
  });

  app.notFound((context) => {
    if (context.req.path.startsWith('/internal/v1/access/')) {
      return context.json(
        {
          callId: validCallIdOrNull(context.req.header('X-Dirt-Call-Id')),
          error: { code: 'not_found', message: 'The requested access-control route does not exist.' },
        },
        404,
      );
    }
    context.header('Cache-Control', 'no-store');
    return context.html(errorPage('Not found', 'That page does not exist.'), 404);
  });
  app.onError((error, context) => {
    if (context.req.path.startsWith('/internal/v1/access/')) {
      const failure = internalError(error);
      const callId = validCallIdOrNull(context.req.header('X-Dirt-Call-Id'));
      return context.json({ callId, error: { code: failure.code, message: failure.message } }, failure.status);
    }
    if (context.req.path.startsWith('/api/')) {
      if (error instanceof AccessError) {
        const status = error.code === 'conflict' ? 409 : error.code === 'not_found' ? 404 : 400;
        return context.json({ error: error.message }, status);
      }
      if (error instanceof z.ZodError || error instanceof SyntaxError) {
        return context.json({ error: 'The request is invalid.' }, 400);
      }
      dependencies.logger
        .child({ component: 'web' })
        .error('web.request_failed', 'Web request failed unexpectedly.', { path: context.req.path });
      return context.json({ error: 'An internal error occurred.' }, 500);
    }
    dependencies.logger
      .child({ component: 'web' })
      .error('web.request_failed', 'Web request failed unexpectedly.', { path: context.req.path });
    context.header('Cache-Control', 'no-store');
    return context.html(errorPage('Something went wrong', 'Please try again.'), 500);
  });

  return app;
}

function expiredOnboardingCookie(publicOrigin: string): string {
  const secure = publicOrigin.startsWith('https://');
  const name = secure ? '__Host-dirt-onboarding' : 'dirt-onboarding';
  return `${name}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0${secure ? '; Secure' : ''}`;
}

function withNoStore(response: Response): Response {
  response.headers.set('Cache-Control', 'no-store');
  return response;
}

function canonicalPublicRequest(request: Request, publicOrigin: string): Request {
  const incoming = new URL(request.url);
  const init: RequestInit & { duplex?: 'half' } = {
    method: request.method,
    headers: request.headers,
    signal: request.signal,
  };
  if (request.body !== null) {
    init.body = request.body;
    init.duplex = 'half';
  }
  return new Request(`${publicOrigin}${incoming.pathname}${incoming.search}`, init);
}

function validCallIdOrNull(value: string | undefined): string | null {
  return value !== undefined && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(value)
    ? value
    : null;
}

async function currentUser(
  auth: DirtAuth,
  repository: AccessRepository,
  headers: Headers,
): Promise<UserSummary | null> {
  const session = await auth.api.getSession({ headers });
  if (session === null) return null;
  try {
    const user = repository.requireUserById(session.user.id);
    return user.status === 'active' ? user : null;
  } catch (error: unknown) {
    if (error instanceof AccessError && error.code === 'not_found') return null;
    throw error;
  }
}

function isLoopbackConnection(context: Context): boolean {
  const address = getConnInfo(context).remote.address;
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1';
}

function requireSameOrigin(context: Context, origin: string): void {
  if (context.req.header('Origin') !== origin || context.req.header('Sec-Fetch-Site') === 'cross-site') {
    throw new AccessError('invalid', 'Same-origin request required.');
  }
}

async function readSmallJson(request: Request): Promise<unknown> {
  if (request.headers.get('Content-Type')?.split(';', 1)[0]?.trim().toLowerCase() !== 'application/json') {
    throw new AccessError('invalid', 'Content-Type must be application/json.');
  }
  const length = request.headers.get('Content-Length');
  if (length !== null && (!/^\d+$/u.test(length) || Number(length) > MAX_BROWSER_BODY_BYTES)) {
    throw new AccessError('invalid', 'Request body is too large.');
  }
  const reader = request.body?.getReader();
  if (reader === undefined) throw new SyntaxError('missing body');
  const chunks: Uint8Array[] = [];
  let total: number;
  try {
    total = await readBrowserChunks(reader, chunks, 0);
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch (error: unknown) {
    if (error instanceof SyntaxError) throw error;
    throw new AccessError('invalid', 'Request body must be valid UTF-8 JSON.');
  }
}

async function readBrowserChunks(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  chunks: Uint8Array[],
  total: number,
): Promise<number> {
  const result = await reader.read();
  if (result.done) return total;
  const nextTotal = total + result.value.byteLength;
  if (nextTotal > MAX_BROWSER_BODY_BYTES) throw new AccessError('invalid', 'Request body is too large.');
  chunks.push(result.value);
  return readBrowserChunks(reader, chunks, nextTotal);
}
