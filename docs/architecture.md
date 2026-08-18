# Architecture

```text
MCP host
   | stdio
   v
TypeScript MCP server
   | authenticated HTTP on 127.0.0.1
   v
Dirt MCP Paper plugin
   | validated operations
   v
FAWE -> Paper-owned live world
```

## Components

### Paper plugin

The Java plugin owns everything that touches the Minecraft server:

- plugin lifecycle and configuration;
- bearer authentication and request validation;
- world lookup, bounds, and configured limits;
- coordination of reads and edits;
- FAWE edit sessions and bounded in-memory undo history; and
- the loopback HTTP API and concise request audit records in the server console.

Paper and FAWE classes stop at this boundary. The plugin never hosts a model or
parses MCP messages.

### MCP server

The TypeScript process owns the agent-facing interface:

- MCP stdio transport;
- concise tool names and Zod input/output schemas;
- calls to the local Paper bridge;
- conversion of bridge results into structured MCP content; and
- actionable error messages.

It does not read world files or reproduce Minecraft editing logic. Stdout is
reserved for MCP; process diagnostics go to stderr.

Each accepted tool call writes one completion record to stderr with a generated
call ID, MCP request ID, client label when available, world, outcome, and elapsed
time. The call ID is forwarded to the Paper bridge so its matching console record
can be correlated. Neither record includes bearer tokens or complete tool inputs.

### Protocol

`protocol/openapi.yaml` is the versioned boundary between Java and TypeScript.
It contains implemented behavior only. Every new operation is added with its
Java implementation, TypeScript tool, validation, and tests.

## Inspection execution

Region inspection never loads or generates terrain. The plugin verifies that
every intersecting chunk is already loaded and captures thread-safe Paper chunk
snapshots on the main server thread. It counts summary states, extracts exact
filtered geometry, or scans the nearest non-air block along each sightline of a
bounded orthographic view from those snapshots off-thread. Exact results are
rejected rather than truncated when their cap is exceeded. Requests fail if the
world, height range, or any chunk is unavailable.

## Deployment

V1 is a same-machine deployment. Loading the plugin starts the Paper bridge on
the hard-coded `127.0.0.1` interface. Bridge endpoints require a shared bearer
token even on loopback. The MCP server receives the bridge URL and token through
its process environment.

## Edit execution

V1 keeps execution intentionally direct:

1. Authenticate and parse the request.
2. Resolve an already-loaded world and normalize the inclusive region bounds.
3. Reject invalid block states or requests beyond configured limits.
4. If `dryRun` is true, calculate and return the effect without mutation.
5. Otherwise run the FAWE operation, wait for completion, retain its history,
   and return exact counts.

There is no persistent job system. One mutation may run per world at a time;
additional mutations fail as busy rather than racing. Each world retains its
configured number of newest Dirt MCP edits in memory; history is cleared on
restart, and setting the history depth to zero disables undo retention.

Potentially blocking FAWE work stays off Paper's main tick thread. Any Paper API
that requires server-thread ownership crosses a small scheduler boundary.
Responses report success only after FAWE has completed and closed its edit
session.

`replace_blocks` and `fill_region` use the same already-loaded-chunk rule as
inspection, canonicalize Bukkit block-state strings at the Paper boundary, and
record only successful non-empty edits. Fill counts blocks already in the
destination state before mutation so dry-runs are exact and the changed-block
limit is checked before execution.
`undo_last_edit` uses the same world lock, applies the newest history entry
through a fresh FAWE edit session, and consumes it only after completion.

## Dependency direction

Dependencies point toward the live world:

```text
MCP schemas -> bridge contract -> application services -> FAWE adapter -> Paper
```

FAWE types do not appear in HTTP or MCP schemas. This keeps tool behavior stable
when the underlying editor API changes with the latest Paper release.
