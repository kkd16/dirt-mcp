import type { JSX } from 'hono/jsx/jsx-runtime';
import type { AuthorizedClientSummary, OAuthClientSummary, PasskeySummary, UserSummary } from '../access/repository.ts';

const STYLESHEET = '/assets/app.css';
const SCRIPT = '/assets/app.js';
const DATE_FORMAT = new Intl.DateTimeFormat('en', { dateStyle: 'medium' });

export interface ReadinessSummary {
  readonly bridgeAvailable: boolean;
  readonly enabledTools: number;
  readonly totalTools: number;
}

export interface DashboardViewModel {
  readonly user: UserSummary;
  readonly passkeys: readonly PasskeySummary[];
  readonly clients: readonly AuthorizedClientSummary[];
  readonly readiness: ReadinessSummary;
  readonly mcpEndpoint: string;
}

export interface ConsentViewModel {
  readonly user: UserSummary;
  readonly client: OAuthClientSummary | null;
  readonly requestedClientId: string;
  readonly redirectUri: string | null;
  readonly scopes: readonly string[];
}

interface ShellProperties {
  readonly title: string;
  readonly pageName: string;
  readonly children: JSX.Element;
  readonly signedIn?: boolean;
}

function Shell({ title, pageName, children, signedIn = false }: ShellProperties): JSX.Element {
  return (
    <html lang="en">
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta name="color-scheme" content="light dark" />
        <meta name="theme-color" content="#f2f4ef" media="(prefers-color-scheme: light)" />
        <meta name="theme-color" content="#111918" media="(prefers-color-scheme: dark)" />
        <title>{title} · Dirt</title>
        <link rel="stylesheet" href={STYLESHEET} />
      </head>
      <body data-page={pageName}>
        <a class="skip-link" href="#main">
          Skip to content
        </a>
        <header class="site-header">
          <a class="brand" href={signedIn ? '/dashboard' : '/'} aria-label="Dirt home">
            <span class="brand-mark" aria-hidden="true">
              <span />
            </span>
            <span>Dirt</span>
          </a>
          <span class="header-note">Minecraft MCP</span>
          {signedIn ? (
            <div class="header-action action-zone">
              <span class="form-status compact-status" data-form-status role="status" aria-live="polite" />
              <button class="quiet-button" type="button" data-action="sign-out">
                Sign out
              </button>
            </div>
          ) : null}
        </header>
        <main id="main" tabindex={-1}>
          {children}
        </main>
        <script type="module" src={SCRIPT} />
      </body>
    </html>
  );
}

export function PublicPage({ continuation }: { readonly continuation?: 'minecraft-link' }): JSX.Element {
  const linking = continuation === 'minecraft-link';
  return (
    <Shell title={linking ? 'Sign in to link Minecraft' : 'Minecraft MCP'} pageName="home">
      <div class="landing-shell">
        <section class="landing-hero" aria-labelledby="landing-title">
          <div class="survey-coordinate" aria-hidden="true">
            <span>N 64°</span>
            <span>Y +00</span>
          </div>
          <p class="eyebrow">Self-hosted Minecraft MCP</p>
          <h1 id="landing-title">
            MCP for your
            <span>Paper world.</span>
          </h1>
          <p class="lede">Inspect and edit a live Minecraft world from any compatible MCP client.</p>
          <div class="action-zone landing-action">
            <button class="primary-button" type="button" data-action="sign-in">
              {linking ? 'Sign in and continue' : 'Sign in with a passkey'}
            </button>
            <p class="form-status" data-form-status role="status" aria-live="polite">
              {linking ? 'The link code stays in this browser.' : ''}
            </p>
          </div>
          <p class="invitation-note">Access is invite-only. Ask your server operator for an invitation.</p>
        </section>

        <aside class="survey-field" aria-label="Gateway availability">
          <div class="contour contour-one" aria-hidden="true" />
          <div class="contour contour-two" aria-hidden="true" />
          <div class="field-reading">
            <span class="status-dot" aria-hidden="true" />
            <p class="reading-label">Gateway</p>
            <p class="reading-value">Online</p>
            <p class="reading-detail">Sign in for Paper status.</p>
          </div>
          <div class="field-axis" aria-hidden="true">
            <span>bridge</span>
            <span>oauth</span>
            <span>mcp</span>
          </div>
        </aside>

        <section class="landing-notes" aria-label="How Dirt works">
          <article>
            <p class="note-label">Observe</p>
            <h2>Inspect the world.</h2>
            <p>Read blocks, views, players, and server status.</p>
          </article>
          <article>
            <p class="note-label">Shape</p>
            <h2>Make bounded edits.</h2>
            <p>FAWE-backed edits are serialized per world and recorded for undo.</p>
          </article>
          <article>
            <p class="note-label">Control</p>
            <h2>Keep it local.</h2>
            <p>Passkeys and OAuth protect the public edge. Paper stays on loopback.</p>
          </article>
        </section>
      </div>
    </Shell>
  );
}

