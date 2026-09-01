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

## Installation

Dirt has one supported production environment:

- Ubuntu Server 24.04 LTS on x86-64 with systemd and sudo access;
- an existing native Paper 26.2 build 121 server on the same host, running
  under a non-root account with Java 25 and `online-mode=true`;
- no WorldEdit plugin; Dirt uses FAWE as its direct replacement;
- a permanent public DNS name pointing at the host and inbound TCP ports 80 and
  443 available;
- no existing public web server or reverse proxy on the host.

The release includes Node.js and the web/MCP application. The installer adds
the Dirt Paper plugin, installs the pinned FAWE 2.15.4 dependency when it is
absent, and installs Caddy from its official Ubuntu repository. Paper, Java,
worlds, DNS, and firewall policy remain operator-owned. Shared hosting,
panel-managed or containerized Paper, non-Ubuntu systems, and ARM are not
supported.

Choose the permanent hostname before enrolling passkeys because WebAuthn
credentials are bound to the relying-party domain.

> **No release is published yet.** The command below is the sole distribution
> contract and becomes usable with the first GitHub Release. There is no
> alternate production installation from a source checkout. Maintainers can
> build and validate the exact release assets with `make check-package`;
> publication means creating
> tag `v<projectVersion>` and attaching `dirt-install`, the generated archive,
> and its `.sha256` file to that GitHub Release.

### 1. Install Dirt

Download the inspectable bootstrap, then run it with the Paper root and public
hostname:

```bash
curl --proto '=https' --tlsv1.2 --fail --location \
  --output dirt-install \
  https://github.com/kkd16/dirt-mcp/releases/latest/download/dirt-install

sudo bash ./dirt-install \
  --paper-dir /srv/minecraft \
  --hostname dirt.example.com
```

The bootstrap selects one exact release archive and verifies its SHA-256 before
running it. The installer validates the host and Paper installation before
making changes, creates private credentials without displaying them, starts the
native web/MCP and HTTPS services, and prints the remaining Paper restart step.
It does not install, update, stop, or start Paper.

The installer places the Dirt JAR directly in Paper's `plugins/` directory. If
the exact release JAR was already dropped there, it is retained. A conflicting
Dirt or FAWE JAR causes installation to stop rather than overwrite operator
files.

Restart Paper through its existing supervisor. Do not use plugin reload. On
first successful start, Paper creates `plugins/DirtMCP/config.yml`; restart
Paper after changing it. The bridge always remains private on `127.0.0.1:8765`.

Inspect the native services with standard system tools:

```bash
systemctl status dirt-mcp caddy
journalctl --unit dirt-mcp --unit caddy
```

The application is installed read-only under `/opt/dirt-mcp`, configuration and
the web authentication secret are under `/etc/dirt-mcp`, and SQLite is at
`/var/lib/dirt-mcp/dirt.sqlite3`. Bridge and control credentials are Paper-owned
files under `plugins/DirtMCP/secrets/`; systemd passes private copies to the
web/MCP service without granting it access to the Paper tree.

### 2. Create and link the first account

Have the player join the server. Then run this as an in-game operator or from
the server console:

```text
/dirt invite create <player>
```

The named player must be online. Dirt sends the private invitation URL only to
that player's in-game chat; the operator sees confirmation without the secret.
The player opens the URL and enrolls a passkey. Their current Minecraft name is
their Dirt username, and the new account is linked to that authenticated
online-mode UUID immediately. There is no separate username to choose.

Operators can list and administer access in game:

```text
/dirt users [page]
/dirt invites [page]
/dirt invite create <player>
/dirt invite revoke <id>
/dirt user disable <username|id>
/dirt user enable <username|id>
/dirt user recover <username|id>
/dirt user unlink <username|id>
```

Invitation creation targets an online player and may be initiated from the
console without revealing the link there. Recovery creation remains
in-game-operator-only because it returns a secret. Secrets are never written to
the server console or detail logs.

### 3. Connect an MCP client

Give a current remote-MCP client the URL `https://your-host.example/mcp`. The
client discovers Dirt's OAuth metadata, opens browser authorization, and sends
audience-bound access tokens to the MCP resource. Dirt does not support stdio,
legacy SSE, dynamic client registration, client credentials, or compatibility
endpoints.

The authorization screen warns that Dirt includes
`run_minecraft_commands`, which has console-equivalent authority. Paper's
`bridge.allowed-operations` list is the single global upper bound. Remove an
operation ID and restart Paper to remove the corresponding tool for everyone.

## Backup and restore

Use the packaged SQLite online-backup command; do not copy only the live main
database while WAL mode is active. Create a private destination once, then give
each backup a new filename:

```bash
sudo install -d -o dirt-mcp -g dirt-mcp -m 700 /var/backups/dirt-mcp
sudo -u dirt-mcp \
  env DIRT_DATABASE_PATH=/var/lib/dirt-mcp/dirt.sqlite3 \
  /opt/dirt-mcp/node/bin/node /opt/dirt-mcp/app/dist/backup.js \
  "/var/backups/dirt-mcp/dirt-$(date +%Y%m%d-%H%M%S).sqlite3"
```

The destination must not already exist. Backups are published atomically with
mode `0600`; keep copies off-host and test restoration periodically.

To restore, stop Dirt, stage a selected backup with the service ownership,
replace the database while it is closed, and start Dirt. Its systemd unit
validates the current schema before serving traffic. Dirt has no legacy schema
upgrade path; an incompatible database is rejected.

```bash
sudo systemctl stop dirt-mcp
sudo install -o dirt-mcp -g dirt-mcp -m 600 \
  /var/backups/dirt-mcp/dirt-20260830-120000.sqlite3 \
  /var/lib/dirt-mcp/dirt.sqlite3.restore
sudo rm -f /var/lib/dirt-mcp/dirt.sqlite3-wal \
  /var/lib/dirt-mcp/dirt.sqlite3-shm
sudo mv -f /var/lib/dirt-mcp/dirt.sqlite3.restore \
  /var/lib/dirt-mcp/dirt.sqlite3
sudo systemctl start dirt-mcp
```

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

The root `Procfile` runs Paper and the single Node web/MCP service together
through Overmind. Disposable runtime state lives under ignored `.dev/`: the
Paper world is in `.dev/paper/`, SQLite is `.dev/dirt.sqlite3`, the web
authentication secret is in `.dev/secrets/`, and Paper bridge/control secrets
are in `.dev/paper/plugins/DirtMCP/secrets/`. Secrets never belong in the
tracked `.overmind.env`; it contains only non-secret development settings and
secret-file paths. Starting Paper means accepting the
[Minecraft EULA](https://aka.ms/MinecraftEULA).

The supported development and release-build environment is Ubuntu 24.04
x86-64. It requires Java 25, Node.js 24.20.0, pnpm 11.24.0, Overmind 2.5.1,
tmux, ShellCheck, actionlint, and the standard archive/download tools. `make
doctor` is the authoritative check.

| Command              | Purpose                                                      |
| -------------------- | ------------------------------------------------------------ |
| `make help`          | List the supported root commands.                            |
| `make doctor`        | Check the complete development toolchain.                    |
| `make deps`          | Install locked Node dependencies.                            |
| `make build`         | Stop the stack, then build Paper and web/MCP.                |
| `make build-paper`   | Stop the stack, then build only the Paper plugin.            |
| `make build-web`     | Build only the web/MCP service.                              |
| `make check`         | Stop the stack, then run every offline validation gate.      |
| `make package`       | Stop the stack, then build the native release assets.        |
| `make check-package` | Stop the stack, then validate the native package.            |
| `make verify`        | Run the complete local gate, including live smoke tests.     |
| `make ci`            | Run CI's clean build, test, and package gate.                |
| `make format`        | Apply every repository formatter.                            |
| `make up`            | Build and start or reuse the complete managed stack.         |
| `make restart`       | Rebuild and restart Paper and web together.                  |
| `make restart-paper` | Rebuild Paper, safely draining and restoring web/MCP.        |
| `make restart-web`   | Rebuild, initialize the schema, and restart only web/MCP.    |
| `make down`          | Stop the complete managed stack cleanly.                     |
| `make status`        | Show both managed process states.                            |
| `make health`        | Check the authenticated bridge and web health endpoint.      |
| `make logs`          | Print recent output from both panes, or Paper's stopped log. |
| `make console`       | Connect interactively to the Paper console.                  |
| `make command`       | Send one stdin line to the Paper console.                    |
| `make smoke`         | Run the managed live integration gate.                       |
| `make clean`         | Remove build outputs while preserving `.dev/`.               |

Hosted CI additionally exercises the privileged installer on a clean Ubuntu
systemd host; that host-level test intentionally is not a local Make target.

The managed stack uses Minecraft port `25565`, bridge port `8765`, and web port
`3000`. `make up` waits for both services to become healthy. Paper does not
support plugin hot reload, so use `make restart-paper` after Java or plugin
configuration changes; use `make restart-web` for web-only work or
`make restart` when both sides changed. Send a console command without exposing
it as a process argument:

```bash
printf '%s\n' 'version' | make command
```

Run the smallest relevant check while working. Before handing off code or
contract changes, run `make verify` once. Do not hand-edit generated runtime
files, lockfiles, or world data, and do not commit secrets. See
[`AGENTS.md`](AGENTS.md) for repository rules.

## License

Licensed under the [Apache License 2.0](LICENSE).
