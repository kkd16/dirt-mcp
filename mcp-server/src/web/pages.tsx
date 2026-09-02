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
  readonly username?: string;
}

function Shell({ title, pageName, children, username }: ShellProperties): JSX.Element {
  return (
    <html lang="en">
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta name="color-scheme" content="light dark" />
        <meta name="theme-color" content="#f2f5f1" media="(prefers-color-scheme: light)" />
        <meta name="theme-color" content="#101713" media="(prefers-color-scheme: dark)" />
        <title>{title} · Dirt</title>
        <link rel="stylesheet" href={STYLESHEET} />
      </head>
      <body data-page={pageName}>
        <a class="skip-link" href="#main">
          Skip to content
        </a>
        <header class="site-header">
          <a class="brand" href={username === undefined ? '/' : '/dashboard'} aria-label="Dirt home">
            <BrandMark />
            <span>Dirt</span>
          </a>
          {username === undefined ? null : (
            <div class="header-account action-zone">
              <span class="header-username">{username}</span>
              <span class="form-status compact-status" data-form-status role="status" aria-live="polite" />
              <button class="text-button" type="button" data-action="sign-out">
                Sign out
              </button>
            </div>
          )}
        </header>
        <main id="main" tabindex={-1}>
          {children}
        </main>
        <script type="module" src={SCRIPT} />
      </body>
    </html>
  );
}

function BrandMark(): JSX.Element {
  return (
    <svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
      <path d="M3.5 12V3.5H12M20 3.5h8.5V12M28.5 20v8.5H20M12 28.5H3.5V20" />
      <path d="M10.5 9.5h12v12h-12zM16.5 9.5v12M10.5 15.5h12" />
      <path class="coordinate-cell" d="M21.5 20.5h7v7h-7z" />
    </svg>
  );
}

export function SignInPage({ continueToDashboard = false }: { readonly continueToDashboard?: boolean }): JSX.Element {
  return (
    <Shell title={continueToDashboard ? 'Sign in to continue' : 'Sign in'} pageName="sign-in">
      <div class="task-layout">
        <section class="task-card action-zone" aria-labelledby="sign-in-title">
          <p class="eyebrow">Private server access</p>
          <h1 id="sign-in-title">{continueToDashboard ? 'Sign in to continue' : 'Sign in to Dirt'}</h1>
          <p class="task-intro">Use the passkey for your linked Minecraft account.</p>
          <button class="primary-button" type="button" data-action="sign-in">
            Sign in with a passkey
          </button>
          <p class="form-status" data-form-status role="status" aria-live="polite" />
          <p class="task-footnote">Need access? Ask an operator to invite you in Minecraft.</p>
        </section>
      </div>
    </Shell>
  );
}

export function OnboardingPage({
  kind,
  claim,
}: {
  readonly kind: 'invitation' | 'recovery';
  readonly claim: { readonly username: string } | null;
}): JSX.Element {
  const invitation = kind === 'invitation';
  const title = invitation ? 'Create your Dirt passkey' : 'Replace your passkey';
  return (
    <Shell title={invitation ? 'Accept invitation' : 'Recover account'} pageName={kind}>
      <div class="task-layout">
        <section class="task-card action-zone" aria-labelledby="onboarding-title" data-onboarding-page={kind}>
          <p class="eyebrow">{invitation ? 'Minecraft invitation' : 'Account recovery'}</p>
          <h1 id="onboarding-title">{claim === null ? 'Checking your link' : title}</h1>
          {claim === null ? (
            <div data-onboarding-prepare={kind}>
              <p class="task-intro">Verifying the private link from Minecraft…</p>
            </div>
          ) : (
            <div class="onboarding-ready" data-onboarding-ready>
              <div class="verified-player">
                <span class="survey-node" aria-hidden="true" />
                <span>
                  <small>Verified Minecraft account</small>
                  <strong>{claim.username}</strong>
                </span>
              </div>
              <p class="task-intro">
                {invitation
                  ? 'Create a passkey to finish your account. Your Minecraft identity is already linked.'
                  : 'Create a replacement passkey. Existing sessions and MCP access will be revoked.'}
              </p>
              <form data-onboarding={kind}>
                <button class="primary-button" type="submit">
                  {invitation ? 'Create passkey' : 'Replace passkey'}
                </button>
              </form>
            </div>
          )}
          <p class="form-status" data-form-status role="status" aria-live="polite" />
        </section>
      </div>
    </Shell>
  );
}

