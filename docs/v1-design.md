# Version 1 behavior

V1 is a synchronous, local tool surface for loaded Paper worlds. Mutation tools
execute immediately unless `dryRun` is enabled. The authoritative HTTP request,
response, and error schemas are in
[`protocol/openapi.yaml`](../protocol/openapi.yaml); this document records the
behavior that matters when choosing and combining tools.

## Tools

| MCP tool                    | Bridge operation                     | Purpose                                                               |
| --------------------------- | ------------------------------------ | --------------------------------------------------------------------- |
| `ping_server`               | `GET /v1/ping`                       | Verify the authenticated Dirt, Paper, and FAWE path without mutation. |
| `get_server_status`         | `GET /v1/server-status`              | Return builds, performance, players, worlds, and active config.       |
| `count_region_block_states` | `POST /v1/count-region-block-states` | Count canonical block states in an inclusive region.                  |
| `get_region_blocks`         | `POST /v1/get-region-blocks`         | Return filtered exact blocks or lossless axis-aligned runs.           |
| `scan_orthographic_view`    | `POST /v1/scan-orthographic-view`    | Find a selected non-air depth on each bounded world-axis sightline.   |
| `replace_region_blocks`     | `POST /v1/replace-region-blocks`     | Replace a union of block-state patterns with a destination palette.   |
| `fill_region`               | `POST /v1/fill-region`               | Fill a region from a destination palette.                             |
| `set_blocks`                | `POST /v1/set-blocks`                | Place weighted-palette states at relative offsets as one edit.        |
| `get_edit_history`          | `POST /v1/get-edit-history`          | List retained undoable edits for one loaded world, newest first.      |
| `undo_edit`                 | `POST /v1/undo-edit`                 | Undo the identified newest retained edit in one loaded world.         |

The table is the implemented surface. The `tools` configuration section is an
explicit allowlist for the agent-facing MCP catalog. The shipped file lists all
ten tools as true; every recognized tool omitted from that section resolves to
false. Disabled tools are absent from MCP discovery and calls to them are
rejected before Dirt creates a call ID or bridge request. Flags are independent,
so operators can expose only the workflow they intend. Authenticated bridge
routes remain available for the local MCP process, including the status request
used to load the allowlist.

Tool settings are snapshotted when Paper starts and when the MCP process loads
its catalog. After editing YAML, restart Paper and the MCP host or process.

Coordinates are signed 32-bit integers. Region corners are inclusive and are
normalized independently on each axis.

## Inspection

Inspection never loads or generates terrain. Dirt rejects a request unless its
world, height range, and every intersecting chunk are already available. Paper
captures thread-safe chunk snapshots on its main thread; counting and extraction
then run off-thread.

`count_region_block_states` returns a complete histogram, including air.
`get_region_blocks` returns geometry and defaults to non-air `blocks`. Its
optional include and exclude filters accept block-state patterns: omitted state
properties match any value. Both lists are limited to 64 entries combined and
exact duplicates are evaluated once. The `runs` format returns a deterministic,
non-overlapping exact cover using inclusive axis-aligned spans.

`scan_orthographic_view` scans away from an origin, beginning at distance one.
Its zero-based `depth` selects the non-air hit returned on each sightline: zero
is the first, one is the second, and omission defaults to zero. Air gaps do not
count toward depth. Horizontal views use world-up as their vertical axis. Up and
down views use east as horizontal and north as vertical. The bridge returns
explicit blocks; the MCP-only `grid` format converts them to a one-based
`blockStatePalette` with aligned state-index and distance rows. Zero denotes an
empty sightline.

Inspection result caps never truncate data. Dirt returns `result_too_large`
when exact blocks, runs, or visible blocks exceed the applicable cap.

## Edits, history, and undo

