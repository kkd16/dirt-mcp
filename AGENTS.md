# Repository instructions

## Product context

Dirt MCP is a local-first MCP interface for inspecting and editing a live Paper
world. The MCP process talks over authenticated loopback HTTP to a Paper plugin;
the plugin owns all Minecraft and FAWE access.

Read `README.md` and `docs/` before changing behavior. The OpenAPI file describes
implemented bridge behavior; the v1 design describes the intended product.

The current implementation contains plugin lifecycle, authenticated health and
region inspection, plus FAWE-backed block replacement and undo. Do not present
the planned fill tool as working.

## Product rules

- Support the latest stable Paper release only. Do not add compatibility layers
  for older Paper versions without an explicit decision.
- FAWE is required for v1 edits and must be isolated behind Dirt-owned Java
  services. FAWE types must not enter HTTP or MCP schemas.
- Paper owns live worlds. Never mutate `.mca` files or world directories behind
  a running server.
- Keep the bridge on `127.0.0.1`. Remote/cloud transport is outside v1.
- World endpoints require bearer authentication. Never log or commit tokens.
- Do not add permission or land-policy integrations. Configurable volume and
  changed-block caps exist only to bound resource use.
- `replace_blocks` and `fill_region` may execute immediately; `dryRun` is an
  option, not a mandatory approval stage.
- Keep v1 synchronous and simple: one mutation per world, at most 20 in-memory
  undo entries per world, and no persistent jobs or database.
- Do not add renderers, Mineflayer, schematics, terrain systems, web UI, Docker,
  or speculative extension points unless the product scope changes explicitly.

## Engineering rules

- Implement vertical slices. A bridge operation lands with Java behavior,
  OpenAPI, TypeScript/Zod schemas, MCP exposure, tests, and concise docs.
- `protocol/openapi.yaml` contains implemented behavior only.
- Keep Paper/FAWE logic in `paper-plugin`, MCP concerns in `mcp-server`, and wire
  types in `protocol`.
- Keep blocking FAWE work off Paper's main tick thread. Cross into Paper-owned
  APIs through an explicit scheduler boundary when required.
- Close FAWE edit sessions and other resources on success and failure. Report an
  edit complete only after FAWE completion and history capture.
- Prefer small concrete implementations over empty packages, placeholder types,
  factories, or dependency-heavy frameworks.
- Pin compatibility-sensitive dependencies. Before upgrading Paper, Java,
  Gradle, FAWE, or MCP, verify current official documentation and artifact
  metadata, then update code, lockfiles, CI, and docs together.
- MCP stdio stdout is protocol-only. Send diagnostics to stderr.
- Preserve existing user changes and do not commit unless asked.

## Development workflow

Use the root commands rather than duplicating build invocations:

```text
make doctor   Verify Java, Node, npm, and Gradle
make build    Build Java and TypeScript
make check    Run Java tests and TypeScript static checks
make ci       Perform the clean CI-equivalent build
make up       Build and run the local Paper integration server
```

`make up` uses `paper-plugin/run/`, Minecraft port `25566`, and bridge port
`8765`. Treat that directory as local runtime state: do not commit it, delete its
world, or hand-edit generated configuration as part of ordinary development.
Stop Paper with the console `stop` command.

Validation expectations:

- Documentation-only changes: check links, commands, formatting, and stale
  references.
- TypeScript-only changes: `make check` and relevant MCP protocol smoke tests.
- Java or contract changes: `make ci`, inspect the built JAR, and run focused
  tests.
- Plugin lifecycle, Paper API, networking, configuration, or FAWE changes:
  perform a real `make up` boot, exercise the changed path, and verify clean
  shutdown.

Keep documentation concise and durable. Record the present design and supported
behavior, not conversation history, implementation diaries, review reports, or
roadmaps.
