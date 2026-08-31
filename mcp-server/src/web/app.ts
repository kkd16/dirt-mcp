import { getConnInfo } from '@hono/node-server/conninfo';
import { serveStatic } from '@hono/node-server/serve-static';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Hono, type Context } from 'hono';
import { jsxRenderer } from 'hono/jsx-renderer';
import { routePath } from 'hono/route';
import { secureHeaders } from 'hono/secure-headers';
import * as z from 'zod';
import { AccessError, type AccessRepository, type UserSummary } from '../access/repository.ts';
import { internalError, readJsonBody, registerInternalRoutes, validCallIdOrNull } from '../access/routes.ts';
import {
  createOnboardingTicket,
  normalizeHandle,
  onboardingCookieName,
  onboardingCookieHeader,
  SESSION_FRESH_AGE_SECONDS,
  type DirtAuth,
} from '../auth.ts';
import type { BridgeClient } from '../bridge/client.ts';
import { BRIDGE_ROUTES, BridgeCapabilitiesSchema } from '../bridge/contract.ts';
import type { RuntimeConfig } from '../config.ts';
import { safeErrorFields, type DirtLogger } from '../logging.ts';
import type { DirtMcpHandler } from '../mcp-http.ts';
import { MCP_TOOL_OPERATIONS, toolConfigurationFromCapabilities } from '../tools/configuration.ts';
import {
  ConsentPage,
  DashboardPage,
  ErrorPage,
  OnboardingPage,
  PublicPage,
  type DashboardViewModel,
  type ReadinessSummary,
} from './pages.tsx';

const onboardingTokenSchema = z.string().min(20).max(256);
const onboardingExchangeSchema = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('invitation'), token: onboardingTokenSchema, handle: z.string().max(64) }).strict(),
  z.object({ kind: z.literal('recovery'), token: onboardingTokenSchema }).strict(),
]);
const linkSchema = z.object({ code: z.string().trim().min(8).max(24) }).strict();
const RECENT_AUTHENTICATION_MS = SESSION_FRESH_AGE_SECONDS * 1_000;
const PingResponseSchema = z.object({ status: z.literal('ok') }).strict();
const SENSITIVE_OAUTH_PATHS = new Set([
  '/api/auth/oauth2/authorize',
  '/api/auth/oauth2/consent',
  '/api/auth/oauth2/continue',
]);

interface WebSession {
  readonly session: { readonly createdAt: Date };
  readonly user: { readonly id: string };
}

type WebAuth = Pick<DirtAuth, 'handler'> & {
  readonly api: {
    readonly getSession: (context: { readonly headers: Headers }) => Promise<WebSession | null>;
  };
};

interface WebAppDependencies {
  readonly auth: WebAuth;
  readonly bridge: Pick<BridgeClient, 'request'>;
  readonly config: RuntimeConfig;
  readonly logger: DirtLogger;
  readonly mcp: DirtMcpHandler;
  readonly repository: AccessRepository;
  readonly isLoopback?: (context: Context) => boolean;
  readonly assetRoot?: string;
}

