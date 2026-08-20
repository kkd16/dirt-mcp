# Dirt MCP

AI access to live Minecraft worlds.

Dirt MCP connects a local MCP client to a running Paper server. It is designed
for inspecting bounded regions and performing deterministic bulk edits through
[FastAsyncWorldEdit (FAWE)](https://github.com/IntellectualSites/FastAsyncWorldEdit),
while Paper remains the owner of the live world.

The repository ships the Paper plugin, authenticated loopback bridge, and MCP
tools for server status, bounded inspection, FAWE-backed cuboid and palette-based
edits, inspectable edit history, and ID-checked undo.

## Platform support

Dirt MCP tracks the latest stable Paper release only. The current baseline is:

- [Paper 26.2](https://docs.papermc.io/paper/dev/project-setup/), pinned to API
  build 112 stable;
- FAWE 2.15.4;
- [Java 25](https://docs.papermc.io/paper/getting-started/#requirements);
- Node.js 26 or newer;
- pnpm 11.22.0 or newer.

Older Paper or Minecraft versions are not supported.
Source development also requires GNU Make, curl, tmux, ShellCheck 0.9 or newer,
and actionlint 1.7.12 or newer.

## Installation from source

```bash
git clone https://github.com/kkd16/dirt-mcp.git
cd dirt-mcp
make install
make build
```

The build produces:

```text
paper-plugin/build/libs/dirt-mcp-paper-<version>.jar
mcp-server/dist/index.js
```

Copy the Paper plugin into an existing server:

```bash
cp paper-plugin/build/libs/dirt-mcp-paper-*.jar /path/to/paper/plugins/
```

Install the matching current Paper build from the
[official FAWE download page](https://intellectualsites.github.io/download/fawe.html).
FAWE is a required runtime dependency; Dirt MCP will not load without it.

## Running on a Paper server

The loopback bridge starts with the plugin and requires a bearer token. Supply
the token when starting Paper:

```bash
export DIRT_MCP_BRIDGE_TOKEN="$(openssl rand -hex 32)"
DIRT_MCP_BRIDGE_PORT=8765 \
java -Xms2G -Xmx2G -jar paper.jar --nogui
```

The port may instead be set in `plugins/DirtMCP/config.yml`. The
[shipped configuration](paper-plugin/src/main/resources/config.yml) documents
every setting and default.

The bridge always binds to `127.0.0.1`; do not proxy or expose it publicly. Give
the same `DIRT_MCP_BRIDGE_TOKEN` to the MCP process. Tokens must satisfy the
configured byte minimum, which defaults to 32; lowering it weakens
authentication. Never commit or log tokens. All settings are validated at
startup; active tool limits, edit-history configuration, defaults, logging
configuration, and the resolved MCP tool allowlist are reported by
`get_server_status`. The shipped
`tools` section explicitly enables every tool. Each recognized entry is an
independent boolean; an entry omitted from that section resolves to false, while
unknown or invalid entries stop plugin startup. Other configuration keys remain
required. Compare an existing file with the shipped `config.yml` after
upgrading.

Restart Paper after changing the file, then restart the MCP host or process so
it loads the new catalog. Tool configuration controls the agent-facing MCP
catalog; authenticated loopback bridge routes remain available to the matching
local MCP process.

### Paper operator command

Operators can inspect the running plugin with `/dirt`. Running it without a
subcommand displays its formatted help menu; `/dirt version` shows the packaged
plugin version, `/dirt status` gives a compact server and bridge summary, and
`/dirt config` lists the active startup-snapshotted configuration, including
every resolved per-tool flag. `/dirt tools` lists every supported MCP tool with
its configured ON/OFF state; select one or run `/dirt tools <tool_id>` for a
concise view of its purpose, arguments, structured return values, and important
behavior. The config view reflects the `DIRT_MCP_BRIDGE_PORT` override when
present. Restart Paper to apply configuration file changes.

The command requires `dirtmcp.command`, which is granted to operators by
default and may be assigned explicitly through a permission plugin.

### Logs

Paper's server console receives concise lifecycle, completed mutation and undo,
actionable warning, and unexpected-failure events at the configured
`logging.console-level`. Detailed structured events are written as JSON Lines to
`plugins/DirtMCP/logs/dirt-detail.%g.jsonl`. The positive
`logging.detail-file-max-bytes` and `logging.detail-file-retained-files` settings
bound size-based rotation; retained-file count must be between two and 100. The
shipped values are 10,485,760 bytes and five files. If the detail sink cannot be opened
or later fails, Dirt continues and reports the logging degradation as a
prominent Paper console error.

The MCP process writes one structured JSON object per diagnostic line to
stderr. Stdout remains reserved for MCP protocol messages. Paper and MCP records
carry applicable call, edit, operation, world, outcome, and duration fields so
the two sides can be correlated. Neither sink records bearer tokens, raw request
bodies, or complete block payloads.

The repository includes a project-scoped Codex configuration in
`.codex/config.toml`. Run `make up` at least once to build the project and create
its ignored development token, then start Codex from this trusted repository.
Codex launches the MCP process when it connects. Rebuild MCP changes and restart
Codex so it launches the new process and tool catalog.

For another MCP host, configure it to launch the source build:

```json
{
  "mcpServers": {
    "dirt": {
      "command": "node",
      "args": ["/absolute/path/to/dirt-mcp/mcp-server/dist/index.js"],
      "env": {
        "DIRT_MCP_BRIDGE_URL": "http://127.0.0.1:8765",
        "DIRT_MCP_BRIDGE_TOKEN": "<same secret supplied to Paper>"
      }
    }
  }
}
```

The implemented tool surface contains `ping_server`, `get_server_status`,
`count_region_block_states`, `get_region_blocks`, `scan_orthographic_view`,
`replace_region_blocks`, `fill_region`, `set_blocks`, `get_edit_history`,
and `undo_edit`. Fresh configurations enable all ten. The MCP process advertises
only tools enabled in the Paper startup snapshot; a disabled tool is absent from
`tools/list` and cannot be called. See the
[v1 behavior guide](docs/v1-design.md) for selection and execution semantics, and the
[OpenAPI contract](protocol/openapi.yaml) for exact bridge schemas.

`set_blocks` takes one absolute `origin`, one or more weighted `palettes`, and
compact `[paletteIndex, x, y, z]` placements whose coordinates are signed
origin-relative offsets. Each palette uses the same optional-weight and seed
rules as the cuboid edit tools. Dirt validates every position before one FAWE
edit, rejects duplicates, and retains a committed non-empty batch as one history
record. The shipped 256 KiB request limit bounds request memory. Placement does
not request Minecraft neighbor physics.

Every block-edit response has an `outcome` and an `edit` field. A `committed`
result contains an `EditRecord` with its generated `editId`, creating `callId`,
operation, world name and UUID, normalized bounds, positive changed-block count,
original edit completion or recovery timestamp, and last retained status. A
successful undo preserves the status immediately before it consumed the record.
A `preview` or `no_change` result returns `edit: null` because there is no
mutation to undo.

`get_edit_history` returns the retained records for one loaded world, newest
first. `undo_edit` requires both the world and the exact `editId` of the newest
record; it rejects a retained older ID instead of undoing a different edit.
Direct bridge callers must supply a canonical UUIDv4 `X-Dirt-Call-Id` header for
each block edit and undo; the MCP server generates it automatically. Successful
undo returns the original record plus `undoCallId` and `undoneAt`.

History is bounded by positive per-world, global-entry, and aggregate
changed-block limits. The shipped defaults are 20 entries per world, 100 total,
and 1,310,720 retained changed blocks. Immediately before a non-empty edit first
mutates the world, Dirt reserves worst-case space under all three limits,
evicting old committed records if needed. If protected history leaves no room,
the request fails with `history_capacity_exceeded` before changing the world.
Recovery-required records therefore remain visible and retryable without making
the limits soft. History and its FAWE change data are discarded on world unload
or Paper restart. An undo may asynchronously reload its previously existing
chunks without generating terrain and holds plugin chunk tickets only while it
runs.

Every failure mapped by a Dirt tool handler includes its generated
`error.callId`. Invalid tool names or arguments are rejected before Dirt creates
a call ID; MCP SDK output-validation failures occur outside Dirt error mapping
and do not carry a structured Dirt `error.callId`. A structured bridge edit
error includes `error.editId` when the request leaves a committed or
recovery-required record, or rollback cannot be confirmed after the world
becomes unavailable. Transaction-finalization errors may also include the
generated ID after a confirmed rollback. The MCP server preserves any received
edit ID and salvages a valid nested ID from malformed success or non-2xx
responses on edit and undo routes when possible. Callers can reconcile records
returned by `get_edit_history` using `editId` or `callId`; an absent record means
no retryable undo remains.

## Local development

The checked-in Gradle wrapper supplies Gradle. Verify the local toolchain and
start the managed development server with:

```bash
make doctor
make up
```

`make up` installs locked dependencies when needed, incrementally builds both
components, downloads the pinned FAWE development dependency, creates an ignored
local bearer token when needed, and accepts Mojang's EULA on the command line. It
starts one persistent Paper process in a detached tmux session, waits for the
authenticated bridge to become healthy, and returns. Paper listens on port
`25566` with an IPv4 listener suitable for Windows and WSL. Connect at
`127.0.0.1:25566`. The authenticated MCP bridge is available to local MCP
clients at `127.0.0.1:8765`. Only run it if you agree to the
[Minecraft EULA](https://aka.ms/MinecraftEULA).

During development, rebuild and safely cycle Paper with:

```bash
make reload
```

Paper does not safely support plugin hot reloads, so this performs an
incremental build, clean `stop`, restart of the same development world, and
health check. Connected players receive a clear restart message before they are
disconnected. MCP server changes require rebuilding and restarting the MCP host
or process separately.

Override local ports when needed:

```bash
make up MC_PORT=25567 BRIDGE_PORT=9876
```

The selected ports are retained by `make reload`. Restart Codex after changing
the bridge port so its MCP process reads the new development state.

Useful commands:

```text
make verify    Run the complete incremental local gate, including live smoke coverage
make check     Run all offline builds, tests, lint, formatting, and validation
make smoke     Restart Paper and run live integration and lifecycle validation
make ci        Run the clean complete gate used by GitHub Actions, including smoke
make build     Build the Paper plugin and MCP server
make format    Apply the repository's Java, TypeScript, and configuration formatters
make reload    Incrementally rebuild and gracefully restart Paper
make up        Start or reuse the managed Paper server
make down      Stop the managed Paper server cleanly
make status    Report Paper and authenticated bridge health
make logs      Print recent Paper console output
make console   Attach to Paper; detach without stopping with Ctrl-b d
make command   Send one console command with CMD='...'
make health    Run the authenticated end-to-end Dirt/Paper/FAWE ping
make mcp       Run the MCP stdio process
make clean     Remove build outputs, preserving the development world
```

Generated Paper state lives in `paper-plugin/run/` and is not committed. This is
the canonical disposable development world: reuse and mutate it freely instead
of creating temporary Paper servers. Normal builds, reloads, and cleans preserve
the world.

`make verify` is the standard pre-handoff gate. It runs all Java, TypeScript,
Node, MCP, contract, configuration, package, and formatting checks, validates
the built Paper JAR, restarts the managed server, runs live bridge, Paper, and
FAWE coverage, and rejects serious lifecycle log failures. The live suite
temporarily force-loads chunk `0,0`, verifies status and inspection paths,
mutates a bounded fixture through fill, replacement, and palette-based setting,
checks result caps, exact states, no-ops, history metadata, and ID-checked undo,
then restores the prior world state. Run it without concurrent Dirt MCP edits.

The offline Java suite publishes a complete JaCoCo report at
`paper-plugin/build/reports/jacoco/test/html/index.html` and enforces 70% line
and 58% branch coverage across the whole plugin. A second gate enforces 90% line
and 75% branch coverage on the independently testable core; only explicitly
listed Paper/FAWE runtime adapters are omitted from that stricter calculation.
Those adapters remain visible in the complete report and are exercised by the
managed live smoke suite, whose separate JVM is not counted as JaCoCo coverage.
The MCP suite uses Node's native coverage and enforces 90% line, 80% branch, and
90% function coverage across the complete emitted server.

## Contributing

Keep changes focused and implement behavior vertically across Java, OpenAPI,
TypeScript, tests, and documentation. Do not add empty packages or document
unimplemented endpoints as available.

Before handing off a local change:

```bash
make verify
```

GitHub Actions runs `make ci` from a clean dependency and build state, including
the same managed Paper smoke suite. Use focused native checks during iteration
when the complete gate is unnecessary.

Read [`AGENTS.md`](AGENTS.md) for repository engineering rules and
[`docs/`](docs/README.md) for the v1 product design.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
