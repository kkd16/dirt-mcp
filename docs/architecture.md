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
- FAWE edit sessions and bounded, retryable in-memory edit history; and
- the loopback HTTP API and concise request audit records in the server console.

Paper and FAWE classes stop at this boundary. The plugin never hosts a model or
parses MCP messages.

The plugin remains one deployable JAR but is organized as cohesive feature
packages. A small bootstrap owns lifecycle; the bridge dispatcher owns exact
routing, authentication, admission, and error mapping; narrow operation
interfaces connect endpoints to status, inspection, and edit services.
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
catalog from cohesive status, inspection, and editing registrars. Tool
schemas stay with their feature; one concrete bridge client owns authenticated
HTTP and response validation; one execution helper owns call IDs, error mapping,
and auditing. The design uses functions and concrete modules rather than a tool
class hierarchy or dependency-injection framework.

Each accepted tool call writes one completion record to stderr with a generated
call ID, MCP request ID, client label when available, world, outcome, and elapsed
time. The call ID is forwarded to the Paper bridge so its matching console record
can be correlated. Block-edit and undo routes require that canonical UUIDv4 as
`X-Dirt-Call-Id`; committed edit metadata keeps the creating ID, while undo
returns its separate `undoCallId`. Neither audit record includes bearer tokens or
complete tool inputs.

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
filtered geometry, or scans a selected non-air depth along each sightline of a
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
The configured bridge URL must be a bare `http://127.0.0.1` origin with an
optional port; paths, queries, fragments, credentials, and other hosts are
rejected at startup.

## Edit execution