export function createWebApp(dependencies: WebAppDependencies): Hono {
  const { auth, bridge, config, mcp, repository } = dependencies;
  const expectedHost = new URL(config.publicOrigin).host;
  const loopbackHost = `127.0.0.1:${config.port}`;
  const assetRoot = dependencies.assetRoot ?? fileURLToPath(new URL('../public', import.meta.url));
  const app = new Hono();

  app.use('*', jsxRenderer());

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
      permissionsPolicy: {
        camera: false,
        geolocation: false,
        microphone: false,
        payment: false,
        publickeyCredentialsGet: ['self'],
        usb: false,
      },
      strictTransportSecurity: false,
      xContentTypeOptions: false,
      xFrameOptions: 'DENY',
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
    const path = context.req.path;
    if (
      path.startsWith('/api/') ||
      path === '/mcp' ||
      (context.req.method === 'GET' && ['/', '/invite', '/recover', '/dashboard', '/consent'].includes(path))
    ) {
      context.header('Cache-Control', 'no-store');
    }
    return next();
  });

  registerInternalRoutes(app, {
    config,
    repository,
    isLoopback: dependencies.isLoopback ?? isLoopbackConnection,
  });

  app.use('/assets/*', async (context, next) => {
    await next();
    if (context.res.status === 200) {
      context.res.headers.set(
        'Cache-Control',
        /-[a-zA-Z0-9_-]{8,}\./u.test(context.req.path) ? 'public, max-age=31536000, immutable' : 'no-cache',
      );
    }
  });
  app.use('/assets/*', serveStatic({ root: assetRoot }));

  app.use('/api/auth/oauth2/*', async (context, next) => {
    // Better Auth matches its endpoint routes against URL.pathname without
    // decoding percent escapes. Use the same value so an alternate spelling
    // is either guarded by both routers or rejected by Better Auth.
    const oauthPath = new URL(context.req.url).pathname;
    if (!SENSITIVE_OAUTH_PATHS.has(oauthPath)) return next();
    const sessionUser = await currentSessionUser(auth, repository, context.req.raw.headers);
    const authorizationRequest = oauthPath === '/api/auth/oauth2/authorize' && context.req.method === 'GET';
    if (sessionUser === null || !isRecentlyAuthenticated(sessionUser.sessionCreatedAt)) {
      if (authorizationRequest) {
        return context.redirect(`/${new URL(context.req.url).search}`, 303);
      }
      return context.json({ error: 'Recent passkey authentication is required.' }, 403);
    }
    if (sessionUser.user.minecraftAccount === null) {
      if (authorizationRequest) {
        context.status(403);
        return context.render(
          ErrorPage({ title: 'Minecraft link required', message: 'Link a Minecraft account before authorizing MCP.' }),
        );
      }
      return context.json({ error: 'Link a Minecraft account before authorizing MCP.' }, 403);
    }
    if (context.req.method !== 'GET') requireSameOrigin(context, config.publicOrigin);
    return next();
  });

  app.all('/api/auth/*', async (context) =>
    withNoStore(await auth.handler(canonicalPublicRequest(context.req.raw, config.publicOrigin))),
  );
  app.all('/.well-known/*', async (context) =>
    withNoStore(await auth.handler(canonicalPublicRequest(context.req.raw, config.publicOrigin))),
  );

  app.post('/api/onboarding/exchange', async (context) => {
    requireSameOrigin(context, config.publicOrigin);
    const body = await readJsonBody(context.req.raw, onboardingExchangeSchema);
    const claim =
      body.kind === 'invitation' ? repository.resolveInvitation(body.token) : repository.resolveRecovery(body.token);
    const handle = body.kind === 'invitation' ? normalizeHandle(body.handle) : claim.handle;
    if (body.kind === 'invitation' && !repository.isHandleAvailable(handle)) {
      throw new AccessError('conflict', 'That handle is already in use.');
    }
    const ticket = createOnboardingTicket({ ...claim, handle }, config.authSecret);
    context.header('Set-Cookie', onboardingCookieHeader(config.publicOrigin, ticket));
    return context.json({ ok: true, handle });
  });

  app.post('/api/access/minecraft-link', async (context) => {
    requireSameOrigin(context, config.publicOrigin);
    const body = await readJsonBody(context.req.raw, linkSchema);
    const sessionUser = await currentSessionUser(auth, repository, context.req.raw.headers);
    if (sessionUser === null) return context.json({ error: 'Sign in again before linking Minecraft.' }, 401);
    if (!isRecentlyAuthenticated(sessionUser.sessionCreatedAt)) {
      return context.json({ error: 'Sign in again before linking Minecraft.' }, 403);
    }
    const user = repository.consumeMinecraftLinkChallenge(sessionUser.user.id, body.code);
    return context.json({ user });
  });

  app.all('/mcp', async (context) =>
    withNoStore(await mcp.fetch(canonicalPublicRequest(context.req.raw, config.publicOrigin))),
  );

  app.get('/', async (context) => {
    const sessionUser = await currentSessionUser(auth, repository, context.req.raw.headers);
    const requestUrl = new URL(context.req.url);
    if (requestUrl.searchParams.has('client_id')) {
      if (sessionUser !== null && isRecentlyAuthenticated(sessionUser.sessionCreatedAt)) {
        return context.redirect(`/api/auth/oauth2/authorize${requestUrl.search}`, 303);
      }
      return context.render(PublicPage({}));
    }
    if (sessionUser !== null) return context.redirect('/dashboard', 303);
    return context.render(PublicPage({}));
  });
  app.get('/invite', (context) => context.render(OnboardingPage({ kind: 'invitation' })));
  app.get('/recover', (context) => context.render(OnboardingPage({ kind: 'recovery' })));
  app.get('/dashboard', async (context) => {
    const sessionUser = await currentSessionUser(auth, repository, context.req.raw.headers);
    if (sessionUser === null) return context.render(PublicPage({ continuation: 'minecraft-link' }));
    context.header('Set-Cookie', expiredOnboardingCookie(config.publicOrigin));
    const model = await dashboardViewModel(bridge, repository, sessionUser.user, config.publicOrigin);
    return context.render(DashboardPage({ model }));
  });
  app.get('/consent', async (context) => {
    const sessionUser = await currentSessionUser(auth, repository, context.req.raw.headers);
    if (sessionUser === null || !isRecentlyAuthenticated(sessionUser.sessionCreatedAt)) {
      return context.redirect(`/${new URL(context.req.url).search}`, 303);
    }
    if (sessionUser.user.minecraftAccount === null) {
      context.status(403);
      return context.render(
        ErrorPage({ title: 'Minecraft link required', message: 'Link a Minecraft account before authorizing MCP.' }),
      );
    }
    const query = new URL(context.req.url).searchParams;
    const requestedClientId = query.get('client_id') ?? 'Unknown MCP client';
    const scopes = (query.get('scope') ?? 'dirt:mcp').split(' ').filter((scope) => scope.length > 0);
    return context.render(
      ConsentPage({
        model: {
          user: sessionUser.user,
          client: repository.findOAuthClient(requestedClientId),
          requestedClientId,
          redirectUri: query.get('redirect_uri'),
          scopes,
        },
      }),
    );
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
    context.status(404);
    return context.render(ErrorPage({ title: 'Not found', message: 'That page does not exist.' }));
  });
  app.onError((error, context) => {
    if (context.req.path.startsWith('/internal/v1/access/')) {
      const failure = internalError(error);
      const callId = validCallIdOrNull(context.req.header('X-Dirt-Call-Id'));
      if (failure.code === 'internal_error') {
        dependencies.logger
          .child({ component: 'access_control', call_id: callId })
          .error('access_control.request_failed', 'Internal access-control request failed.', {
            route: routePath(context),
            ...safeErrorFields(error),
          });
      }
      return context.json({ callId, error: { code: failure.code, message: failure.message } }, failure.status);
    }
    const apiRequest = context.req.path.startsWith('/api/');
    if (apiRequest) {
      const failure = internalError(error);
      if (failure.code !== 'internal_error') {
        return context.json({ error: failure.message }, failure.status);
      }
    }
    dependencies.logger
      .child({ component: 'web' })
      .error('web.request_failed', 'Web request failed unexpectedly.', { path: context.req.path });
    if (apiRequest) return context.json({ error: 'An internal error occurred.' }, 500);
    context.header('Cache-Control', 'no-store');
    context.status(500);
    return context.render(ErrorPage({ title: 'Something went wrong', message: 'Please try again.' }));
  });

  return app;
}

