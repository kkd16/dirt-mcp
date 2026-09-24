# Architecture

Dirt MCP is a self-hosted dashboard and authenticated MCP endpoint for a live
Paper server. Paper owns world state and persistence; Dirt exposes bounded
operations against already-loaded worlds without reading or writing Minecraft
region files.

```text
Browser / remote MCP client
          | HTTPS
          v
        Caddy
          | HTTP on 127.0.0.1
          v
Dashboard + OAuth + MCP service ---- SQLite accounts and credentials
          | authenticated HTTP on 127.0.0.1
          v
    Dirt MCP Paper plugin ----------> private account-control API
          |                            on 127.0.0.1
          v
    FAWE and Paper-owned live worlds
```

## Components and ownership

### Web and MCP service

`mcp-server` is one Node.js process that owns the user-facing and agent-facing
interfaces:

- server-rendered dashboard and view-only tool-reference pages plus their small
  WebAuthn browser client;
- invite-only passkey accounts, browser sessions, recovery, and Minecraft links;
- OAuth authorization and the stateless Streamable HTTP `POST /mcp` endpoint;
- the public tool schemas, annotations, and fixed input defaults;
- the shared human-facing tool catalog used by web and Paper commands;
- Zod input, success-output, and structured failure schemas;
- environment validation and authenticated loopback clients; and
- a private loopback API used by Paper's access commands.

Each MCP request authenticates an OAuth access token, then checks the current
account is active and linked to exactly one Minecraft UUID. It reads the
account's access profile and Paper's authenticated capabilities, then creates a
request-scoped MCP server with only tools admitted by both. It
forwards a generated UUID with every bridge request so web and Paper logs can be
correlated. It does not read world files, implement Minecraft parsing, or
delegate account policy to Paper.

### Paper plugin

`paper-plugin` owns everything that touches the Minecraft server:

- plugin lifecycle, configuration, authentication, and request admission;
- operator access commands and the asynchronous private control client;
- the resource adapter that decodes the shared human-facing catalog for
  `/dirt tools`;
- the bridge operation allowlist and capabilities response;
- world, player, chunk, block-state, and command access;
- explicit transitions onto and off Paper's main thread;
- FAWE edit execution and bounded in-memory undo history; and
- operator-console and rotating detail logs.

HTTP endpoints decode into Dirt-owned models and call narrow feature services.
Paper and FAWE types remain behind those services and do not appear in MCP or
wire schemas.

### Protocols

[`protocol/openapi.yaml`](../protocol/openapi.yaml) is the authoritative HTTP
world bridge contract between Java and TypeScript. It contains implemented
routes, request and response bodies, required headers, and structured error
variants. The world bridge's
[generated immutable TypeScript declarations](../mcp-server/src/generated/openapi.ts)
are freshness-checked against that contract.
[`protocol/access-control.openapi.yaml`](../protocol/access-control.openapi.yaml)
is the separate private Paper-to-web account-control contract. The
[tool reference](tools.md) documents the composed MCP interface.

## Identity and authorization

Registration is invite-only and passkey-only. Each invitation is bound to one
online-mode Minecraft UUID, current Minecraft name, and explicit Viewer,
Builder, or Operator profile before it is created. There is no invitation
default.
Paper sends its URL only to that online player's private chat. The Minecraft
name becomes the Dirt username when the player enrolls a passkey; Dirt has no
separate user-chosen username. Raw invitation, recovery, and link secrets are
random, short-lived, single-use values; only their SHA-256 digests are stored.
Browser-facing links keep secrets in URL fragments, exchange them through a
POST, clear the fragment, and continue with a secure, HTTP-only, same-site
enrollment cookie. Passkey ceremonies require user verification.
Recovery is an operator-issued re-enrollment that removes the account's prior
passkeys and revokes its sessions, consent, authorization codes, and refresh
grants. Recovery and every account eligibility transition rotate the account's
authorization generation, immediately invalidating previously issued access
tokens. Every MCP request also reloads the account and requires it to be active
and linked. A signed-in user may disconnect one authorized MCP client from the
dashboard. Dirt transactionally removes that user-client grant and its token
and authorization-code records, then rejects later requests from its existing
access tokens because every MCP request also requires the exact consent record
identified by the token's `dirt_consent_id` claim. Reconnecting creates a new
consent record and never reactivates old access tokens. Calls already admitted
before disconnection may finish; reconnecting requires OAuth authorization again.