`replace_region_blocks` and `fill_region` operate on inclusive cuboids.
`set_blocks` is the compact counterpart for mixed-material structures. Its
`palettes` field contains one or more block-state palettes. Each `placements`
entry is an exact `[paletteIndex, x, y, z]` integer tuple whose coordinates are
signed offsets from the absolute `origin`. The batch remains one mutation and,
when it commits at least one change, one retained history record.

Replacement sources are a non-empty array of block-state patterns. Patterns
are ORed, and omitted properties match any value, so `minecraft:oak_stairs`
matches every oak-stair state while an explicitly supplied property constrains
the match. Every destination or set-block palette contains exact block states.
If every entry in one palette omits `weight`, its states have equal per-block
probability. Otherwise every entry in that palette supplies a whole-number
percentage and its weights total 100. Weighted results are probabilistic rather
than exact quotas.

All three edit tools accept an optional signed 32-bit `seed`. Omission generates
a fresh seed that is returned in the response. Reusing a seed with the same
ordered palettes and unchanged world reproduces each coordinate's choice, so a
dry run can be replayed as an edit with the same changed count.

Before mutation, Dirt validates all palette references, resolved coordinates,
loaded chunks, and block states. Resolved positions must be distinct;
duplicates are rejected rather than given last-write semantics. Block states
are canonicalized at the Paper boundary. A `set_blocks` request may not contain
more placements than the configured region-volume limit, and all edits must stay
within the changed-block limit.

Every `replace_region_blocks`, `fill_region`, `set_blocks`, and `undo_edit`
bridge request requires a canonical UUIDv4 `X-Dirt-Call-Id` header. The MCP
server creates it; direct bridge clients must do the same. Each executed
non-empty edit uses one recording FAWE edit session. A world accepts one Dirt
edit or undo at a time, and competing work fails with `world_busy`.

Every block-edit response includes `outcome` and `edit`. The possible outcomes
are:

- `preview`: the exact dry-run result; `edit` is null;
- `no_change`: an executed request that changed zero blocks; `edit` is null; or
- `committed`: a positive change count with a retained `EditRecord` in `edit`.

An `EditRecord` contains the generated `editId`, creating `callId`, operation,
world name, Paper `worldId`, normalized inclusive bounds, positive
`changedBlockCount`, ISO-8601 `completedAt` for the original edit's completion
or recovery, and last retained `status` (`committed` or `recovery_required`). A
later failed undo can change the status without changing that timestamp; a
successful retry returns that pre-consumption status. Set-block records use the
smallest bounds containing all resolved placements. History stores the operation
name, not the full request.

Committed success is reported only after a live FAWE `ChangeSet` is retained.
Dirt stores that minimal change set with its touched chunks and metadata, not an
open edit session or full world snapshot. Disposal is idempotent and deletes the
change set after successful undo, eviction, world unload, or shutdown. Failed
undo keeps the same change set for retry.

`get_edit_history` returns one loaded world's retained records newest first.
Every returned record has live undo data. `undo_edit` requires the exact
`editId` of the newest entry. A retained older ID fails with `edit_not_latest`;
an absent, evicted, consumed, or wrong-world ID fails with `edit_not_found`.
Success consumes the entry only after FAWE completes and returns the original
record, the new `undoCallId`, and `undoneAt`.

History is bounded by positive `edit-history.max-entries-per-world`,
`edit-history.max-entries-total`, and
`edit-history.max-retained-changed-blocks` settings. Immediately before a
non-empty live operation first mutates blocks, Dirt reserves one entry and its
worst-case changed-block count across all concurrent worlds. It evicts the oldest
committed records to make room. If protected entries leave no room, the request
fails with `history_capacity_exceeded` before mutation. History uses the Paper
world UUID and is cleared for a world when it unloads; all history is cleared on
restart. The per-world entry limit cannot exceed the global entry limit, and the
retained changed-block limit must accommodate one maximum-sized edit.

