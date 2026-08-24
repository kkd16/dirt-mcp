# Architecture

Dirt MCP is a same-machine bridge between an MCP host and a live Paper server.
Paper owns world state and persistence; Dirt exposes bounded operations against
already-loaded worlds without reading or writing Minecraft region files.

```text
MCP host
   | stdio
   v
TypeScript MCP server
   | authenticated HTTP on 127.0.0.1
   v
Dirt MCP Paper plugin
   | validated operations and scheduler boundaries
   v
FAWE and Paper-owned live worlds
```

## Components and ownership

### MCP server

`mcp-server` owns the agent-facing interface:

- MCP stdio transport and tool registration;
- the public tool catalog, descriptions, annotations, and fixed input defaults;
- Zod input, success-output, and structured failure schemas;
- environment validation and the authenticated bridge client;
- structured diagnostics on stderr while stdout remains protocol-only.

At startup it reads Paper's authenticated capabilities endpoint, maps allowed
bridge operation IDs to its own tool catalog, and registers only tools whose
operation is admitted. It forwards a generated UUID with every bridge request so MCP
and Paper logs can be correlated. It does not read world files, implement
Minecraft parsing, or delegate MCP policy to Paper.

### Paper plugin

`paper-plugin` owns everything that touches the Minecraft server:

- plugin lifecycle, configuration, authentication, and request admission;
- the bridge operation allowlist and capabilities response;
- world, player, chunk, block-state, and command access;
- explicit transitions onto and off Paper's main thread;
- FAWE edit execution and bounded in-memory undo history; and
- operator-console and rotating detail logs.

HTTP endpoints decode into Dirt-owned models and call narrow feature services.
Paper and FAWE types remain behind those services and do not appear in MCP or
wire schemas.

### Protocol

[`protocol/openapi.yaml`](../protocol/openapi.yaml) is the authoritative HTTP
contract between Java and TypeScript. It contains implemented routes, request
and response bodies, required headers, and structured error variants. The
[generated immutable TypeScript declarations](../mcp-server/src/generated/openapi.ts)
are freshness-checked against it. The [tool reference](tools.md) documents the
separate composed MCP interface.

## Execution model

The bridge listens only on `127.0.0.1`, requires bearer authentication, and
admits a bounded number of concurrent requests. MCP calls remain synchronous;
there is no job service, database, or remote transport.

Every authenticated bridge request carries `X-Dirt-Call-Id`. The mandatory
`getCapabilities` control-plane operation reports the configurable operation
IDs currently admitted by Paper. Disabled configurable operations fail with
`operation_disabled`; Paper does not know MCP tool names or schemas.

Bridge requests are fully materialized. MCP applies fixed tool defaults and
generates omitted edit seeds before HTTP dispatch; Paper receives explicit
values and independently validates them. The status operation is a focused POST
with explicit section booleans and required nullable player, world, and
configuration sections in its response. Within edit requests, only an omitted
mutation caller ceiling is represented by null so Paper can apply its configured
safety cap.

Inspections share a smaller non-queueing admission pool within the bridge
request pool. Region snapshots and perspective traces use separate chunk and
work budgets, while match patterns and exact-state palettes have separate item
caps. This keeps each limit aligned with the resource it protects instead of
letting one representation's ceiling constrain another.

Paper-owned state is accessed through an explicit scheduler boundary. World and
player lookup, chunk snapshots, block-data validation, plugin tickets, and
command dispatch run where Paper requires them. Inspection algorithms and
potentially blocking FAWE work run away from the main thread. Edit sessions and
temporary chunk tickets are closed or released on every path before completion
is reported.

## Inspection

Region inspection never loads or generates terrain. Dirt validates the world,
height range, volume, result ceiling, and touched chunks before capturing
thread-safe Paper chunk snapshots on the main thread. Counting, filtering,
exact geometry extraction, and orthographic scanning then run off-thread.
Results fail rather than truncate when a configured ceiling would be exceeded.

