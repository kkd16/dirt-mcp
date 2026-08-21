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
- bounded operator-level Minecraft command dispatch;
- world lookup, bounds, and configured limits;
- coordination of reads and edits;
- FAWE edit sessions and bounded, retryable in-memory edit history; and
- the loopback HTTP API, concise operator-console events, and rotating JSONL
  detail logs.

Paper and FAWE classes stop at this boundary. The plugin never hosts a model or
parses MCP messages.

The plugin remains one deployable JAR but is organized as cohesive feature
packages. A small bootstrap owns lifecycle; the bridge dispatcher owns exact
routing, authentication, admission, and error mapping; narrow operation
interfaces connect endpoints to status, command, inspection, player-context,
and edit services.
Focused static request decoders sit beside their endpoints without a shared
decoder interface or hierarchy, while the exchange object owns common body and
response mechanics.
Paper scheduler access is centralized, while Dirt-owned models and inspection
algorithms remain independent of HTTP, Bukkit, and FAWE. Adding an operation is
a vertical slice rather than another branch in a central server class.

One runtime-owned logging facade is injected across plugin components. It keeps
severity mapping, field encoding, throwable handling, and sensitive-field
rejection in one place while callers add explicit immutable context such as
call ID, edit ID, operation, and world. It sends an operator summary to Paper's
console and a bounded diagnostic stream to rotating JSON Lines without making
the detail sink part of world-operation success.

### MCP server

The TypeScript process owns the agent-facing interface:

- MCP stdio transport;
- concise tool names and Zod input/output schemas;
- calls to the local Paper bridge;
- conversion of bridge results into structured MCP content, including compact
  lossless orthographic grids and sparse player-view hits; and
- strict, actionable error details alongside human-readable messages.

It does not read world files or reproduce Minecraft editing logic. Stdout is
reserved for MCP; process diagnostics are structured JSON Lines on stderr.

The process entry point validates its environment and starts the current MCP
stdio transport. When the connection opens, a composition root reads the
authenticated Paper status snapshot and registers its enabled tool catalog.
Tool schemas stay with their cohesive status, command, inspection,
player-context, and editing registrars; one concrete bridge client owns
authenticated HTTP and response validation; one execution helper owns call IDs,
error mapping, and auditing. The design uses functions and concrete modules
rather than a tool class hierarchy or dependency-injection framework.

Each accepted tool call carries explicit context across asynchronous boundaries
and writes one completion object to stderr. Its generated call ID is forwarded
to Paper so the two processes can be correlated. Exact logging and redaction
semantics are documented in the [v1 behavior guide](v1-design.md#logging).

### Protocol

`protocol/openapi.yaml` is the versioned boundary between Java and TypeScript.
It contains implemented behavior only.

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

Player context is captured in one bounded Paper main-thread action. When its
perspective view is requested, Dirt first computes and verifies a conservative
loaded-chunk preflight for the sampled rays, then uses Paper block-collision ray
tracing from the captured eye pose. It does
not emulate a renderer or inspect client-only presentation state.
Player-context calls share the inspection admission gate; viewport dimensions
determine the view ray count, whose configured max-distance product and checked
chunks use the corresponding inspection ceilings.

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

The MCP allowlist is a Paper startup snapshot reported by the authenticated
status endpoint. The MCP process snapshots it again while building its tool
catalog, so changing YAML requires a Paper restart followed by an MCP host or
process restart. Bridge routes remain an internal authenticated transport and
are not removed when their agent-facing tool is disabled.

## Command execution

`run_minecraft_commands` validates the complete bounded batch before crossing
once onto Paper's main thread. It rejects ISO control characters, strips Java
outer whitespace, removes at most one in-game leading slash, rejects entries
that are empty after normalization, and dispatches commands once in order through
`Server.dispatchCommand` until the first failure.

Each dispatch uses Paper's supported feedback-forwarding sender and a fresh
capture scope. The sender has console-equivalent permissions but is neither a
player nor the literal console sender. Synchronous Adventure feedback is
converted to plain text under one Unicode-code-point budget for the request;
late feedback is ignored after that command's dispatch returns.

A missing target or `CommandException` becomes the final in-band per-command
outcome and stops the batch before later entries are dispatched. A reported
`dispatched` outcome means only that Paper found and invoked a target without a
dispatch exception. Command batches are non-atomic: effects from earlier
commands remain. Dispatch is synchronous, but arbitrary command effects may
outlive the response and sit outside FAWE limits, per-world edit locks, and Dirt
undo history. A timeout, disconnect, or unexpected internal failure can
therefore be ambiguous after main-thread execution begins and must not be
retried blindly.

## Edit execution

Paper-owned world lookup, block-data validation, asynchronous chunk-load
requests, and plugin ticket changes cross an explicit main-thread scheduler
boundary. Potentially blocking FAWE scans, edits, rollback, and undo remain on
bridge workers. Every WorldEdit session is closed before completion is reported.

The caller waits synchronously while a live edit moves through these boundaries:

1. Authenticate and parse the request.
2. Resolve a loaded world on Paper's thread and validate the requested positions
   or inclusive region bounds.
3. Reject invalid block states, unavailable chunks, or configured-limit
   violations before mutation.
4. If `dryRun` is true, calculate and return the effect without mutation.
5. For a non-empty live edit, reserve bounded history capacity before mutation.
6. Run the FAWE operation off-thread, close its session, and retain
   the completed undo data before returning.

There is no persistent job system. One edit, history read, or undo may hold a
world's mutation lock at a time; competing work fails as busy rather than
racing. Initial edits require loaded chunks. Reference-counted Paper plugin
tickets keep them loaded only while preparation and FAWE execution need them.

### Retained undo state

Dirt retains an edit record, touched chunk coordinates, and the FAWE change set,
not an open edit session or world snapshot. The coordinator owns reservation,
eviction, recovery protection, newest-first undo, and idempotent disposal. A
failed rollback or undo keeps retryable state and blocks further edits in that
world; successful undo, eviction, world unload, and plugin shutdown dispose it.
History is in memory and keyed by Paper world UUID, so it cannot outlive a world
unload or server process.

Retained history does not pin chunks between calls. Undo asynchronously loads
only remembered existing chunks without generation, acquires plugin tickets on
Paper's thread, and runs FAWE restoration on a worker. A failed load or undo
leaves the record available for retry.

The [v1 behavior guide](v1-design.md#edits-history-and-undo) owns exact operation,
result, recovery, history-limit, and failure semantics.

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
