# Repository instructions

## Product and architecture

Dirt MCP is a self-hosted interface for inspecting and editing a live Paper
world. Its web service owns the dashboard, accounts, OAuth authorization, and
public MCP endpoint. It communicates over authenticated loopback HTTP with a
Paper plugin, which owns Minecraft and FAWE access. `protocol/openapi.yaml` is
the authoritative world bridge contract, `protocol/access-control.openapi.yaml`
is the private Paper-to-web account-control contract, `docs/tools.md` records
the public MCP surface, and `docs/architecture.md` defines ownership and product
boundaries.

- Support only the latest stable Paper release. Verify official sources before
  changing Paper, Java, Gradle, FAWE, or MCP dependencies.
- Keep the bridge on `127.0.0.1`, require bearer authentication for world
  endpoints, and never log or commit tokens.
- Expose only the HTTPS edge. Keep web and Paper control listeners on
  `127.0.0.1`, reject private control routes at the edge, and use distinct
  credentials for the world bridge and Paper-to-web control API.
- Require invite-only passkey accounts and OAuth for MCP. An account must be
  active and linked one-to-one with an online-mode Minecraft UUID before it can
  use MCP.
- Paper owns live worlds. Never edit world files or `.mca` data behind a running
  server.
- FAWE is required for edits. Keep FAWE types and behavior behind Dirt-owned
  Java services; wire schemas must remain implementation-neutral.
- Keep Paper/FAWE code in `paper-plugin`, MCP code in `mcp-server`, and wire
  contracts in `protocol`.
- Keep v1 small: one synchronous mutation per world, bounded in-memory undo, one
  SQLite database, one fixed MCP scope, and no persistent jobs, roles,
  per-account permissions, renderer, world UI, or speculative extension points.

## Engineering

- Implement bridge operations as vertical slices: Java behavior, OpenAPI,
  TypeScript/Zod schemas, MCP exposure, tests, and concise documentation.
- Keep blocking FAWE work off Paper's main thread. Use an explicit scheduler
  boundary for Paper-owned APIs.
- Close edit sessions and other resources on every path. Report completion only
  after the edit and undo history are complete.
- Prefer small concrete implementations. Preserve standard project metadata,
  wrappers, configuration, and scripts unless they are obsolete or harmful.
- Never edit generated lockfiles manually; update them only through their
  owning package-manager or build command. Prefer official CLI commands for
  project initialization and configuration changes when they can preserve the
  established configuration; edit configuration directly only when no suitable
  command exists.
- Never log bearer credentials, invite or recovery secrets, WebAuthn challenges,
  authorization codes, or raw command text.
- Preserve user changes. Commit only when asked.

## Workflow

Use the root commands:

```text
make help             List the supported root commands
make doctor           Check required development tools
make install          Install locked Node dependencies
make build            Build Paper and the web/MCP service
make build-paper      Build only the Paper plugin
make build-web        Build only the web/MCP service
make check            Run offline Java and MCP checks
make check-production Validate the production deployment
make verify           Run the complete local gate, including live smoke tests
make ci               Run the clean complete CI gate, including live smoke tests
make format           Apply all repository formatters
make up               Start or reuse the complete managed stack
make restart          Rebuild and restart Paper and web
make restart-paper    Rebuild Paper, safely draining and restoring web/MCP
make restart-web      Rebuild, migrate, and restart only web/MCP
make down             Stop the complete managed stack
make status           Report both managed processes
make health           Check authenticated bridge and web health
make logs             Print recent output from both panes or the stopped Paper log
make console          Connect to the Paper console
make command          Send one stdin line to the Paper console
make smoke            Run the managed live integration gate
make clean            Remove build outputs while preserving runtime state
```

The root Procfile and Overmind manage Paper plus the single Node web/MCP service.
Runtime state lives under `.dev/`; its world is disposable. The stack uses
Minecraft port `25565`, bridge port `8765`, and web port `3000`. Do not commit or
hand-edit generated runtime files or secrets. Keep secrets only in the managed
files under `.dev/secrets/`, never in `.env` or `.overmind.env`. Paper plugin
reload is unsupported, so use `make restart-paper` after plugin changes. Use
`printf '%s\n' 'version' | make command` to send one Paper console command.

- During iteration, run only the smallest relevant test, lint, or type-check target.
- Before handing off code or contract changes, run `make verify` once. It already
  includes `make check` and `make smoke`; do not run those immediately beforehand.
- After an interrupted command, confirm it stopped before starting it again.
- Documentation-only changes need only link, command, formatting, and stale-text
  checks.

Keep documentation concise and current. Do not add implementation diaries,
review reports, or roadmaps.