Viewer grants inspection and history tools, Builder adds bounded edits and
undo, and Operator adds console-equivalent Minecraft commands. Changing a
profile is transactional: it preserves passkeys, the Minecraft link, and
browser sessions, but rotates the authorization generation and removes MCP
consent, codes, access tokens, and refresh tokens. Assigning the same profile
is a no-op.

Paper operators create targeted invitations with
`/dirt invite create <player> <profile>`, revoke them through `/dirt invite`,
and manage accounts and profiles through `/dirt user`.
An online player starts linking with `/dirt link`; Paper supplies the
online-mode-authenticated UUID and current name to the private control API and
returns a short-lived link URL only to that player. A recently authenticated
web account consumes it. Database constraints and one transaction enforce one
web account to one Minecraft UUID.

MCP authorization uses one fixed `dirt:mcp` scope. The service supports current
client metadata discovery plus authorization code flow with PKCE S256; it does
not support dynamic client registration, client credentials, legacy MCP
transports, or version-specific endpoints. The single Streamable HTTP endpoint
intentionally serves both `2025-06-18` and `2026-07-28` through one server
factory, with identical authentication and profile policy. Every MCP request
validates the token's issuer, audience, expiry, scope, current authorization
generation, and consent record, then reloads the account's active, linked, and
profile state. The fixed OAuth scope does not encode profiles; effective access
is the intersection of supported tools, Paper enablement, and the current profile grant.

## Execution model

The bridge listens only on `127.0.0.1`, requires bearer authentication, and
admits a bounded number of concurrent requests. MCP calls remain synchronous
and independent: there is no MCP session, server-sent event stream, persistent
job service, or persistent world history. SQLite persists only identity,
credentials, sessions, OAuth state, invitations, and link challenges.

Every authenticated bridge request carries `X-Dirt-Call-Id`. The mandatory
`getCapabilities` control-plane operation reports the configurable operation
IDs currently admitted by Paper. Disabled configurable operations fail with
`operation_disabled`. The bridge remains implementation-neutral and does not
use MCP tool names or schemas; only the administrative command UI reads the
shared descriptive catalog.

The dashboard checks the mandatory capabilities route and runs the end-to-end
ping only when `pingServer` is enabled. It distinguishes unavailable Paper,
zero enabled tools, and zero tools granted to the account's profile.

Bridge requests are fully materialized. MCP applies fixed tool defaults and
generates omitted edit seeds before HTTP dispatch; Paper receives explicit
values and independently validates them. MCP accepts only exact HTTP 200
`application/json` successes, schema-checks bridge responses and their request
correlation, and requires structured error codes to match their HTTP status
before exposing results to the host. The status operation is a focused POST with
explicit section booleans and required nullable player, world, and configuration
sections in its response. Within edit requests, only an omitted mutation caller
ceiling is represented by null so Paper can apply its configured safety cap.

Inspections share a smaller non-queueing admission pool within the bridge
request pool. Region snapshots and perspective traces use separate chunk and
work budgets, while match patterns and exact-state palettes have separate item
caps. This keeps each limit aligned with the resource it protects instead of
letting one representation's ceiling constrain another.

Paper-owned state is accessed through an explicit scheduler boundary. World and
player lookup, chunk snapshots, block-data validation, plugin tickets, and
command dispatch run where Paper requires them. Inspection algorithms and
potentially blocking FAWE work run away from the main thread. Edit sessions and
temporary chunk tickets are closed or released on every path before completion
is reported.

## Inspection

Region inspection never loads or generates terrain. Dirt validates the world,
height range, volume, result ceiling, and touched chunks before capturing
thread-safe Paper chunk snapshots on the main thread. Counting, filtering,
exact geometry extraction, and orthographic scanning then run off-thread.
Exact structures fail rather than truncate when the effective result ceiling
would be exceeded.

Player context is captured as one coherent main-thread snapshot. Perspective
views are a separate operation using either an online player's eye pose or an
explicit world position and yaw/pitch. They use Paper block-collision ray tracing
after a conservative loaded-chunk preflight and describe a server projection,
not entities, particles, lighting, resource packs, third-person camera state, or
other client-only presentation.

## Edits and undo

All block edits validate their complete input before mutation. A request may
preview the exact changed-block count or execute one FAWE operation. Live edits
follow this lifecycle:

1. Resolve the loaded world and validate bounds, positions, states, chunks, and
   configured limits.
2. Compute the expected change count and enforce the configured and optional
   request ceiling; before a positive live edit, reserve the lower of the
   effective ceiling and the operation's maximum possible change count.
3. Run the edit off-thread in one recording FAWE session.
4. Close the session and retain its change set before returning committed
   success.

Each world has one mutation lock shared by edits, history reads, and undo.
Competing work fails as busy rather than racing. Ordinary edits require loaded
chunks; short-lived plugin tickets keep them loaded only while preparation and
execution need them.

Dirt retains the model-supplied label, edit metadata, touched chunk coordinates,
and the FAWE change set, not an open session or full world snapshot. History is
bounded per world and globally, ordered newest first, and keyed by Paper world
UUID. Capacity evictions become final only when the replacement edit is
retained, so a failed edit leaves prior undo history intact. A batch undo must
name an exact newest-first history prefix, which Dirt validates completely
before restoration starts. It then restores sequentially, loading only
remembered existing chunks without generation and consuming each record after
that restoration succeeds.

If a runtime batch failure follows successful undos, those newer records remain
consumed, the failed current record remains retained, and older requested edits
are not attempted. The bridge returns this partial progress as a discriminated
HTTP 200 result; MCP presents it as an actionable tool failure. Records in an
undo-active world are protected from global eviction until the batch stops.
History is a flat stack with no redo entries. It is cleared on world unload,
plugin shutdown, or process restart and does not replace backups.

If an edit and its automatic rollback both fail, or an undo fails, the retained
record enters `recovery_required`. It remains retryable and blocks new Dirt
edits in that world until recovery succeeds. Recovery records are protected
from normal history eviction.

FAWE edits use its bulk-edit side-effect profile and do not request normal
Minecraft neighbor updates. Physics-sensitive structures may therefore need a
separate explicit update.

## Command execution

Command batches cross Paper's main thread once and dispatch registered commands
in order through a feedback-capturing sender with console-equivalent
permissions but no player identity or location. Validation happens before the
first command, and execution stops at the first missing target or dispatch
exception.

Batches are non-atomic. Earlier effects remain after a later failure, arbitrary
effects may outlive the response, and command changes are outside Dirt's FAWE
limits, mutation locks, history, and undo. A timeout or disconnect after
dispatch begins can therefore leave completion ambiguous.

## Security and observability

The configured bridge and control URLs must be bare `http://127.0.0.1` origins
with optional ports. They use distinct bearer credentials. Production secrets
are loaded from private files. Systemd copies the Paper-owned bridge and control
credentials into the web service's private credential directory, so the web
account cannot traverse the Paper tree. Credentials are excluded from logs
along with raw request bodies, complete block payloads, command text, command
feedback, WebAuthn challenges, invitation values, recovery values, and OAuth
tokens and codes.

The Node service also binds to `127.0.0.1`. Caddy is the only internet-facing
HTTP process, terminates HTTPS, rejects `/internal/*` before proxying, and adds
transport-wide security headers. Paper may expose its Minecraft game port
separately. The application adds content-specific security headers, requires
its exact canonical origin, uses secure host-only cookies, validates mutation
origins, sends a restrictive content security policy, and exposes no
cross-origin API.

Paper writes concise lifecycle, mutation, undo, warning, and failure events to
its console and bounded structured detail events to rotating JSON Lines files.
The web service writes structured lifecycle, tool, and sanitized failure
records. Call and edit IDs correlate records across both processes without
exposing credentials. Secret-producing Paper commands are player-only, render
results privately, and never write the secret to console or server logs.

## Boundaries

Dirt intentionally provides synchronous, semantic world operations rather than
a general Minecraft automation platform. Its dashboard is limited to account,
security, linking, MCP connection status, and a view-only tool field guide. It
does not provide persistent jobs or persistent world history, remote bridge
access, custom roles or per-tool grants, direct world-file editing, a renderer,
world controls, player control, schematics, or support for multiple Paper
generations.

Dependencies point inward through Dirt-owned operation contracts:

```text
public edge -> web/auth/MCP -> bridge operation contracts <- feature services
                    ^        ^           ^                       |
                    |        |           |                Paper / FAWE adapters
                    |    tool catalog    +---- Paper control client
                    +---- SQLite
```

This boundary keeps the public tool and wire contracts independent of Paper and
FAWE implementation types.