If an edit and its automatic rollback both fail, Dirt reports the recovery edit
ID and exposes a pinned `recovery_required` record in history. A failed undo also
changes the same retained record to that status. Further edits in the world are
blocked, while history lookup and retrying `undo_edit` with that newest ID remain
available. Recovery records are never evicted and consume only their pre-reserved
capacity. An entry being undone is protected until the attempt finishes. The
configured limits therefore remain hard.

When a request fails after leaving a committed or recovery-required record, or
when rollback cannot be confirmed after its world becomes unavailable, its
structured bridge error includes `error.editId`. A transaction-finalization
error may also include the generated ID after confirmed rollback. The MCP server
preserves any received edit ID and recovers a valid nested ID from malformed
success or non-2xx responses on edit and undo routes when possible. Callers can
reconcile records returned by `get_edit_history` using `editId` or the record's
creating `callId`; an absent record means no retryable history remains. Every
failure mapped by a Dirt tool handler also includes its generated `error.callId`
for request correlation. Invalid tool names or arguments fail before Dirt
generates a call ID. MCP SDK output-validation failures occur outside Dirt error
mapping and do not carry a structured Dirt `error.callId`.

Ordinary edits still require already-loaded chunks. Retained history does not
keep chunks loaded, so undo uses Paper's asynchronous existing-chunk load with
generation disabled. After every required chunk is available, Dirt holds
reference-counted plugin tickets for the undo only. A load failure leaves the
record intact for a later retry.

Dry runs report exact changed counts without mutation. Fill and replacement
counts exclude coordinates whose selected destination is already present.
`set_blocks` counts exclude blocks already in their requested destination state.

Dirt uses FAWE's API side-effect profile, which does not request Minecraft
neighbor updates. This is appropriate for large deterministic builds, but
redstone and other physics-sensitive structures may require a separate,
explicit update mechanism.

`undo_edit` restores only a retained Dirt replacement, fill, or palette-based set.
It does not undo player actions, WorldEdit actions, or other plugin activity.

## Logging

The Paper console is an operator summary rather than a request trace. At the
configured `logging.console-level`, it reports lifecycle, completed mutations
and undos, actionable degraded conditions, and unexpected failures. Detailed
events are JSON Lines in
`plugins/DirtMCP/logs/dirt-detail.%g.jsonl`. Positive
`logging.detail-file-max-bytes` and `logging.detail-file-retained-files` values
bound size-based rotation, with two to 100 retained files. A detail-file failure
is reported as a prominent console error and does not stop the bridge.

The MCP process reserves stdout for MCP protocol traffic and writes structured
JSON Lines to stderr. Applicable `call_id`, `edit_id`, operation, world, outcome,
status or error code, and duration fields correlate MCP calls with Paper detail
records. Dirt logs bounded summaries, never bearer tokens, raw request bodies,
or complete block payloads.

## Limits and security

The shipped defaults allow regions of 262,144 blocks, at most 256 touched edit
chunks, at most 32 snapshotted inspection chunks, 65,536 changed blocks per edit,
detailed scans of 16,384 blocks, 512 results by default and at most 2,048, and 20
history entries per world, 100 entries across all worlds, and 1,310,720 changed
blocks across retained entries. JSON request bodies are capped at 262,144 bytes
with a five-second upload deadline. At most 32 authenticated bridge requests and
two inspection scans execute concurrently. Active operation limits, the
separate `editHistory` object, `logging` configuration, and every resolved
per-tool boolean in `tools` are available through `get_server_status`; the
history fields are
`maxEntriesPerWorld`, `maxEntriesTotal`, and `maxRetainedChangedBlocks`. The YAML
settings live in
`plugins/DirtMCP/config.yml`; every shipped setting and default is documented in
the plugin's
[configuration file](../paper-plugin/src/main/resources/config.yml).

Limits bound resource use; they are not a permissions or land-policy system.
Every bridge endpoint requires the shared bearer token, the bridge binds only to
`127.0.0.1`, and the operator remains responsible for access and backups.