Player context is captured as one coherent main-thread snapshot. Perspective
views are a separate operation using either an online player's eye pose or an
explicit world position and yaw/pitch. They use Paper block-collision ray tracing
after a conservative loaded-chunk preflight and describe a server projection,
not entities, particles, lighting, resource packs, third-person camera state, or
other client-only presentation.

## Edits and undo

All block edits validate their complete input before mutation. A request may
preview the exact changed-block count or execute one FAWE operation. Live edits
follow this lifecycle:

1. Resolve the loaded world and validate bounds, positions, states, chunks, and
   configured limits.
2. Compute the expected change count and enforce the configured and optional
   request ceiling; before a positive live edit, reserve the lower of the
   effective ceiling and the operation's maximum possible change count.
3. Run the edit off-thread in one recording FAWE session.
4. Close the session and retain its change set before returning committed
   success.

Each world has one mutation lock shared by edits, history reads, and undo.
Competing work fails as busy rather than racing. Ordinary edits require loaded
chunks; short-lived plugin tickets keep them loaded only while preparation and
execution need them.

Dirt retains the model-supplied label, edit metadata, touched chunk coordinates,
and the FAWE change set, not an open session or full world snapshot. History is
bounded per world and globally, ordered newest first, and keyed by Paper world
UUID. Capacity evictions become final only when the replacement edit is
retained, so a failed edit leaves prior undo history intact. A batch undo must
name an exact newest-first history prefix, which Dirt validates completely
before restoration starts. It then restores sequentially, loading only
remembered existing chunks without generation and consuming each record after
that restoration succeeds.

If a runtime batch failure follows successful undos, those newer records remain
consumed, the failed current record remains retained, and older requested edits
are not attempted. The bridge returns this partial progress as a discriminated
HTTP 200 result; MCP presents it as an actionable tool failure. Records in an
undo-active world are protected from global eviction until the batch stops.
History is a flat stack with no redo entries. It is cleared on world unload,
plugin shutdown, or process restart and does not replace backups.

If an edit and its automatic rollback both fail, or an undo fails, the retained
record enters `recovery_required`. It remains retryable and blocks new Dirt
edits in that world until recovery succeeds. Recovery records are protected
from normal history eviction.

FAWE edits use its bulk-edit side-effect profile and do not request normal
Minecraft neighbor updates. Physics-sensitive structures may therefore need a
separate explicit update.

## Command execution

Command batches cross Paper's main thread once and dispatch registered commands
in order through a feedback-capturing sender with console-equivalent
permissions but no player identity or location. Validation happens before the
first command, and execution stops at the first missing target or dispatch
exception.

Batches are non-atomic. Earlier effects remain after a later failure, arbitrary
effects may outlive the response, and command changes are outside Dirt's FAWE
limits, mutation locks, history, and undo. A timeout or disconnect after
dispatch begins can therefore leave completion ambiguous.

## Security and observability

The configured bridge URL accepted by the MCP process must be a bare
`http://127.0.0.1` origin with an optional port. Tokens are supplied through the
process environment and are excluded from logs along with raw request bodies,
complete block payloads, command text, and command feedback.

Paper writes concise lifecycle, mutation, undo, warning, and failure events to
its console and bounded structured detail events to rotating JSON Lines files.
The MCP process writes one structured completion record per accepted call to
stderr. Call and edit IDs correlate records across both processes without
exposing credentials.

## Boundaries

Dirt intentionally provides synchronous, local, semantic operations rather
than a general Minecraft automation platform. It does not provide persistent
jobs or persistent history, a database, remote bridge access, permission
integration, direct world-file editing, a renderer or web UI, player control,
schematics, or support for multiple Paper generations.

Dependencies point inward through Dirt-owned operation contracts:

```text
bootstrap -> bridge endpoints -> operation contracts <- feature services
                                      ^                    |
                                      |             Paper / FAWE adapters
```

This boundary keeps the public tool and wire contracts independent of Paper and
FAWE implementation types.
