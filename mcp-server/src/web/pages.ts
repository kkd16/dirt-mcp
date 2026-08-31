import type { UserSummary } from '../access/repository.ts';

const STYLESHEET = '/assets/app.css';
const SCRIPT = '/assets/app.js';

function page(title: string, body: string, pageName: string): string {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="dark light">
  <title>${escapeHtml(title)} · Dirt</title>
  <link rel="stylesheet" href="${STYLESHEET}">
</head>
<body data-page="${escapeHtml(pageName)}">
  <a class="skip-link" href="#main">Skip to content</a>
  <header><a class="brand" href="/">Dirt</a><span>Minecraft MCP</span></header>
  <main id="main" tabindex="-1">${body}</main>
  <div id="status" class="status" role="status" aria-live="polite"></div>
  <script type="module" src="${SCRIPT}"></script>
</body>
</html>`;
}

export function signInPage(): string {
  return page(
    'Sign in',
    `<section class="card narrow">
      <p class="eyebrow">Secure access</p>
      <h1>Sign in with a passkey</h1>
      <p>Use the fingerprint, face, PIN, or security key already registered with your Dirt account.</p>
      <button type="button" data-action="sign-in">Sign in</button>
    </section>`,
    'sign-in',
  );
}

export function onboardingPage(kind: 'invitation' | 'recovery'): string {
  const invitation = kind === 'invitation';
  return page(
    invitation ? 'Accept invitation' : 'Recover account',
    `<section class="card narrow">
      <p class="eyebrow">${invitation ? 'Invitation' : 'Account recovery'}</p>
      <h1>${invitation ? 'Create your Dirt account' : 'Replace your passkey'}</h1>
      <p>The secret in this link stays in your browser and is exchanged only when you continue.</p>
      <form data-onboarding="${kind}">
        ${
          invitation
            ? '<label for="handle">Account handle</label><input id="handle" name="handle" autocomplete="username" minlength="3" maxlength="32" pattern="[a-z0-9][a-z0-9_-]{1,30}[a-z0-9]" required><p class="hint">3–32 lowercase letters, numbers, underscores, or hyphens.</p>'
            : ''
        }
        <button type="submit">${invitation ? 'Create account and passkey' : 'Create replacement passkey'}</button>
      </form>
    </section>`,
    kind,
  );
}

export function dashboardPage(user: UserSummary): string {
  const minecraft = user.minecraftAccount;
  return page(
    'Dashboard',
    `<section class="hero">
      <div><p class="eyebrow">Dashboard</p><h1>Hello, ${escapeHtml(user.handle)}</h1></div>
      <button type="button" class="secondary" data-action="sign-out">Sign out</button>
    </section>
    <div class="grid">
      <section class="card">
        <h2>Minecraft account</h2>
        ${
          minecraft === null
            ? '<p class="state warning">Not linked</p><p>Join the server and request a one-time link code, then enter it here.</p><a class="button" href="/link">Link Minecraft</a>'
            : `<p class="state success">Linked</p><dl><dt>Name</dt><dd>${escapeHtml(minecraft.name)}</dd><dt>UUID</dt><dd><code>${escapeHtml(minecraft.uuid)}</code></dd></dl>`
        }
      </section>
      <section class="card">
        <h2>Authentication</h2>
        <p class="state success">Passkey ready</p>
        <p>Dirt requires user verification for every passkey sign-in. Self-service passkey changes are intentionally disabled; an operator-issued recovery link replaces the credential and revokes existing sessions.</p>
      </section>
      <section class="card">
        <h2>MCP access</h2>
        <p class="state ${minecraft === null ? 'warning' : 'success'}">${minecraft === null ? 'Link Minecraft first' : 'Ready'}</p>
        <p>Compatible MCP clients discover OAuth automatically at this server’s <code>/mcp</code> endpoint.</p>
      </section>
    </div>`,
    'dashboard',
  );
}

export function linkPage(user: UserSummary): string {
  return page(
    'Link Minecraft',
    `<section class="card narrow">
      <p class="eyebrow">Minecraft link</p>
      <h1>Link ${escapeHtml(user.handle)}</h1>
      <p>Enter the one-time code shown by the Minecraft server. Codes expire after ten minutes.</p>
      <form data-link>
        <label for="code">Link code</label>
        <input id="code" name="code" autocomplete="one-time-code" maxlength="24" required>
        <button type="submit">Link account</button>
      </form>
      <p><a href="/dashboard">Back to dashboard</a></p>
    </section>`,
    'link',
  );
}

export function consentPage(user: UserSummary, clientName: string, scopes: readonly string[]): string {
  return page(
    'Authorize MCP client',
    `<section class="card narrow">
      <p class="eyebrow">MCP authorization</p>
      <h1>Allow ${escapeHtml(clientName)}?</h1>
      <p>This client will have full Dirt MCP authority as <strong>${escapeHtml(user.handle)}</strong>, including live world edits, undo, and raw console-equivalent <code>run_minecraft_commands</code> access.</p>
      <h2>Requested access</h2>
      <ul>${scopes.map((scope) => `<li>${escapeHtml(scope)}</li>`).join('')}</ul>
      <form data-consent>
        <button type="submit" name="decision" value="allow">Allow</button>
        <button type="submit" name="decision" value="deny" class="secondary">Deny</button>
      </form>
    </section>`,
    'consent',
  );
}

export function errorPage(title: string, message: string): string {
  return page(
    title,
    `<section class="card narrow"><p class="eyebrow">Dirt</p><h1>${escapeHtml(title)}</h1><p>${escapeHtml(message)}</p><p><a href="/">Return home</a></p></section>`,
    'error',
  );
}

export const stylesheet = `
:root{font-family:Inter,ui-sans-serif,system-ui,sans-serif;color:#e8ece9;background:#0e1411;line-height:1.55;font-synthesis:none}*{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(circle at 15% 0,#1e3a2d 0,transparent 35rem),#0e1411;color:#e8ece9}a{color:#93e5ae}a:focus-visible,button:focus-visible,input:focus-visible{outline:3px solid #ffd166;outline-offset:3px}header{height:4.5rem;display:flex;align-items:center;gap:1rem;padding:0 clamp(1rem,4vw,4rem);border-bottom:1px solid #33423a;background:#101713cc;backdrop-filter:blur(12px)}header span{color:#a9b8af}.brand{font-size:1.3rem;font-weight:800;color:#f7fff9;text-decoration:none}main{max-width:70rem;margin:0 auto;padding:clamp(2rem,6vw,5rem) clamp(1rem,4vw,3rem)}h1{font-size:clamp(2rem,5vw,3.5rem);line-height:1.05;margin:.3rem 0 1rem}h2{margin-top:0;font-size:1.25rem}.eyebrow{text-transform:uppercase;letter-spacing:.13em;font-size:.78rem;color:#93e5ae;font-weight:800}.card{background:#17201b;border:1px solid #35483d;border-radius:1rem;padding:clamp(1.25rem,3vw,2rem);box-shadow:0 1rem 3rem #0005}.narrow{max-width:36rem;margin:4vh auto}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(16rem,1fr));gap:1rem}.hero{display:flex;align-items:end;justify-content:space-between;gap:2rem;margin-bottom:2rem}.hero h1{margin-bottom:0}button,.button{display:inline-flex;align-items:center;justify-content:center;border:0;border-radius:.6rem;padding:.75rem 1rem;background:#7ae39e;color:#07100a;font:inherit;font-weight:800;cursor:pointer;text-decoration:none}button:hover,.button:hover{background:#a0efb9}.secondary{background:#28372f;color:#eef8f1;border:1px solid #4a6254}.secondary:hover{background:#354a3e}form{display:grid;gap:.85rem;margin-top:1.5rem}input{width:100%;font:inherit;padding:.75rem;border-radius:.5rem;border:1px solid #64766b;background:#0d1510;color:#fff}label{font-weight:750}.hint{font-size:.9rem;color:#afbab3;margin:-.5rem 0 .3rem}.state{font-weight:800}.success{color:#93e5ae}.warning{color:#ffd166}dl{display:grid;grid-template-columns:auto 1fr;gap:.4rem 1rem}dt{color:#a9b8af}dd{margin:0;overflow-wrap:anywhere}code{font-size:.87em;overflow-wrap:anywhere}.status{position:fixed;right:1rem;bottom:1rem;max-width:28rem;padding:.8rem 1rem;border-radius:.6rem;background:#26372d;box-shadow:0 .5rem 2rem #0008}.status:empty{display:none}.skip-link{position:absolute;left:-9999px;top:0;background:#fff;color:#000;padding:.5rem}.skip-link:focus{left:.5rem;top:.5rem;z-index:3}@media(max-width:36rem){.hero{display:block}.hero button{margin-top:1rem}}
`;

function escapeHtml(value: string): string {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
}
