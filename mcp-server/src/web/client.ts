import { passkeyClient } from '@better-auth/passkey/client';
import { createAuthClient } from 'better-auth/client';

const authClient = createAuthClient({ plugins: [passkeyClient()] });
const status = document.querySelector<HTMLElement>('#status');

function report(message: string): void {
  if (status !== null) status.textContent = message;
}

function formString(form: FormData, name: string): string {
  const value = form.get(name);
  return typeof value === 'string' ? value : '';
}

async function responseObject(response: Response): Promise<Record<string, unknown>> {
  try {
    const value: unknown = await response.json();
    return typeof value === 'object' && value !== null && !Array.isArray(value) ? value : {};
  } catch {
    return {};
  }
}

function optionalString(object: Record<string, unknown>, name: string): string | undefined {
  const value = object[name];
  return typeof value === 'string' ? value : undefined;
}

function runUserAction(action: () => Promise<void>): void {
  void action().catch(() => report('The request failed. Check your connection and try again.'));
}

document.querySelector('[data-action="sign-in"]')?.addEventListener('click', () => {
  runUserAction(async () => {
    report('Waiting for your passkey…');
    const result = await authClient.signIn.passkey();
    if (result.error !== null) return report(result.error.message ?? 'Sign-in failed.');
    location.assign(location.search.length > 1 ? `/api/auth/oauth2/authorize${location.search}` : '/dashboard');
  });
});

document.querySelector<HTMLFormElement>('[data-onboarding]')?.addEventListener('submit', (event) => {
  event.preventDefault();
  runUserAction(async () => {
    const form = event.currentTarget;
    const kind = form.dataset.onboarding;
    const token = new URLSearchParams(location.hash.slice(1)).get('token');
    if ((kind !== 'invitation' && kind !== 'recovery') || token === null) {
      return report('This link is incomplete. Request a new one.');
    }
    const formData = new FormData(form);
    report('Checking the secure link…');
    const body = kind === 'invitation' ? { kind, token, handle: formString(formData, 'handle') } : { kind, token };
    const exchange = await fetch('/api/onboarding/exchange', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const exchangeBody = await responseObject(exchange);
    if (!exchange.ok) return report(optionalString(exchangeBody, 'error') ?? 'The link could not be accepted.');
    history.replaceState(null, '', location.pathname);
    const signedOut = await authClient.signOut();
    if (signedOut.error !== null) return report(signedOut.error.message ?? 'Sign out failed. Please try again.');
    report('Create your passkey to finish…');
    const result = await authClient.passkey.addPasskey({
      name: kind === 'recovery' ? 'Recovered passkey' : 'Primary passkey',
      createSession: true,
    });
    if (result.error !== null) return report(result.error.message ?? 'Passkey setup failed.');
    location.assign('/dashboard');
  });
});

document.querySelector('[data-action="sign-out"]')?.addEventListener('click', () => {
  runUserAction(async () => {
    const result = await authClient.signOut();
    if (result.error !== null) return report(result.error.message ?? 'Sign out failed. Please try again.');
    location.assign('/sign-in');
  });
});

document.querySelector<HTMLFormElement>('[data-link]')?.addEventListener('submit', (event) => {
  event.preventDefault();
  runUserAction(async () => {
    const form = new FormData(event.currentTarget);
    const fragmentCode = new URLSearchParams(location.hash.slice(1)).get('code');
    const formCode = formString(form, 'code');
    const code = formCode.length > 0 ? formCode : (fragmentCode ?? '');
    report('Linking Minecraft account…');
    const response = await fetch('/api/access/minecraft-link', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code }),
    });
    const body = await responseObject(response);
    if (!response.ok) return report(optionalString(body, 'error') ?? 'Linking failed.');
    history.replaceState(null, '', '/link');
    location.assign('/dashboard');
  });
});

const linkInput = document.querySelector<HTMLInputElement>('[data-link] input[name="code"]');
if (linkInput !== null) {
  linkInput.value = new URLSearchParams(location.hash.slice(1)).get('code') ?? '';
  if (location.hash.length > 0) history.replaceState(null, '', location.pathname);
}

document.querySelector<HTMLFormElement>('[data-consent]')?.addEventListener('submit', (event) => {
  event.preventDefault();
  runUserAction(async () => {
    const accept =
      event instanceof SubmitEvent && event.submitter instanceof HTMLButtonElement && event.submitter.value === 'allow';
    report(accept ? 'Authorizing client…' : 'Denying request…');
    const response = await fetch('/api/auth/oauth2/consent', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accept, oauth_query: location.search.slice(1) }),
    });
    const body = await responseObject(response);
    const redirect = body.redirect === true ? optionalString(body, 'url') : undefined;
    if (!response.ok || redirect === undefined) {
      return report(optionalString(body, 'error') ?? 'Authorization failed.');
    }
    location.assign(redirect);
  });
});