export function DashboardPage({ model }: { readonly model: DashboardViewModel }): JSX.Element {
  const { user, readiness, clients, passkeys, mcpEndpoint } = model;
  const task = dashboardTask(model);
  return (
    <Shell title="Dashboard" pageName="dashboard" username={user.username}>
      <div class="dashboard-shell">
        <header class="dashboard-intro">
          <p class="eyebrow">{user.username}</p>
          <h1 id="dashboard-task-title">{task.title}</h1>
          <p>{task.detail}</p>
        </header>

        {clients.length === 0 ? <SetupSteps linked={user.minecraftUuid !== null} /> : null}

        <section class={`primary-task task-${task.tone}`} aria-labelledby="dashboard-task-title">
          <div class="task-heading">
            <span class="task-coordinate" aria-hidden="true">
              {task.coordinate}
            </span>
            <p class="eyebrow">{task.label}</p>
          </div>
          {user.minecraftUuid === null ? (
            <div class="task-body task-body-split">
              <p>
                Join the server as <strong>{user.username}</strong>, run <code>/dirt link</code>, then enter the
                one-time code.
              </p>
              <form class="link-form action-zone" data-link>
                <label class="field-label" for="code">
                  <span>Link code</span>
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
          ) : clients.length === 0 ? (
            <div class="task-body">
              <p>Paste this endpoint into your MCP client. Dirt will return here so you can approve access.</p>
              <CopyField value={mcpEndpoint} />
            </div>
          ) : (
            <div class="task-body ready-body">
              <p>{task.action}</p>
              <span class="status-chip">{clients.length} authorized</span>
            </div>
          )}
        </section>

        <div class="status-row" aria-label="Current status">
          <StatusItem
            label="Minecraft"
            value={user.minecraftUuid === null ? 'Not linked' : user.username}
            tone={user.minecraftUuid === null ? 'warning' : 'success'}
          />
          <StatusItem
            label="Paper & FAWE"
            value={readiness.bridgeAvailable ? 'Online' : 'Offline'}
            tone={readiness.bridgeAvailable ? 'success' : 'danger'}
          />
          <StatusItem
            label="MCP tools"
            value={`${readiness.enabledTools} of ${readiness.totalTools}`}
            tone={readiness.enabledTools > 0 ? 'success' : 'warning'}
          />
        </div>

        <section class="detail-stack" aria-label="Account details">
          <DetailGroup
            title="Minecraft account"
            summary={user.minecraftUuid === null ? 'Needs linking' : user.username}
          >
            <dl class="record-list">
              <Record label="Username" value={user.username} />
              <Record label="Link status" value={user.minecraftUuid === null ? 'Not linked' : 'Linked'} />
              <Record label="Online-mode UUID" value={user.minecraftUuid ?? 'Available after linking'} code />
              <Record label="Dirt account ID" value={user.id} code />
              <Record label="Account status" value={user.status === 'active' ? 'Active' : 'Disabled'} />
              <Record label="Created" value={<DateValue value={user.createdAt} />} />
            </dl>
          </DetailGroup>

          <DetailGroup title="Passkeys" summary={`${passkeys.length} enrolled`}>
            <div>
              {passkeys.length === 0 ? (
                <p class="detail-empty">No passkey found.</p>
              ) : (
                <ul class="simple-list">
                  {passkeys.map((passkey) => (
                    <li>
                      <span>{passkey.name}</span>
                      <span>
                        Added <DateValue value={passkey.createdAt} />
                      </span>
                    </li>
                  ))}
                </ul>
              )}
              <p class="supporting-copy">Ask an operator for a recovery link to replace your passkey.</p>
            </div>
          </DetailGroup>

          <DetailGroup
            title="MCP connections"
            summary={clients.length === 0 ? 'None yet' : `${clients.length} authorized`}
          >
            <div>
              <p class="supporting-copy">Endpoint</p>
              <CopyField value={mcpEndpoint} />
              {clients.length === 0 ? (
                <p class="detail-empty">No clients have been authorized.</p>
              ) : (
                <ul class="client-list">
                  {clients.map((client) => (
                    <ClientRecord client={client} />
                  ))}
                </ul>
              )}
            </div>
          </DetailGroup>

          <DetailGroup
            title="Server access"
            summary={readiness.bridgeAvailable ? 'Paper and FAWE online' : 'Paper offline'}
          >
            <div>
              <dl class="record-list">
                <Record label="Bridge" value={readiness.bridgeAvailable ? 'Available' : 'Unavailable'} />
                <Record label="Enabled tools" value={`${readiness.enabledTools} of ${readiness.totalTools}`} />
                <Record label="Access" value="Full Dirt access" />
              </dl>
              <p class="supporting-copy">
                Enabled clients can inspect the live world and make bounded, undoable edits allowed by the server.
              </p>
            </div>
          </DetailGroup>
        </section>
      </div>
    </Shell>
  );
}

