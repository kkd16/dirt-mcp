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
- pnpm 11.22.0 or a newer 11.x release.

Older Paper or Minecraft versions are not supported.
Source development also requires GNU Make, curl, tmux, ShellCheck 0.9 or newer,
and actionlint 1.7.12 or newer.
The managed development-server commands target Linux or WSL and use Bash and
GNU coreutils.

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

Install the matching current FAWE build from the
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
the same `DIRT_MCP_BRIDGE_TOKEN` to the MCP process. Tokens must contain at
least 32 bytes. Never commit or log tokens. All settings are validated at
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

Paper's server console receives concise lifecycle, mutation, undo, warning, and
failure events. More detailed structured events are written as rotating JSON
Lines under `plugins/DirtMCP/logs/`; the shipped configuration documents the
console threshold and file bounds. A detail-sink failure does not stop world
operations and is reported prominently in the console.

The MCP process writes structured diagnostics to stderr while stdout remains
reserved for MCP protocol messages. Paper and MCP records carry correlation
fields without recording bearer tokens, raw request bodies, or complete block
payloads. See the [v1 behavior guide](docs/v1-design.md#logging) for the precise
logging contract.

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
`replace_region_blocks`, `fill_region`, `set_blocks`, `get_edit_history`, and
`undo_edit`. Fresh configurations enable all ten. The MCP process advertises
only tools enabled in the Paper startup snapshot; a disabled tool is absent from
`tools/list` and cannot be called. See the [v1 behavior guide](docs/v1-design.md)
for tool selection and edit, history, recovery, and undo semantics, and the
[OpenAPI contract](protocol/openapi.yaml) for exact bridge schemas.

## Local development

The checked-in Gradle wrapper supplies Gradle. Verify the local toolchain and
start the managed development server with:

```bash
make doctor
make up
```

When no healthy managed server is running, `make up` installs locked
dependencies when needed, incrementally builds both components, downloads the
pinned Paper and FAWE runtime artifacts when absent, creates an ignored local
bearer token, and accepts Mojang's EULA on the command line. It starts one
persistent Paper process in a detached tmux session, waits for the authenticated
bridge to become healthy, and returns. A healthy existing server is reused
without rebuilding; use `make reload` after source or configuration changes.
Paper listens on port `25566` with an IPv4 listener suitable for Windows and WSL.
Connect at `127.0.0.1:25566`. The authenticated MCP bridge is available to local
MCP clients at `127.0.0.1:8765`. Only run it if you agree to the
[Minecraft EULA](https://aka.ms/MinecraftEULA).

During development, rebuild and safely cycle Paper with:

```bash
make reload
```

Paper does not safely support plugin hot reloads, so this performs an
incremental build, clean `stop`, restart of the same development world, and
health check. Connected players receive a clear restart message before they are
disconnected. `make reload` also rebuilds the MCP server, but its host or process
must be restarted separately to load that build.

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
`paper-plugin/build/reports/jacoco/test/html/index.html` and enforces separate
whole-plugin and independently testable-core gates. Their thresholds and the
narrow Paper/FAWE adapter exclusion list live in
[the plugin build](paper-plugin/build.gradle.kts). Excluded adapters remain in
the complete report and are exercised by the managed smoke suite, whose separate
JVM is not counted by JaCoCo. The MCP suite uses Node's native coverage across
the complete emitted server; its gate is owned by
[the server package](mcp-server/package.json).

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
