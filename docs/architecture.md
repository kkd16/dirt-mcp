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

The plugin remains one deployable JAR but is organized as cohesive feature
packages. A small bootstrap owns lifecycle; the bridge dispatcher owns exact
routing, authentication, admission, and error mapping; narrow operation
interfaces connect endpoints to status, command, inspection, and edit services.
Endpoints compose an operation with a typed request-decoder function, keeping
wire parsing adjacent to the operation without a decoder class hierarchy.
Paper scheduler access is centralized, while Dirt-owned models and inspection
algorithms remain independent of HTTP, Bukkit, and FAWE. Adding an operation is
a vertical slice rather than another branch in a central server class.

### MCP server

The TypeScript process owns the agent-facing interface:

- MCP stdio transport;
- concise tool names and Zod input/output schemas;
- calls to the local Paper bridge;
- conversion of bridge results into structured MCP content, including the
  optional lossless palette-grid view representation; and
- actionable error messages.

It does not read world files or reproduce Minecraft editing logic. Stdout is
reserved for MCP; process diagnostics go to stderr.

The process entry point only validates its environment and starts the current
MCP stdio transport. A composition root installs a deterministic static tool
catalog from cohesive status, inspection, editing, and command registrars. Tool
schemas stay with their feature; one concrete bridge client owns authenticated
HTTP and response validation; one execution helper owns call IDs, error mapping,
and auditing. The design uses functions and concrete modules rather than a tool
class hierarchy or dependency-injection framework.

Each accepted tool call writes one completion record to stderr with a generated
call ID, MCP request ID, client label when available, world, outcome, and elapsed
time. The call ID is forwarded to the Paper bridge so its matching console record
can be correlated. Neither record includes bearer tokens or complete tool inputs.

### Protocol

`protocol/openapi.yaml` is the versioned boundary between Java and TypeScript.
It contains implemented behavior only. Every new operation is added with its
Java implementation, TypeScript tool, validation, and tests.

## Inspection execution

Region inspection never loads or generates terrain. Exact block retrieval and
orthographic views share one detailed-inspection volume limit and one default
and maximum result limit. The plugin verifies that
every intersecting chunk is already loaded and captures thread-safe Paper chunk
snapshots on the main server thread. It counts summary states, extracts exact
filtered geometry, or scans the nearest non-air block along each sightline of a
bounded orthographic view from those snapshots off-thread. Exact results are
rejected rather than truncated when their cap is exceeded. Requests fail if the
world, height range, or any chunk is unavailable. A separate touched-chunk limit
is checked before snapshot capture, so thin regions cannot amplify main-thread
work despite having a small block volume.

## Deployment

V1 is a same-machine deployment. Loading the plugin starts the Paper bridge on
the hard-coded `127.0.0.1` interface. Bridge endpoints require a shared bearer
token even on loopback. Routes use exact method and path matching and do not
accept query strings. A bounded authenticated-request admission gate rejects
excess work rather than allowing Paper scheduler waiters to grow without bound.
The MCP server receives the bridge URL and token through its process environment.
It supports MCP protocol revision `2026-07-28` only. The configured bridge URL
must be a bare `http://127.0.0.1` origin with an optional port; paths, queries,
fragments, credentials, and other hosts are rejected at startup.

## Edit execution

V1 keeps execution intentionally direct:

1. Authenticate and parse the request.
2. Resolve an already-loaded world and validate the requested positions or
   inclusive region bounds.
3. Reject invalid block states or requests beyond configured limits.
4. If `dryRun` is true, calculate and return the effect without mutation.
5. Otherwise run the FAWE operation, wait for completion, retain non-empty
   history when enabled, and return exact counts.

There is no persistent job system. One mutation may run per world at a time;
additional mutations fail as busy rather than racing. Each world retains its
configured number of newest Dirt MCP edits in memory; history is cleared on
restart or world unload, and setting the history depth to zero disables undo
retention. Locks and history use the Paper world UUID, so a new world loaded
under an old name cannot inherit stale state.

Potentially blocking FAWE work stays off Paper's main tick thread. Any Paper API
that requires server-thread ownership crosses a small scheduler boundary.
Responses report success only after FAWE has completed and closed its edit
session. Plugin chunk tickets are reference counted and held only for the
duration of an edit or undo. Runtime shutdown rejects new work and waits a
bounded time for active bridge workers. If editing is quiescent, Dirt releases
its resources before closing the scheduler boundary; otherwise it leaves
active resources intact for Paper's plugin shutdown cleanup rather than racing
an edit that ignored interruption.

`replace_region_blocks` and `fill_region` use the same already-loaded-chunk
rule as inspection. `set_blocks` checks only the chunks containing its explicit
positions. All three canonicalize Bukkit block-state strings at the Paper
boundary and record only successful non-empty edits. Replacement expands its
property-aware source patterns to a concrete FAWE mask on Paper's main thread.
Cuboid destinations become a FAWE random pattern backed by a stateless
seed-and-coordinate selector, making results independent of traversal order.
The same selector pre-counts exact changes before mutation so dry runs are
replayable and the changed-block limit is checked before execution. Sparse
edits reject duplicate positions and validate every position, state, and chunk
before opening their single FAWE edit session. Dirt explicitly uses FAWE's API
side-effect profile for edits, which omits neighbor updates while retaining
API-appropriate heightmap and lighting work.

`undo_last_dirt_edit` uses the same world lock, applies the newest history entry
through a fresh FAWE edit session, and consumes it only after completion.

## Command execution

`run_minecraft_commands` crosses once onto Paper's main thread and dispatches
the requested commands sequentially through `Server.dispatchCommand`. It uses
Paper's feedback-capturing command sender, which has console-equivalent
permissions but no player entity. Synchronous Adventure feedback is converted
to bounded plain text for the bridge response.

Every command is attempted once in order, including after Paper finds no command
target or a command executor throws. Bukkit does not expose the Brigadier result
value, so ordinary command feedback is returned to the caller but is not
interpreted as semantic success or failure. Dispatch exceptions retain Paper's
original message while presenting the deepest non-empty cause message as the
actionable explanation. Command effects do not participate in FAWE locking,
Dirt resource limits, or Dirt undo history.

## Dependency direction

Dependencies point inward through narrow operation contracts and outward only
from concrete platform adapters:

```text
bootstrap -> bridge endpoints -> operation contracts <- feature services
                                      ^                    |
                                      |             Paper / FAWE adapters
```

FAWE types do not appear in HTTP or MCP schemas. This keeps tool behavior stable
when the underlying editor API changes with the latest Paper release.
