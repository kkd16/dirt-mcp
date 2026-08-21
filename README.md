# Dirt MCP

Dirt MCP gives local AI agents controlled access to a live Minecraft world. It
connects an MCP host to a Paper server and provides bounded tools for inspecting
terrain, reading player context, making deterministic bulk edits with
[FastAsyncWorldEdit (FAWE)](https://modrinth.com/plugin/fastasyncworldedit), and
undoing recent Dirt edits.

The usual workflow is:

```text
inspect -> preview -> edit -> verify -> undo if needed
```

Paper remains the sole owner of the live world. Dirt never edits region files
directly, and inspections do not load or generate terrain.

## Features

- Count or retrieve exact block states in bounded, already-loaded regions.
- Scan compact orthographic views and capture server-authoritative player views.
- Replace, fill, or place weighted-palette blocks as one FAWE edit.
- Preview edits with reproducible seeds before committing them.
- Inspect bounded in-memory edit history and undo the newest edit by ID.
- Run bounded command batches through an operator-level, non-player sender.
- Expose only the MCP tools enabled by the Paper administrator.

See the [tool reference](docs/tools.md) for the complete catalog and schemas.

## Requirements

Dirt MCP tracks the latest stable Paper release rather than supporting older
Minecraft versions. The current baseline is:

- [Paper 26.2](https://papermc.io/downloads/paper/), API build 112 stable;
- [Java 25](https://docs.papermc.io/paper/getting-started/#requirements);
- a Paper-compatible [FAWE build](https://modrinth.com/plugin/fastasyncworldedit);
- Node.js 26 or newer; and
- pnpm 11.22.0 or a newer 11.x release.

Building from source also requires GNU Make. The full development workflow adds
curl, tmux, ShellCheck 0.9 or newer, and actionlint 1.7.12 or newer. The
development scripts target Linux or WSL.

## Setup

Clone and build both components:

```bash
git clone https://github.com/kkd16/dirt-mcp.git
cd dirt-mcp
make build
```

The build creates:

```text
paper-plugin/build/libs/dirt-mcp-paper-<version>.jar
mcp-server/dist/index.js
```

Install the Dirt JAR and a compatible FAWE release in the Paper server's
`plugins/` directory. Dirt will not load without FAWE.

Generate a secret of at least 32 bytes and supply it when starting Paper:

```bash
export DIRT_MCP_BRIDGE_TOKEN="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("hex"))')"
java -Xms2G -Xmx2G -jar paper.jar --nogui
```

The plugin creates `plugins/DirtMCP/config.yml` on first run. Its shipped
[configuration](paper-plugin/src/main/resources/config.yml) documents limits,
logging, edit-history retention, defaults, the bridge port, and the MCP tool
allowlist. Restart Paper after changing it.

Configure the MCP host to start the built TypeScript server with the same
secret. Hosts using an `mcpServers` JSON configuration can use:

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

Restart the MCP host after rebuilding the TypeScript server or changing the
Paper tool allowlist. Call `ping_server` to verify the complete MCP, bridge,
Paper, and FAWE path.

For local development with Codex, the checked-in `.codex/config.toml` already
launches Dirt through `scripts/run-dirt-mcp`. Run `make up`, then start or
restart Codex from the trusted checkout.

## Security and operations

The bridge always binds to `127.0.0.1` and every endpoint requires bearer
authentication. Do not proxy the bridge, expose it publicly, log its token, or
commit credentials. Paper console logs contain concise operator events; bounded
rotating JSON Lines detail logs live under `plugins/DirtMCP/logs/`. MCP stdout is
reserved for protocol messages and diagnostics go to stderr.

Edits are synchronous and bounded, with one Dirt mutation at a time per world.
Undo history is kept only in memory and is cleared on world unload or server
restart. Command batches are non-atomic, may cause effects outside Dirt's edit
limits and history, and must not be retried blindly after an ambiguous timeout.
Keep normal server backups.

Paper operators can run `/dirt` for live status, configuration, and tool
summaries. It requires `dirtmcp.command`, which operators receive by default.

## Development

The repository includes a disposable managed Paper world under the ignored
`paper-plugin/run/` directory. Running it means accepting the
[Minecraft EULA](https://aka.ms/MinecraftEULA).

```bash
make doctor   # check the development toolchain
make up       # build and start or reuse the managed server
make reload   # rebuild and safely restart after changes
make verify   # run the complete local gate and live smoke tests
make down     # stop the managed server
```

The managed server uses Minecraft port `25566` and bridge port `8765`; override
new-server ports with `make up MC_PORT=25567 BRIDGE_PORT=9876`. Paper plugin hot
reload is unsupported, so use `make reload` after Java or plugin configuration
changes. Run `make help` for the full command list.

## Contributing

Keep changes focused and preserve the component boundaries: Paper and FAWE code
belong in `paper-plugin`, MCP behavior in `mcp-server`, and wire contracts in
`protocol/openapi.yaml`. Tool changes should land as complete vertical slices
with Java behavior, OpenAPI, TypeScript schemas, MCP exposure, tests, and concise
documentation.

During development, run the smallest relevant check. Before handing off code or
contract changes, run `make verify` once. Do not hand-edit generated runtime
files or world data, and do not commit secrets. Additional repository rules are
in [`AGENTS.md`](AGENTS.md).

## Documentation

- [Architecture](docs/architecture.md)
- [MCP tool reference](docs/tools.md)
- [Bridge OpenAPI contract](protocol/openapi.yaml)
- [Plugin configuration](paper-plugin/src/main/resources/config.yml)

## License

Licensed under the [Apache License 2.0](LICENSE).
