# Dirt MCP

AI access to live Minecraft worlds.

Dirt MCP connects a local MCP client to a running Paper server. It is designed
for inspecting bounded regions and performing deterministic bulk edits through
[FastAsyncWorldEdit (FAWE)](https://github.com/IntellectualSites/FastAsyncWorldEdit),
while Paper remains the owner of the live world.

The repository currently ships the Paper plugin, authenticated loopback bridge,
and MCP tools for status, bounded region inspection, FAWE-backed block
replacement and filling, and undo.

## Platform support

Dirt MCP tracks the latest stable Paper release only. The current baseline is:

- Paper 26.2;
- FAWE 2.15.4;
- Java 25;
- Node.js 24 LTS or newer.

Older Paper or Minecraft versions are not supported.
Source development also requires GNU Make, curl, and tmux.

## Installation

### Release installation

Release publishing is not wired up yet. The intended v1 distribution is:

1. Download `dirt-mcp-paper-<version>.jar` from GitHub Releases.
2. Install the matching latest release from the
   [official FAWE download page](https://intellectualsites.github.io/download/fawe.html)
   on the Paper server.
3. Place the Dirt MCP JAR in the server's `plugins/` directory.
4. Install the MCP process with `npm install --global @dirt-mcp/server`.

Until those artifacts are published, install from source.

### Source installation

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

FAWE is a required runtime dependency. Dirt MCP will not load without it.

## Running on a Paper server

The loopback bridge starts with the plugin and requires a bearer token. Supply
the token when starting Paper:

```bash
export DIRT_MCP_BRIDGE_TOKEN="$(openssl rand -hex 32)"
DIRT_MCP_BRIDGE_PORT=8765 \
java -Xms2G -Xmx2G -jar paper.jar --nogui
```

The port may instead be set in `plugins/DirtMCP/config.yml`:

```yaml
bridge:
  port: 8765

limits:
  max-region-volume: 1000000
  max-changed-blocks: 250000
```

The bridge always binds to `127.0.0.1`; do not proxy or expose it publicly. Give
the same `DIRT_MCP_BRIDGE_TOKEN` to the MCP process. Tokens must contain at
least 32 bytes. Never commit or log them.

The repository includes a project-scoped Codex configuration in
`.codex/config.toml`. Run `make up` at least once to build the project and create
its ignored development token, then start Codex from this trusted repository.
Codex launches the MCP process when it connects. The source-development MCP
process hot-reloads rebuilt tool definitions; changes to its small bootstrap
still require restarting Codex.

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

Once npm publishing exists, a global installation will provide the equivalent
`dirt-mcp` command. The current tool surface contains `dirt_status`,
`inspect_region`, `replace_blocks`, `fill_region`, and `undo_last_edit`.

## Local development

The checked-in Gradle wrapper supplies Gradle. Verify the local toolchain and
start the managed development server with:

```bash
make doctor
make up
```

`make up` installs locked dependencies, builds and tests both components,
downloads the pinned FAWE development dependency, creates an ignored local
bearer token when needed, and accepts Mojang's EULA on the command line. It
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
health check. Players disconnect only for the restart. The MCP tool catalog
reloads in place when its compiled module changes.

Override local ports when needed:

```bash
make up MC_PORT=25567 BRIDGE_PORT=9876
```

The selected ports are retained by `make reload`. Restart Codex after changing
the bridge port so its MCP process reads the new development state.

Useful commands:

```text
make help      List commands and configuration overrides
make build     Build the Paper plugin and MCP server
make reload    Incrementally rebuild and gracefully restart Paper
make down      Stop the managed Paper server cleanly
make status    Report Paper and authenticated bridge health
make logs      Print recent Paper console output
make console   Attach to Paper; detach without stopping with Ctrl-b d
make command   Send one console command with CMD='...'
make check     Run Java and MCP checks and tests
make ci        Reproduce the clean CI build
make dev-token Create the ignored local bearer token
make health    Query a running bridge
make mcp       Run the MCP stdio process
make clean     Remove build outputs, preserving the development world
```

Generated Paper state lives in `paper-plugin/run/` and is not committed. This is
the canonical disposable development world: reuse and mutate it freely instead
of creating temporary Paper servers. Normal builds, reloads, and cleans preserve
the world.

## Contributing

Keep changes focused and implement behavior vertically across Java, OpenAPI,
TypeScript, tests, and documentation. Do not add empty packages or document
unimplemented endpoints as available.

Before opening a pull request:

```bash
make ci
```

Changes affecting plugin startup, configuration, networking, Paper APIs, or
FAWE must also be exercised on the managed Paper server and cycled with
`make reload` when plugin code changes.

Read [`AGENTS.md`](AGENTS.md) for repository engineering rules and
[`docs/`](docs/README.md) for the v1 product design.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
