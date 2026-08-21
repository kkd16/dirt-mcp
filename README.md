# Dirt MCP

Dirt MCP connects an MCP host to a live Minecraft world through Paper. Local AI
agents can inspect terrain and player context, preview or run bounded,
deterministic bulk edits with
[FastAsyncWorldEdit (FAWE)](https://modrinth.com/plugin/fastasyncworldedit), and
undo recent Dirt edits.

The usual workflow is:

```text
inspect -> preview -> edit -> verify -> undo if needed
```

> **Paper remains the sole owner of the live world.** Dirt never edits region
> files directly, and inspections do not load or generate terrain.

## What you can do

| Area     | Capabilities                                                                    |
| -------- | ------------------------------------------------------------------------------- |
| Inspect  | Count blocks or retrieve exact block states in bounded, already-loaded regions. |
| View     | Scan compact orthographic views or capture server-authoritative player views.   |
| Edit     | Replace, fill, or place weighted-palette blocks as a single FAWE edit.          |
| Preview  | Preview edits with reproducible seeds before committing them.                   |
| Undo     | Inspect bounded in-memory edit history and undo the newest edit by ID.          |
| Commands | Run bounded command batches through an operator-level, non-player sender.       |
| Access   | Paper administrators choose which MCP tools to expose.                          |

See the [tool reference](docs/tools.md) for the complete catalog and schemas.

## Requirements

Dirt MCP follows the latest stable Paper release and does not support older
Minecraft versions.

| Dependency                                                          | Version                         |
| ------------------------------------------------------------------- | ------------------------------- |
| [Paper](https://papermc.io/downloads/paper/)                        | 26.2, API build 112 stable      |
| [Java](https://docs.papermc.io/paper/getting-started/#requirements) | 25                              |
| [FAWE](https://modrinth.com/plugin/fastasyncworldedit)              | A Paper-compatible build        |
| Node.js                                                             | 26 or newer                     |
| pnpm                                                                | 11.22.0 or a newer 11.x release |

Building from source also requires GNU Make. The full development workflow uses
curl, tmux, ShellCheck 0.9 or newer, and actionlint 1.7.12 or newer. Development
scripts target Linux or WSL.

## Setup

### 1. Clone and build

Build the Paper plugin and MCP server:

```bash
git clone https://github.com/kkd16/dirt-mcp.git
cd dirt-mcp
make build
```

The build outputs:

```text
paper-plugin/build/libs/dirt-mcp-paper-<version>.jar
mcp-server/dist/index.js
```

### 2. Install the Paper plugins

Put the Dirt JAR and a compatible FAWE release in the Paper server's `plugins/`
directory. Dirt will not load without FAWE.

### 3. Create the bridge secret and start Paper

Generate a secret of at least 32 bytes, then start Paper with it set:

```bash
export DIRT_MCP_BRIDGE_TOKEN="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("hex"))')"
java -Xms2G -Xmx2G -jar paper.jar --nogui
```

On first run, Dirt creates `plugins/DirtMCP/config.yml`. The shipped
[configuration](paper-plugin/src/main/resources/config.yml) covers limits,
defaults, logging, edit-history retention, the bridge port, and the MCP tool
allowlist. Restart Paper after changing it.

### 4. Connect the MCP host

Point the MCP host at the built TypeScript server and give it the same secret.
For hosts with an `mcpServers` JSON configuration:

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
Paper tool allowlist. Call `ping_server` to test the whole path through the MCP
server, bridge, Paper, and FAWE.

### Local development with Codex

The checked-in `.codex/config.toml` launches Dirt through
`scripts/run-dirt-mcp`. Run `make up`, then start or restart Codex from the
trusted checkout.

## Security and operations

- The bridge binds to `127.0.0.1`, and every endpoint requires bearer
  authentication. Never proxy it, expose it publicly, log its token, or commit
  credentials.
- Paper console logs show concise operator events. Bounded, rotating JSON Lines
  detail logs live under `plugins/DirtMCP/logs/`.
- MCP stdout is for protocol messages only; diagnostics go to stderr.
- Edits are synchronous and bounded, with one Dirt mutation at a time per world.
- Undo history is in-memory only and is cleared on world unload or server
  restart.

Command batches are non-atomic and may cause effects outside Dirt's edit limits
and history. Do not retry them blindly after an ambiguous timeout. Keep normal
server backups.

Paper operators can use `/dirt` for live status, configuration, and tool
summaries. It requires `dirtmcp.command`, which operators receive by default.

## Development

The repository includes a disposable managed Paper world in the ignored
`paper-plugin/run/` directory. Running it means accepting the
[Minecraft EULA](https://aka.ms/MinecraftEULA).

| Command       | Purpose                                           |
| ------------- | ------------------------------------------------- |
| `make doctor` | Check the development toolchain.                  |
| `make up`     | Start the managed server, or reuse it if running. |
| `make reload` | Rebuild and safely restart after changes.         |
| `make verify` | Run the complete local gate and live smoke tests. |
| `make down`   | Stop the managed server.                          |

The managed server uses Minecraft port `25566` and bridge port `8765`. To use
different ports for a new server:

```bash
make up MC_PORT=25567 BRIDGE_PORT=9876
```

Paper does not support plugin hot reload. Use `make reload` after Java or plugin
configuration changes. Run `make help` for the full command list.

## Contributing

Keep changes focused and respect the component boundaries:

| Concern             | Location                |
| ------------------- | ----------------------- |
| Paper and FAWE code | `paper-plugin`          |
| MCP behavior        | `mcp-server`            |
| Wire contracts      | `protocol/openapi.yaml` |

Each tool change should cover Java behavior, OpenAPI, TypeScript schemas, MCP
exposure, tests, and concise documentation.

Run the smallest relevant check while working. Before handing off code or
contract changes, run `make verify` once. Do not hand-edit generated runtime
files or world data, and do not commit secrets. See [`AGENTS.md`](AGENTS.md) for
the rest of the repository rules.

## Documentation

- [Architecture](docs/architecture.md)
- [MCP tool reference](docs/tools.md)
- [Bridge OpenAPI contract](protocol/openapi.yaml)
- [Plugin configuration](paper-plugin/src/main/resources/config.yml)

## License

Licensed under the [Apache License 2.0](LICENSE).