function SetupSteps({ linked }: { readonly linked: boolean }): JSX.Element {
  return (
    <ol class="setup-steps" aria-label="Account setup">
      <li class={linked ? 'complete' : 'current'}>
        <span>1</span>
        <div>
          <strong>Minecraft</strong>
          <small>{linked ? 'Linked' : 'Link required'}</small>
        </div>
      </li>
      <li class="complete">
        <span>2</span>
        <div>
          <strong>Passkey</strong>
          <small>Created</small>
        </div>
      </li>
      <li class={linked ? 'current' : 'waiting'}>
        <span>3</span>
        <div>
          <strong>MCP client</strong>
          <small>{linked ? 'Next' : 'Waiting'}</small>
        </div>
      </li>
    </ol>
  );
}

function StatusItem({ label, value, tone }: { readonly label: string; readonly value: string; readonly tone: string }) {
  return (
    <div class="status-item">
      <span class={`status-square tone-${tone}`} aria-hidden="true" />
      <span>
        <small>{label}</small>
        <strong>{value}</strong>
      </span>
    </div>
  );
}

function CopyField({ value }: { readonly value: string }): JSX.Element {
  return (
    <div class="copy-field action-zone">
      <code>{value}</code>
      <button class="secondary-button" type="button" data-copy={value}>
        Copy
      </button>
      <p class="form-status" data-form-status role="status" aria-live="polite" />
    </div>
  );
}

function DetailGroup({
  title,
  summary,
  children,
}: {
  readonly title: string;
  readonly summary: string;
  readonly children: JSX.Element;
}): JSX.Element {
  return (
    <details class="detail-group">
      <summary>
        <strong>{title}</strong>
        <span>{summary}</span>
      </summary>
      <div class="detail-content">{children}</div>
    </details>
  );
}

function Record({
  label,
  value,
  code = false,
}: {
  readonly label: string;
  readonly value: string | JSX.Element;
  readonly code?: boolean;
}): JSX.Element {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{code && typeof value === 'string' ? <code>{value}</code> : value}</dd>
    </div>
  );
}

function ClientRecord({ client }: { readonly client: AuthorizedClientSummary }): JSX.Element {
  const hostname = hostnameFromUrl(client.clientId);
  const label = client.name ?? hostname ?? 'this MCP client';
  return (
    <li>
      <div class="client-heading">
        <strong>{label}</strong>
        <span>Full Dirt access</span>
      </div>
      <dl class="record-list compact-record">
        <Record label="Client ID" value={client.clientId} code />
        <Record label="Scope" value={client.scopes.join(', ')} code />
        <Record label="Authorized" value={<DateValue value={client.authorizedAt} />} />
      </dl>
      <form class="client-disconnect action-zone" data-disconnect-client={client.consentId} data-client-name={label}>
        <button class="secondary-button" type="submit">
          Disconnect
        </button>
        <p class="form-status" data-form-status role="status" aria-live="polite" />
      </form>
    </li>
  );
}

