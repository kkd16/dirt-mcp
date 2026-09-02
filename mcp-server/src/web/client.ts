import { passkeyClient } from '@better-auth/passkey/client';
import { createAuthClient } from 'better-auth/client';

const authClient = createAuthClient({ plugins: [passkeyClient()] });

type StatusTone = 'error' | 'success' | 'neutral';

function report(source: Element, message: string, tone: StatusTone = 'neutral'): void {
  const zone = source.closest('.action-zone');
  const status = zone?.querySelector<HTMLElement>('[data-form-status]');
  if (status === null || status === undefined) return;
  status.textContent = message;
  status.dataset.tone = tone;
}

function formString(form: FormData, name: string): string {
  const value = form.get(name);
  return typeof value === 'string' ? value : '';
}

async function responseObject(response: Response): Promise<Record<string, unknown>> {
  try {
    const value: unknown = await response.json();
    return isRecord(value) ? value : {};
  } catch {
    return {};
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function optionalString(object: Record<string, unknown>, name: string): string | undefined {
  const value = object[name];
  return typeof value === 'string' ? value : undefined;
}

function runUserAction(source: HTMLElement, action: () => Promise<void>): void {
  const controls = actionControls(source);
  for (const control of controls) control.disabled = true;
  source.setAttribute('aria-busy', 'true');
  void action()
    .catch(() => report(source, 'Request failed. Check your connection.', 'error'))
    .finally(() => {
      source.removeAttribute('aria-busy');
      for (const control of controls) control.disabled = false;
    });
}

function actionControls(source: HTMLElement): HTMLButtonElement[] {
  const form = source instanceof HTMLFormElement ? source : source.closest('form');
  if (form !== null) return Array.from(form.querySelectorAll<HTMLButtonElement>('button'));
  if (source instanceof HTMLButtonElement) return [source];
  return Array.from(source.querySelectorAll<HTMLButtonElement>('button'));
}

document.querySelector<HTMLButtonElement>('[data-action="sign-in"]')?.addEventListener('click', (event) => {
  const button = event.currentTarget;
  if (!(button instanceof HTMLButtonElement)) return;
  runUserAction(button, async () => {
    report(button, 'Use your passkey…');
    const result = await authClient.signIn.passkey();
    if (result.error !== null) {
      report(button, result.error.message ?? 'Sign-in failed.', 'error');
      return;
    }
    if (new URLSearchParams(location.search).has('client_id')) {
      location.assign(`/api/auth/oauth2/authorize${location.search}`);
      return;
    }
    location.reload();
  });
});

const onboardingPage = document.querySelector<HTMLElement>('[data-onboarding-page]');
if (onboardingPage !== null) {
  const token = new URLSearchParams(location.hash.slice(1)).get('token');
  const needsPreparation = onboardingPage.querySelector('[data-onboarding-prepare]') !== null;
  if (token !== null) {
    history.replaceState(null, '', location.pathname);
    const ready = onboardingPage.querySelector<HTMLElement>('[data-onboarding-ready]');
    if (ready !== null) ready.hidden = true;
    const title = onboardingPage.querySelector<HTMLElement>('#onboarding-title');
    if (title !== null) title.textContent = 'Checking your link';
    report(onboardingPage, 'Verifying the private link…');
    runUserAction(onboardingPage, async () => {
      const kind = onboardingPage.dataset.onboardingPage;
      if (kind !== 'invitation' && kind !== 'recovery') {
        report(onboardingPage, 'This setup page is invalid. Ask an operator for a new link.', 'error');
        return;
      }
      const exchange = await fetch('/api/onboarding/exchange', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ kind, token }),
      });
      const exchangeBody = await responseObject(exchange);
      if (!exchange.ok) {
        report(onboardingPage, optionalString(exchangeBody, 'error') ?? 'This link could not be verified.', 'error');
        return;
      }
      location.reload();
    });
  } else if (needsPreparation) {
    report(onboardingPage, 'This link is invalid or expired. Ask an operator for a new one.', 'error');
  }
}

