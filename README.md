# Dirt MCP

AI access to live Minecraft worlds.

Dirt MCP connects a local MCP client to a running Paper server. It is designed
for inspecting bounded regions and performing deterministic bulk edits through
[FastAsyncWorldEdit (FAWE)](https://github.com/IntellectualSites/FastAsyncWorldEdit),
while Paper remains the owner of the live world.

The repository currently ships the working connection foundation: a Paper
plugin, loopback health bridge, and `dirt_status` MCP tool. The v1 world tools
are specified in [`docs/v1-design.md`](docs/v1-design.md) and are not yet
implemented.

## Platform support

Dirt MCP tracks the latest stable Paper release only. The current baseline is:

- Paper 26.2 build 112;
- Java 25;
- Node.js 24 LTS or newer; and
- FAWE for the forthcoming v1 editing tools.

Older Paper or Minecraft versions are not supported unless they happen to work.
Source development also requires GNU Make and curl.

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

FAWE will become a required runtime dependency when the v1 editing tools land;
the current health-only foundation can run without it.

## Running on a Paper server

The bridge is disabled by default. Enable it when starting Paper:

```bash
DIRT_MCP_BRIDGE_ENABLED=true \
DIRT_MCP_BRIDGE_PORT=8765 \
java -Xms2G -Xmx2G -jar paper.jar --nogui
```

Or enable it in `plugins/DirtMCP/config.yml` and restart Paper:

```yaml
bridge:
  enabled: true
  port: 8765
```

The bridge always binds to `127.0.0.1`; do not proxy or expose it publicly. The
MCP process must run on the same machine and network namespace as Paper.

The repository includes a project-scoped Codex configuration in
`.codex/config.toml`. After building the project, start Codex from this trusted
repository (or restart an existing Codex session); Codex will launch the MCP
process when it connects. Keep `make up` running so the MCP process can reach
the Paper bridge.

For another MCP host, configure it to launch the source build:

```json
{
  "mcpServers": {
    "dirt": {
      "command": "node",
      "args": ["/absolute/path/to/dirt-mcp/mcp-server/dist/index.js"],
      "env": {
        "DIRT_MCP_BRIDGE_URL": "http://127.0.0.1:8765"
      }
    }
  }
}
```

Once npm publishing exists, a global installation will provide the equivalent
`dirt-mcp` command. The current tool surface contains only `dirt_status`.

## Local development

The checked-in Gradle wrapper supplies Gradle. Verify the local toolchain and
start an isolated development server with:

```bash
make doctor
make up
```

`make up` installs locked dependencies, builds and tests both components, and
accepts Mojang's EULA on the command line. It runs Paper in the foreground on
port `25566` with an IPv4 listener suitable for Windows and WSL. Connect to the
Minecraft server at `127.0.0.1:25566`. The MCP bridge is available to local MCP
clients at `127.0.0.1:8765`. Only run it if you agree to the
[Minecraft EULA](https://aka.ms/MinecraftEULA). Type `stop` in the Paper console
for a clean shutdown.

Override local ports when needed:

```bash
make up MC_PORT=25567 BRIDGE_PORT=9876
```

Useful commands:

```text
make help      List commands and configuration overrides
make build     Build the Paper plugin and MCP server
make check     Run Java tests and TypeScript checks
make ci        Reproduce the clean CI build
make health    Query a running bridge
make mcp       Run the MCP stdio process
make inspect   Open the pinned MCP Inspector
make clean     Remove build outputs, preserving the development world
```

Generated Paper state lives in `paper-plugin/run/` and is not committed.

## Contributing

Keep changes focused and implement behavior vertically across Java, OpenAPI,
TypeScript, tests, and documentation. Do not add empty packages or document
unimplemented endpoints as available.

Before opening a pull request:

```bash
make ci
```

Changes affecting plugin startup, configuration, networking, Paper APIs, or
FAWE must also be exercised on the local Paper server with `make up` and shut
down cleanly afterward.

Read [`AGENTS.md`](AGENTS.md) for repository engineering rules and
[`docs/`](docs/README.md) for the v1 product design.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