export function ConsentPage({ model }: { readonly model: ConsentViewModel }): JSX.Element {
  const clientHostname = hostnameFromUrl(model.requestedClientId);
  const redirectHostname = model.redirectUri === null ? null : hostnameFromUrl(model.redirectUri);
  const clientName = model.client?.name ?? clientHostname ?? 'MCP client';
  const localhostRedirect = redirectHostname !== null && isLoopbackHostname(redirectHostname);
  return (
    <Shell title="Authorize MCP client" pageName="consent" username={model.user.username}>
      <div class="task-layout">
        <section class="task-card consent-card action-zone">
          <p class="eyebrow">MCP authorization</p>
          <h1>Allow {clientName}?</h1>
          <p class="task-intro">
            This client will act as <strong>{model.user.username}</strong> in the live Minecraft world.
          </p>

          <div class="consent-detail">
            <p class="detail-label">Client</p>
            <strong>{clientHostname ?? model.requestedClientId}</strong>
            <code>{model.requestedClientId}</code>
          </div>
          <div class="consent-detail">
            <p class="detail-label">Returns to</p>
            <strong>{redirectHostname ?? 'Destination not provided'}</strong>
            {model.redirectUri === null ? null : <code>{model.redirectUri}</code>}
            {localhostRedirect ? <p class="inline-warning">This request returns to an app on your device.</p> : null}
          </div>
          <div class="consent-detail">
            <p class="detail-label">Access</p>
            <p>
              Inspect worlds, edit blocks with bounded undo, and run commands through{' '}
              <code>run_minecraft_commands</code>.
            </p>
            <code>{model.scopes.join(' ')}</code>
            <p class="authority-warning">
              Minecraft commands can have console-equivalent authority. Only continue if you recognize the client and
              return address.
            </p>
          </div>

          <form class="consent-actions" data-consent>
            <button class="primary-button danger-button" type="submit" name="decision" value="allow">
              Allow client
            </button>
            <button class="secondary-button" type="submit" name="decision" value="deny">
              Deny
            </button>
          </form>
          <p class="form-status" data-form-status role="status" aria-live="polite" />
        </section>
      </div>
    </Shell>
  );
}

export function ErrorPage({ title, message }: { readonly title: string; readonly message: string }): JSX.Element {
  return (
    <Shell title={title} pageName="error">
      <div class="task-layout">
        <section class="task-card">
          <p class="eyebrow">Dirt</p>
          <h1>{title}</h1>
          <p class="task-intro">{message}</p>
          <a class="text-link" href="/">
            Return home
          </a>
        </section>
      </div>
    </Shell>
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

function dashboardTask(model: DashboardViewModel): {
  readonly title: string;
  readonly detail: string;
  readonly action?: string;
  readonly label: string;
  readonly coordinate: string;
  readonly tone: string;
} {
  if (model.user.minecraftUuid === null) {
    return {
      title: 'Link Minecraft again',
      detail: 'Your account needs a verified Minecraft identity before it can use MCP.',
      label: 'Next step',
      coordinate: '01',
      tone: 'warning',
    };
  }
  if (model.clients.length === 0) {
    return {
      title: 'Connect your MCP client',
      detail: 'Minecraft and your passkey are ready. One connection step remains.',
      label: 'Final setup step',
      coordinate: '03',
      tone: 'active',
    };
  }
  if (!model.readiness.bridgeAvailable) {
    return {
      title: 'Paper is offline',
      detail: 'Your account and MCP authorization are ready; the live server is unavailable.',
      action: 'Account controls remain available while the operator restores Paper and FAWE.',
      label: 'Server status',
      coordinate: '--',
      tone: 'danger',
    };
  }
  if (model.readiness.enabledTools === 0) {
    return {
      title: 'No tools are enabled',
      detail: 'Your account is connected, but the operator has not enabled any Dirt tools.',
      action: 'Ask the server operator to enable the tools you need.',
      label: 'Server status',
      coordinate: '--',
      tone: 'warning',
    };
  }
  return {
    title: 'Dirt is ready',
    detail: 'Your Minecraft account and MCP client are connected.',
    action: 'Open your MCP client to inspect or edit the live world.',
    label: 'Ready',
    coordinate: '✓',
    tone: 'ready',
  };
}