The verified runtime boundary is
[Paper API 26.2 build 112 stable](https://jd.papermc.io/paper/26.2/) on
[Java 25](https://docs.papermc.io/paper/getting-started/#requirements), with the
pinned FAWE 2.15.4 development build. Dirt keeps Paper-owned world lookup,
height and block-data validation, asynchronous chunk-load requests, and plugin
ticket changes behind its main-thread scheduler boundary. Potentially blocking
FAWE scans, edits, rollback, and undo run on bridge workers. Every
[WorldEdit edit session](https://worldedit.enginehub.org/en/latest/api/concepts/edit-sessions/)
is closed on every path before completion is reported.

V1 execution remains synchronous to the caller:

1. Authenticate and parse the request.
2. Require a canonical UUIDv4 `X-Dirt-Call-Id` for a block edit or undo.
3. Resolve a loaded world on Paper's thread and validate the requested positions
   or inclusive region bounds.
4. Reject invalid block states, unavailable chunks, or configured-limit
   violations before mutation.
5. If `dryRun` is true, calculate and return the effect without mutation.
6. Otherwise run the FAWE operation off-thread, close its session, and retain
   the completed undo data before returning.

There is no persistent job system. One edit, history read, or undo may hold a
world's mutation lock at a time; competing work fails as busy rather than
racing. Initial edits require their chunks to be loaded already. Reference-counted
Paper plugin tickets keep those chunks loaded only for preparation and FAWE
execution.

### Results and retained history

Every block-edit response has an `outcome`: `preview`, `no_change`, or
`committed`. Its `edit` field is null for previews and no-ops. A committed result
has a positive changed count and an `EditRecord` containing `editId`, the
creating `callId`, operation, world name, stable `worldId`, normalized inclusive
bounds, `changedBlockCount`, ISO-8601 `completedAt` for the original edit's
completion or recovery, and last retained status. A later failed undo can change
the status without changing that timestamp; a successful retry returns that
pre-consumption status. Set-block bounds are the smallest cuboid containing all
resolved positions.

Only records backed by live undo data enter history. `get_edit_history` returns
one loaded world's records newest first. `undo_edit` requires the caller to echo
the intended `editId`: a retained older ID fails with `edit_not_latest`, and an
absent, evicted, already-undone, or wrong-world ID fails with `edit_not_found`.
After a successful undo, Dirt removes and disposes that newest entry and returns
its record with `undoCallId` and `undoneAt`.

Retention has three positive startup limits: entries per world, entries across
all worlds, and the sum of retained changed-block counts. The per-world limit
cannot exceed the global entry limit, and the changed-block budget must fit at
least one maximum-sized edit. After a live request's scan finds a non-zero
change, but before FAWE first mutates the world, Dirt atomically reserves one
entry and the operation's worst-case changed-block budget. Reservation evicts
the oldest committed entries when necessary and accounts for simultaneous edits
in other worlds. `recovery_required` entries and the newest entry currently being
undone are protected. If no bounded reservation is possible, the request fails
with `history_capacity_exceeded` before changing blocks, so all three limits
remain hard even during recovery failures.

If an edit fails after changing blocks, Dirt first attempts automatic rollback.
If rollback also fails, its generated edit ID is reported in the error and a
visible `recovery_required` record retains the undo data. A failed `undo_edit`
similarly keeps the same record and marks it recovery-required. New edits in that
world stay blocked, but `get_edit_history` remains available and `undo_edit` may
retry the same newest ID until it succeeds. If protected recovery records leave
no capacity for another edit, its reservation fails before mutation.

A structured bridge error that leaves a retained committed or
recovery-required record, or cannot confirm rollback after the world becomes
unavailable, contains the transaction UUIDv4 as `error.editId`. A transaction
finalization error may also carry that generated ID after confirmed rollback.
The MCP error preserves any received edit ID and recovers a valid nested ID from
malformed success or non-2xx responses on edit and undo routes when possible.
Every failure mapped by a Dirt tool handler includes its generated
`error.callId`, allowing the caller to reconcile records returned by
`get_edit_history` using either identifier without parsing prose. An absent
record means no undoable edit remains. Invalid tool names or arguments fail
before Dirt generates a call ID. MCP SDK output-validation failures occur
outside Dirt error mapping and do not carry a structured Dirt `error.callId`.

Retained undo state is deliberately minimal: Dirt keeps the `EditRecord`, touched
chunk coordinates, and FAWE `ChangeSet`, not an open `EditSession` or a world
snapshot. Disposal atomically detaches the change set and calls its delete hook
once; successful undo, eviction, world unload, and plugin shutdown all use that
path. A failed undo leaves the same change set attached for retry. History is
therefore in-memory and process-local: world unload removes that world's entries,
and a Paper restart removes all entries. UUID-keyed state prevents a world loaded
under a reused name from inheriting old history.

Retained history does not pin chunks between calls. Before undo, Dirt asks the
[Paper 26.2 World API](https://jd.papermc.io/paper/26.2/org/bukkit/World.html)
to load every remembered chunk asynchronously with generation disabled, waits
for the futures off the main thread, then acquires reference-counted plugin
tickets on the main thread for the undo. A missing or unavailable chunk fails the
attempt without consuming the record, so the same ID remains retryable.

### Edit forms

`replace_region_blocks` and `fill_region` use the same already-loaded-chunk rule
as inspection. `set_blocks` resolves origin-relative offsets and checks only the
chunks containing those positions. All three canonicalize Bukkit block-state
strings at the Paper boundary and record only successful non-empty edits.
Replacement expands its property-aware source patterns to a concrete FAWE mask
on Paper's main thread. Edit palettes become FAWE random patterns backed by a
stateless seed-and-coordinate selector, making results independent of traversal
order. The same selector pre-counts exact changes before mutation so dry runs are
replayable and the changed-block limit is checked before execution. Set-blocks
edits resolve compact `[paletteIndex, x, y, z]` placements, reject duplicate
positions, and validate every palette reference, state, and chunk before opening
their single FAWE edit session. Dirt uses FAWE's API side-effect profile, which
omits neighbor updates while retaining API-appropriate heightmap and lighting
work.

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