document.querySelector<HTMLFormElement>('[data-onboarding]')?.addEventListener('submit', (event) => {
  const form = event.currentTarget;
  if (!(form instanceof HTMLFormElement)) return;
  event.preventDefault();
  runUserAction(form, async () => {
    const kind = form.dataset.onboarding;
    if (kind !== 'invitation' && kind !== 'recovery') {
      report(form, 'This setup page is invalid. Ask an operator for a new link.', 'error');
      return;
    }
    const signedOut = await authClient.signOut();
    if (signedOut.error !== null) {
      report(form, signedOut.error.message ?? 'Sign out failed. Try again.', 'error');
      return;
    }
    report(form, 'Create a passkey…');
    const result = await authClient.passkey.addPasskey({
      name: kind === 'recovery' ? 'Recovered passkey' : 'Primary passkey',
      createSession: true,
    });
    if (result.error !== null) {
      report(form, result.error.message ?? 'Passkey setup failed.', 'error');
      return;
    }
    location.assign('/dashboard');
  });
});

document.querySelector<HTMLButtonElement>('[data-action="sign-out"]')?.addEventListener('click', (event) => {
  const button = event.currentTarget;
  if (!(button instanceof HTMLButtonElement)) return;
  runUserAction(button, async () => {
    report(button, 'Signing out…');
    const result = await authClient.signOut();
    if (result.error !== null) {
      report(button, result.error.message ?? 'Sign out failed. Try again.', 'error');
      return;
    }
    location.assign('/');
  });
});

document.querySelector<HTMLFormElement>('[data-link]')?.addEventListener('submit', (event) => {
  const formElement = event.currentTarget;
  if (!(formElement instanceof HTMLFormElement)) return;
  event.preventDefault();
  runUserAction(formElement, async () => {
    const form = new FormData(formElement);
    const code = formString(form, 'code');
    report(formElement, 'Linking Minecraft…');
    const response = await fetch('/api/access/minecraft-link', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code }),
    });
    const body = await responseObject(response);
    if (!response.ok) {
      report(formElement, optionalString(body, 'error') ?? 'Linking failed.', 'error');
      return;
    }
    history.replaceState(null, '', '/dashboard');
    location.reload();
  });
});

const linkInput = document.querySelector<HTMLInputElement>('[data-link] input[name="code"]');
if (linkInput !== null) {
  const fragmentCode = new URLSearchParams(location.hash.slice(1)).get('code');
  if (fragmentCode !== null) linkInput.value = fragmentCode;
  if (location.hash.length > 0) history.replaceState(null, '', `${location.pathname}${location.search}`);
}

for (const button of document.querySelectorAll<HTMLButtonElement>('[data-copy]')) {
  button.addEventListener('click', () => {
    runUserAction(button, async () => {
      const value = button.dataset.copy;
      if (value === undefined || navigator.clipboard === undefined) {
        report(button, 'Copy unavailable.', 'error');
        return;
      }
      await navigator.clipboard.writeText(value);
      report(button, 'Copied.', 'success');
    });
  });
}

for (const form of document.querySelectorAll<HTMLFormElement>('[data-disconnect-client]')) {
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const consentId = form.dataset.disconnectClient;
    const clientName = form.dataset.clientName ?? 'this MCP client';
    if (
      consentId === undefined ||
      !window.confirm(`Disconnect ${clientName}? It will need to be authorized again before it can use Dirt.`)
    ) {
      return;
    }
    runUserAction(form, async () => {
      report(form, 'Disconnecting client…');
      const response = await fetch('/api/access/mcp-clients/revoke', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ consentId }),
      });
      const body = await responseObject(response);
      if (!response.ok) {
        report(form, optionalString(body, 'error') ?? 'The client could not be disconnected.', 'error');
        return;
      }
      location.reload();
    });
  });
}

document.querySelector<HTMLFormElement>('[data-consent]')?.addEventListener('submit', (event) => {
  const form = event.currentTarget;
  if (!(form instanceof HTMLFormElement)) return;
  event.preventDefault();
  runUserAction(form, async () => {
    const accept =
      event instanceof SubmitEvent && event.submitter instanceof HTMLButtonElement && event.submitter.value === 'allow';
    report(form, accept ? 'Allowing client…' : 'Denying…');
    const response = await fetch('/api/auth/oauth2/consent', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accept, oauth_query: location.search.slice(1) }),
    });
    const body = await responseObject(response);
    const redirect = body.redirect === true ? optionalString(body, 'url') : undefined;
    if (!response.ok || redirect === undefined) {
      report(form, optionalString(body, 'error') ?? 'Authorization failed.', 'error');
      return;
    }
    location.assign(redirect);
  });
});
