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
make doctor   Check required development tools
make build    Build Java and TypeScript
make check    Run offline Java and MCP checks
make verify   Run the complete local gate, including live smoke tests
make ci       Run the clean complete CI gate, including live smoke tests
make format   Apply all repository formatters
make up       Start or reuse the managed Paper server
make reload   Rebuild and restart the managed server
make down     Stop the managed server
```

The managed server uses `paper-plugin/run/`, Minecraft port `25566`, and bridge
port `8765`. Reuse it for live tests; its world is disposable. Do not commit or
hand-edit generated runtime files. Paper plugin reload is unsupported, so use
`make reload` after plugin changes.

- During iteration, run only the smallest relevant test, lint, or type-check target.
- Before handing off code or contract changes, run `make verify` once. It already
  includes `make check` and `make smoke`; do not run those immediately beforehand.
- After an interrupted command, confirm it stopped before starting it again.
- Documentation-only changes need only link, command, formatting, and stale-text
  checks.

Keep documentation concise and current. Do not add implementation diaries,
review reports, or roadmaps.