export function OnboardingPage({ kind }: { readonly kind: 'invitation' | 'recovery' }): JSX.Element {
  const invitation = kind === 'invitation';
  return (
    <Shell title={invitation ? 'Accept invitation' : 'Recover account'} pageName={kind}>
      <TaskLayout marker={invitation ? 'Invitation' : 'Recovery'}>
        <section class="task-card action-zone">
          <p class="eyebrow">{invitation ? 'Invitation' : 'Account recovery'}</p>
          <h1>{invitation ? 'Create your account' : 'Replace your passkey'}</h1>
          <p class="task-intro">
            {invitation
              ? 'Choose a handle, then create a passkey.'
              : 'Creates a new passkey and revokes current sessions and MCP access.'}
          </p>
          <p class="security-note">This link is single-use. Its secret stays in this browser.</p>
          <form data-onboarding={kind}>
            {invitation ? (
              <label class="field-label" for="handle">
                <span>Account handle</span>
                <input
                  id="handle"
                  name="handle"
                  autocomplete="username"
                  minlength={3}
                  maxlength={32}
                  pattern="[a-z0-9][a-z0-9_-]{1,30}[a-z0-9]"
                  aria-describedby="handle-hint"
                  required
                />
                <span class="field-hint" id="handle-hint">
                  3–32 lowercase letters, numbers, underscores, or hyphens.
                </span>
              </label>
            ) : null}
            <button class="primary-button" type="submit">
              {invitation ? 'Create account and passkey' : 'Create replacement passkey'}
            </button>
          </form>
          <p class="form-status" data-form-status role="status" aria-live="polite" />
        </section>
      </TaskLayout>
    </Shell>
  );
}

