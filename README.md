# Dirt MCP

Dirt MCP is a self-hosted passkey dashboard and authenticated MCP endpoint for
inspecting and editing a live Paper world. It keeps Minecraft and FAWE behind an
authenticated loopback bridge while allowing standards-based MCP clients to
connect through HTTPS.

The normal world workflow remains:

```text
inspect -> preview -> edit -> verify -> undo if needed
```

> **Paper remains the sole owner of the live world.** Dirt never edits region
> files directly, and inspections do not load or generate terrain.

## Capabilities

| Area     | Capabilities                                                                    |
| -------- | ------------------------------------------------------------------------------- |
| Inspect  | Count blocks or retrieve exact block states in bounded, already-loaded regions. |
| View     | Scan orthographic views or trace from a player or arbitrary camera perspective. |
| Edit     | Apply labeled, bounded replacements or placements as one FAWE edit.             |
| Preview  | Preview edits with reproducible seeds before committing them.                   |
| Undo     | Inspect labeled in-memory history and undo an exact newest-first edit prefix.   |
| Commands | Run bounded command batches through an operator-level, non-player sender.       |
| Access   | Invite-only passkey accounts linked one-to-one with Minecraft identities.       |

The dashboard intentionally contains only sign-in, enrollment, account,
Minecraft-link, and MCP connection surfaces. It is not a world editor. See the
[tool reference](docs/tools.md) for the complete MCP catalog and schemas.

## Architecture

```text
browser / MCP client --HTTPS--> Caddy --> web + OAuth + MCP --> SQLite
                                            |
                                            | authenticated 127.0.0.1 HTTP
                                            v
                                      Paper plugin --> FAWE
                                            |
                                            +--> private web control API
```

Caddy is the only public HTTP process. Paper may expose its Minecraft game port
separately, while the Node service, world bridge, and Paper-to-web
account-control API remain on `127.0.0.1`. The two loopback directions use
different bearer credentials.

The public MCP endpoint is current, stateless Streamable HTTP at `POST /mcp`.
It uses OAuth authorization code flow with PKCE S256 and the single
`dirt:mcp` scope. Every request rechecks that its account is active and linked
to an online-mode Minecraft UUID. There are no roles or per-account permission
records: every eligible account receives the same tool set permitted by Paper's
global operation allowlist.

## Requirements

Dirt follows the latest stable Paper release and does not support older server
generations.