async function dashboardViewModel(
  bridge: Pick<BridgeClient, 'request'>,
  repository: AccessRepository,
  user: UserSummary,
  publicOrigin: string,
): Promise<DashboardViewModel> {
  const readiness = await dashboardReadiness(bridge);
  return {
    user,
    passkeys: repository.listPasskeys(user.id),
    clients: repository.listAuthorizedClients(user.id),
    readiness,
    mcpEndpoint: `${publicOrigin}/mcp`,
  };
}

async function dashboardReadiness(bridge: Pick<BridgeClient, 'request'>): Promise<ReadinessSummary> {
  const [ping, capabilities] = await Promise.allSettled([
    bridge.request(BRIDGE_ROUTES.ping, randomUUID(), PingResponseSchema),
    bridge.request(BRIDGE_ROUTES.capabilities, randomUUID(), BridgeCapabilitiesSchema),
  ]);
  const configuration =
    capabilities.status === 'fulfilled' ? toolConfigurationFromCapabilities(capabilities.value) : null;
  return {
    bridgeAvailable: ping.status === 'fulfilled' && capabilities.status === 'fulfilled',
    enabledTools: configuration === null ? 0 : Object.values(configuration).filter(Boolean).length,
    totalTools: Object.keys(MCP_TOOL_OPERATIONS).length,
  };
}

function expiredOnboardingCookie(publicOrigin: string): string {
  const secure = publicOrigin.startsWith('https://');
  return `${onboardingCookieName(publicOrigin)}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0${secure ? '; Secure' : ''}`;
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

async function currentSessionUser(
  auth: WebAuth,
  repository: AccessRepository,
  headers: Headers,
): Promise<{ readonly user: UserSummary; readonly sessionCreatedAt: number } | null> {
  const session = await auth.api.getSession({ headers });
  if (session === null) return null;
  try {
    const user = repository.requireUserById(session.user.id);
    if (user.status !== 'active') return null;
    const sessionCreatedAt = new Date(session.session.createdAt).getTime();
    return Number.isFinite(sessionCreatedAt) ? { user, sessionCreatedAt } : null;
  } catch (error: unknown) {
    if (error instanceof AccessError && error.code === 'not_found') return null;
    throw error;
  }
}

function isRecentlyAuthenticated(createdAt: number, now = Date.now()): boolean {
  const age = now - createdAt;
  return age >= 0 && age <= RECENT_AUTHENTICATION_MS;
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