export function DashboardPage({ model }: { readonly model: DashboardViewModel }): JSX.Element {
  const { user, readiness, clients, passkeys, mcpEndpoint } = model;
  const minecraft = user.minecraftAccount;
  const overall = overallReadiness(model);
  return (
    <Shell title="Dashboard" pageName="dashboard" signedIn>
      <div class="dashboard-shell">
        <section class="dashboard-hero" aria-labelledby="dashboard-title">
          <div>
            <p class="eyebrow">{user.handle}</p>
            <h1 id="dashboard-title">{overall.title}</h1>
            <p>{overall.detail}</p>
          </div>
          <div class={`readiness-seal ${overall.tone}`}>
            <span class="seal-dot" aria-hidden="true" />
            <span>{overall.title}</span>
          </div>
        </section>

        <div class="dashboard-grid">
          <section class="panel panel-wide" aria-labelledby="minecraft-title">
            <PanelHeading label="Identity" title="Minecraft account" id="minecraft-title" />
            {minecraft === null ? (
              <div class="panel-content split-content">
                <div>
                  <p class="section-state warning-state">Not linked</p>
                  <p>
                    Join the server and run <code>/dirt link</code>. Enter the one-time code here within ten minutes.
                  </p>
                </div>
                <form class="link-form action-zone" data-link>
                  <label class="field-label" for="code">
                    <span>One-time link code</span>
                    <input
                      id="code"
                      name="code"
                      autocomplete="one-time-code"
                      inputmode="text"
                      minlength={8}
                      maxlength={24}
                      required
                    />
                  </label>
                  <button class="primary-button" type="submit">
                    Link Minecraft
                  </button>
                  <p class="form-status" data-form-status role="status" aria-live="polite" />
                </form>
              </div>
            ) : (
              <dl class="record-list identity-record">
                <div>
                  <dt>Status</dt>
                  <dd class="success-state">Linked</dd>
                </div>
                <div>
                  <dt>Player</dt>
                  <dd>{minecraft.name}</dd>
                </div>
                <div>
                  <dt>Online-mode UUID</dt>
                  <dd>
                    <code>{minecraft.uuid}</code>
                  </dd>
                </div>
              </dl>
            )}
          </section>

          <section class="panel panel-left" aria-labelledby="connection-title">
            <PanelHeading label="Paper bridge" title="Connection" id="connection-title" />
            <div class="connection-reading">
              <p class={`section-state ${readiness.bridgeAvailable ? 'success-state' : 'danger-state'}`}>
                {readiness.bridgeAvailable ? 'Paper and FAWE online' : 'Paper offline'}
              </p>
              <p class="tool-count">
                <span>{readiness.enabledTools}</span>
                <span>of {readiness.totalTools} MCP tools enabled</span>
              </p>
              <p class="supporting-copy">
                {readiness.bridgeAvailable ? 'Capabilities loaded from Paper.' : 'Account controls remain available.'}
              </p>
            </div>
          </section>

          <section class="panel" aria-labelledby="endpoint-title">
            <PanelHeading label="Client setup" title="MCP endpoint" id="endpoint-title" />
            <p class="supporting-copy">Use this URL in your MCP client.</p>
            <div class="copy-field action-zone">
              <code>{mcpEndpoint}</code>
              <button class="quiet-button" type="button" data-copy={mcpEndpoint}>
                Copy
              </button>
              <p class="form-status" data-form-status role="status" aria-live="polite" />
            </div>
          </section>

          <section class="panel panel-wide" aria-labelledby="clients-title">
            <PanelHeading label="OAuth" title="Authorized clients" id="clients-title" />
            {clients.length === 0 ? (
              <div class="empty-state">
                <p>No authorized clients.</p>
                <p>Connect one using the endpoint above.</p>
              </div>
            ) : (
              <ul class="client-list">
                {clients.map((client) => {
                  const hostname = hostnameFromUrl(client.clientId);
                  return (
                    <li>
                      <div class="client-heading">
                        <div>
                          <h3>{client.name ?? hostname ?? 'MCP client'}</h3>
                          {client.name !== null && hostname !== null ? <p>{hostname}</p> : null}
                        </div>
                        <span>Full Dirt access</span>
                      </div>
                      <dl class="record-list compact-record">
                        <div>
                          <dt>Client ID</dt>
                          <dd>
                            <code>{client.clientId}</code>
                          </dd>
                        </div>
                        <div>
                          <dt>Access</dt>
                          <dd>{client.scopes.join(', ')}</dd>
                        </div>
                        <div>
                          <dt>Authorized</dt>
                          <dd>
                            <DateValue value={client.authorizedAt} />
                          </dd>
                        </div>
                      </dl>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>

          <section class="panel panel-left" aria-labelledby="security-title">
            <PanelHeading label="Security" title="Passkeys" id="security-title" />
            {passkeys.length === 0 ? (
              <p class="danger-state">No passkey found.</p>
            ) : (
              <ul class="passkey-list">
                {passkeys.map((passkey) => (
                  <li>
                    <span>{passkey.name}</span>
                    <DateValue value={passkey.createdAt} />
                  </li>
                ))}
              </ul>
            )}
            <p class="supporting-copy">
              For a replacement, ask your operator for a recovery link. This revokes sessions and MCP access.
            </p>
          </section>

          <section class="panel" aria-labelledby="account-title">
            <PanelHeading label="Dirt account" title="Account record" id="account-title" />
            <dl class="record-list">
              <div>
                <dt>Handle</dt>
                <dd>{user.handle}</dd>
              </div>
              <div>
                <dt>Status</dt>
                <dd class="success-state">Active</dd>
              </div>
              <div>
                <dt>Created</dt>
                <dd>
                  <DateValue value={user.createdAt} />
                </dd>
              </div>
            </dl>
          </section>
        </div>
      </div>
    </Shell>
  );
}

export function ConsentPage({ model }: { readonly model: ConsentViewModel }): JSX.Element {
  const clientHostname = hostnameFromUrl(model.requestedClientId);
  const redirectHostname = model.redirectUri === null ? null : hostnameFromUrl(model.redirectUri);
  const clientName = model.client?.name ?? clientHostname ?? 'MCP client';
  const localhostRedirect = redirectHostname !== null && isLoopbackHostname(redirectHostname);
  return (
    <Shell title="Authorize MCP client" pageName="consent" signedIn>
      <TaskLayout marker="Authorization">
        <section class="task-card consent-card action-zone">
          <p class="eyebrow">Live-world authority</p>
          <h1>Allow {clientName}?</h1>
          <p class="task-intro">
            This client will act as <strong>{model.user.handle}</strong> in a live Minecraft world. Only continue if you
            recognize the client and destination.
          </p>

          <div class="destination-block">
            <p class="note-label">Client identity</p>
            <p class="destination-name">{clientHostname ?? model.requestedClientId}</p>
            <code>{model.requestedClientId}</code>
          </div>
          <div class="destination-block">
            <p class="note-label">Returns to</p>
            <p class="destination-name">{redirectHostname ?? 'Destination not provided'}</p>
            {model.redirectUri === null ? null : <code>{model.redirectUri}</code>}
            {localhostRedirect ? (
              <p class="security-warning">This request returns to a local app on your device.</p>
            ) : null}
          </div>

          <div class="authority-block">
            <p class="note-label">Requested authority</p>
            <ul>
              <li>Inspect worlds, blocks, server context, and online player context.</li>
              <li>Edit live blocks and use bounded undo history.</li>
              <li>
                Run Minecraft commands through <code>run_minecraft_commands</code>.
              </li>
            </ul>
            <p class="scope-line">
              Scope: <code>{model.scopes.join(' ')}</code>
            </p>
          </div>

          <form class="consent-actions" data-consent>
            <button class="primary-button danger-button" type="submit" name="decision" value="allow">
              Allow client
            </button>
            <button class="quiet-button" type="submit" name="decision" value="deny">
              Deny
            </button>
          </form>
          <p class="form-status" data-form-status role="status" aria-live="polite" />
        </section>
      </TaskLayout>
    </Shell>
  );
}

export function ErrorPage({ title, message }: { readonly title: string; readonly message: string }): JSX.Element {
  return (
    <Shell title={title} pageName="error">
      <TaskLayout marker="Dirt">
        <section class="task-card">
          <p class="eyebrow">Error</p>
          <h1>{title}</h1>
          <p class="task-intro">{message}</p>
          <a class="text-link" href="/">
            Return home
          </a>
        </section>
      </TaskLayout>
    </Shell>
  );
}

function TaskLayout({ marker, children }: { readonly marker: string; readonly children: JSX.Element }): JSX.Element {
  return (
    <div class="task-layout">
      <div class="task-marker" aria-hidden="true">
        <span>{marker}</span>
        <span>Field station</span>
      </div>
      {children}
    </div>
  );
}

function PanelHeading({ label, title, id }: { readonly label: string; readonly title: string; readonly id: string }) {
  return (
    <header class="panel-heading">
      <p class="note-label">{label}</p>
      <h2 id={id}>{title}</h2>
    </header>
  );
}

function DateValue({ value }: { readonly value: string }): JSX.Element {
  return <time datetime={value}>{DATE_FORMAT.format(new Date(value))}</time>;
}

function hostnameFromUrl(value: string): string | null {
  const parsed = URL.parse(value);
  return parsed === null ? null : parsed.hostname;
}

function isLoopbackHostname(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '::1';
}

function overallReadiness(model: DashboardViewModel): {
  readonly title: string;
  readonly detail: string;
  readonly tone: string;
} {
  if (model.user.minecraftAccount === null) {
    return {
      title: 'Link Minecraft',
      detail: 'Link your account before connecting a client.',
      tone: 'warning-seal',
    };
  }
  if (!model.readiness.bridgeAvailable) {
    return {
      title: 'Paper offline',
      detail: 'Account controls remain available.',
      tone: 'danger-seal',
    };
  }
  if (model.readiness.enabledTools === 0) {
    return {
      title: 'No tools enabled',
      detail: 'Ask the operator to enable Dirt tools.',
      tone: 'warning-seal',
    };
  }
  return {
    title: 'MCP ready',
    detail: 'Minecraft and Paper are connected.',
    tone: 'ready-seal',
  };
}