| Dependency                                                          | Version                    |
| ------------------------------------------------------------------- | -------------------------- |
| [Paper](https://papermc.io/downloads/paper/)                        | 26.2, API build 121 stable |
| [Java](https://docs.papermc.io/paper/getting-started/#requirements) | 25                         |
| [FAWE](https://modrinth.com/plugin/fastasyncworldedit)              | 2.15.4                     |
| Node.js                                                             | 24.20.0 LTS                |
| pnpm                                                                | 11.24.0 or newer 11.x      |
| Docker Engine / Compose                                             | current Linux releases     |

The production Compose topology uses Linux host networking so the containerized
web service can reach the native Paper bridge at `127.0.0.1`. A real DNS name
pointing at the host and inbound ports 80/443 are required. Choose the permanent
hostname before enrolling passkeys because WebAuthn credentials are bound to
the relying-party domain.

## Production setup

### 1. Build and install Paper

```bash
git clone https://github.com/kkd16/dirt-mcp.git
cd dirt-mcp
make build
```

Install `paper-plugin/build/libs/dirt-mcp-paper-<version>.jar` and FAWE in the
native Paper server's `plugins/` directory. Dirt requires `online-mode=true` and
will refuse account linking on an offline-mode server.

### 2. Create private credentials

Create distinct random values for the Paper world bridge, Paper account-control
client, and web authentication service. Keep them outside version control and
the Docker build context, readable only by their services. The example Compose
file uses mounted secret files; `.env.example` contains only non-secret settings.

```bash
install -d -m 700 secrets
sudo install -d -o 1000 -g 1000 -m 700 backups
for name in auth-secret bridge-token control-token; do
  node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('hex'))" > "secrets/$name"
  chmod 444 "secrets/$name"
done
```

Supply the bridge and control values to Paper as
`DIRT_BRIDGE_TOKEN` and `DIRT_CONTROL_TOKEN`. Configure the web service
with the same values through its corresponding mounted secret files. Never
reuse either value as the web authentication secret.

On first start, Paper creates `plugins/DirtMCP/config.yml`. The shipped
[configuration](paper-plugin/src/main/resources/config.yml) covers the private
ports, global operation allowlist, operation limits, logging, and bounded undo
history. Restart Paper after changes. Never proxy or publish its bridge port.

### 3. Configure and start the HTTPS service

```bash
cp .env.example .env
# Set the permanent public hostname and secret-file paths in your local deployment.
docker compose build
docker compose run --rm migrate
docker compose up -d web caddy
```

The deployment uses a non-root Node image, digest-pinned Node and Caddy images,
persistent SQLite and Caddy volumes, and no published application or Paper
bridge port. Back up SQLite with the provided online-backup command before
migration or upgrade; do not copy only the main database file while WAL mode is
active.

```bash
DIRT_BACKUP_DESTINATION="dirt-$(date +%Y%m%d-%H%M%S).sqlite3" \
  docker compose run --rm backup
```

The destination must be a new filename inside the pre-created `backups/`
directory. The command refuses to overwrite an existing backup and publishes
it mode 0600. The production image runs as UID/GID 1000, so that numeric owner
must be able to write the backup directory. Compose file secrets preserve their
host ownership and mode, so the files are read-only but world-readable for the
non-root container. The mode-0700 host directory prevents other host users from
reaching them.

For an upgrade, take the online backup first, stop the deployment, rebuild, run
the migration, and start it again. `docker compose down` preserves the named
data volumes unless `--volumes` is explicitly added, and ensures changes to the
bind-mounted edge configuration take effect. Compose gives the edge and web
processes five minutes and fifteen seconds to finish an admitted world
operation before either can be killed during shutdown.

```bash
docker compose down
docker compose build
docker compose run --rm migrate
docker compose up -d web caddy
```

Keep copies of backups off-host and test restoration periodically. To restore,
stop the deployment, copy a chosen backup into the data volume through the
non-networked `migrate` service, remove stale WAL sidecars, migrate it, and
restart. Replace the example backup filename before running this command.

```bash
docker compose down
docker compose run --rm --no-deps \
  --volume ./backups:/restore:ro \
  --entrypoint /bin/sh migrate -eu -c '
    cp /restore/dirt-20260830-120000.sqlite3 /var/lib/dirt-mcp/dirt.sqlite3.restore
    rm -f /var/lib/dirt-mcp/dirt.sqlite3-wal /var/lib/dirt-mcp/dirt.sqlite3-shm
    mv -f /var/lib/dirt-mcp/dirt.sqlite3.restore /var/lib/dirt-mcp/dirt.sqlite3
  '
docker compose run --rm migrate
docker compose up -d web caddy
```

### 4. Create and link the first account

Join Minecraft as an operator and run:

```text
/dirt access invite create
```

Open the private click-to-copy URL, choose a handle, and enroll a passkey. Then
run `/dirt link` as the same online Minecraft player and open its private link
while recently signed in. Dirt enforces one web account to one Minecraft UUID.

Operators can list and administer access in game:

```text
/dirt access users [page]
/dirt access invitations [page]
/dirt access invite create
/dirt access invite revoke <id>
/dirt access user disable <handle>
/dirt access user enable <handle>
/dirt access user recover <handle>
/dirt access user unlink <handle>
```

Invite and recovery creation are in-game-only because they return secrets.
They are never printed to the server console or detail logs.

### 5. Connect an MCP client

Give a current remote-MCP client the URL `https://your-host.example/mcp`. The
client discovers Dirt's OAuth metadata, opens browser authorization, and sends
audience-bound access tokens to the MCP resource. Dirt does not support stdio,
legacy SSE, dynamic client registration, client credentials, or compatibility
endpoints.

The authorization screen warns that Dirt includes
`run_minecraft_commands`, which has console-equivalent authority. Paper's
`bridge.allowed-operations` list is the single global upper bound. Remove an
operation ID and restart Paper to remove the corresponding tool for everyone.

## Security and operations

- Keep Paper, the web service, and Caddy on the same Linux host. Only Caddy
  should accept internet HTTP traffic; expose Paper's game port as needed, but
  never its bridge or control APIs.
- Use HTTPS for the permanent origin. Secure passkey and session cookies are not
  designed for an HTTP production origin.
- Do not log or commit bearer tokens, invite/recovery/link values, WebAuthn
  challenges, authorization codes, or access/refresh tokens.
- Recovery is operator-issued passkey re-enrollment. It replaces old passkeys
  and revokes the account's sessions, consent, authorization codes, and refresh
  grants. Recovery, disable, enable, link, and unlink transitions also rotate an
  authorization generation that immediately invalidates existing access tokens.
- Edits are synchronous and bounded, with one Dirt mutation at a time per world.
  Undo history is memory-only and clears on world unload or restart.
- Command batches are non-atomic and outside Dirt's FAWE limits and undo. Do not
  retry them blindly after an ambiguous timeout. Keep normal server backups.

See [architecture](docs/architecture.md) for trust boundaries and
[the Paper bridge contract](protocol/openapi.yaml) plus
[the access-control contract](protocol/access-control.openapi.yaml) for the two
private loopback APIs.

## Development

The repository includes a disposable managed Paper world under the ignored
`paper-plugin/run/` directory. Running it means accepting the
[Minecraft EULA](https://aka.ms/MinecraftEULA).

| Command       | Purpose                                                  |
| ------------- | -------------------------------------------------------- |
| `make doctor` | Check the development toolchain.                         |
| `make up`     | Start the managed native Paper server.                   |
| `make reload` | Rebuild and safely restart Paper after plugin changes.   |
| `make web`    | Migrate and run the local dashboard at `localhost:3000`. |
| `make build`  | Build the Paper plugin and web service.                  |
| `make verify` | Run the complete local gate and live Paper smoke tests.  |
| `make down`   | Stop the managed Paper server.                           |

The managed server uses Minecraft port `25566` and bridge port `8765`. Paper
does not support plugin hot reload. Use `make reload` after Java or plugin
configuration changes.

Run the smallest relevant check while working. Before handing off code or
contract changes, run `make verify` once. Do not hand-edit generated runtime
files, lockfiles, or world data, and do not commit secrets. See
[`AGENTS.md`](AGENTS.md) for repository rules.

## License

Licensed under the [Apache License 2.0](LICENSE).
