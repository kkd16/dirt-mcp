# Version 1 behavior

V1 is a synchronous, local tool surface for loaded Paper worlds. Mutation tools
execute immediately unless `dryRun` is enabled. The authoritative HTTP request,
response, and error schemas are in
[`protocol/openapi.yaml`](../protocol/openapi.yaml); this document records the
behavior that matters when choosing and combining tools.

## Tools

| MCP tool                    | Bridge operation                     | Purpose                                                                   |
| --------------------------- | ------------------------------------ | ------------------------------------------------------------------------- |
| `ping_server`               | `GET /v1/ping`                       | Verify the authenticated Dirt, Paper, and FAWE path without mutation.     |
| `get_server_status`         | `GET /v1/server-status`              | Return builds, performance, players, loaded worlds, limits, and defaults. |
| `count_region_block_states` | `POST /v1/count-region-block-states` | Count canonical block states in an inclusive region.                      |
| `get_region_blocks`         | `POST /v1/get-region-blocks`         | Return filtered exact blocks or lossless axis-aligned runs.               |
| `scan_orthographic_view`    | `POST /v1/scan-orthographic-view`    | Find the first non-air block on each bounded world-axis sightline.        |
| `replace_region_blocks`     | `POST /v1/replace-region-blocks`     | Replace a union of block-state patterns with a destination palette.       |
| `fill_region`               | `POST /v1/fill-region`               | Fill a region from a destination palette.                                 |
| `set_blocks`                | `POST /v1/set-blocks`                | Apply different states at distinct explicit positions as one edit.        |
| `undo_last_dirt_edit`       | `POST /v1/undo-last-dirt-edit`       | Undo the newest retained Dirt edit in one world.                          |
| `run_minecraft_commands`    | `POST /v1/run-minecraft-commands`    | Dispatch an ordered command batch with operator-level permissions.        |

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
properties match any value. The `runs` format returns a deterministic,
non-overlapping exact cover using inclusive axis-aligned spans.

`scan_orthographic_view` scans away from an origin, beginning at distance one,
and stops at the first non-air block on each sightline. Horizontal views use
world-up as their vertical axis. Up and down views use east as horizontal and
north as vertical. The bridge returns explicit blocks; the MCP-only `grid`
format converts them to a one-based `blockStatePalette` with aligned state-index
and distance rows. Zero denotes an empty sightline.

Inspection result caps never truncate data. Dirt returns `result_too_large`
when exact blocks, runs, or visible blocks exceed the applicable cap.

## Edits and undo

`replace_region_blocks` and `fill_region` operate on inclusive cuboids.
`set_blocks` is the sparse counterpart for mixed-material structures: every
position can have a different destination state while the batch remains one
mutation and one undo entry.

Replacement sources are a non-empty array of block-state patterns. Patterns
are ORed, and omitted properties match any value, so `minecraft:oak_stairs`
matches every oak-stair state while an explicitly supplied property constrains
the match. Destination palettes contain exact block states. If every palette
entry omits `weight`, states have equal per-block probability. Otherwise every
entry supplies a whole-number percentage and the weights total 100. Weighted
results are probabilistic rather than exact quotas.

Both cuboid tools accept an optional signed 32-bit `seed`. Omission generates a
fresh seed that is returned in the response. Reusing a seed with the same
ordered palette and unchanged world reproduces each coordinate's choice, so a
dry run can be replayed as an edit with the same changed count.

Before mutation, Dirt validates all coordinates, loaded chunks, and block
states. Sparse positions must be distinct; duplicates are rejected rather than
given last-write semantics. Block states are canonicalized at the Paper
boundary. A sparse request may not contain more positions than the configured
region-volume limit, and all edits must stay within the changed-block limit.

Each executed non-empty operation uses one FAWE edit session. A world accepts
one Dirt mutation at a time, and a competing request fails with `world_busy`.
Successful non-empty edits enter bounded, in-memory, per-world history when
history is enabled. Dry runs and no-ops create no history. History is cleared on
restart and can be disabled by setting its depth to zero.

Dry runs report exact changed counts without mutation. Fill and replacement
counts exclude coordinates whose selected destination is already present.
Sparse counts exclude blocks already in their requested destination state.

Dirt uses FAWE's API side-effect profile, which does not request Minecraft
neighbor updates. This is appropriate for large deterministic builds, but
redstone and other physics-sensitive structures may require a separate,
explicit update mechanism.

`undo_last_dirt_edit` restores only the newest retained Dirt replacement, fill,
or sparse edit in that world. It does not undo commands, player actions,
WorldEdit actions, or other plugin activity. An undo entry is consumed only
after successful completion.

## Command dispatch

`run_minecraft_commands` accepts a non-empty `commands` array, even for one
command. One leading in-game slash is optional. Paper attempts every command
once in supplied order and returns its normalized text, bounded synchronous
feedback, optional actionable failure `message`, optional original Paper
`rawMessage`, and one of `dispatched`, `not_found`, or `dispatch_failed`.

The sender has console-equivalent permissions but is not a player. Player-only
commands, `@s`, and relative-position context therefore differ from a real
operator. `dispatched` means Paper found and invoked the command without a
dispatch exception; Bukkit does not expose the Brigadier result value, so Dirt
does not infer semantic success from ordinary feedback.

Commands run on Paper's main thread. Their effects are immediate and are not
covered by FAWE limits, per-world mutation locks, or Dirt undo history.

## Limits and security

The shipped defaults allow regions of 262,144 blocks and at most 256 touched
chunks, 65,536 changed blocks per edit, detailed scans of 16,384 blocks, 512
results by default and at most 2,048, 10 commands per request, 8,192 retained
command-feedback characters, and 20 undo entries per world. JSON request bodies
are capped at 262,144 bytes, and at most 32 authenticated bridge requests execute
concurrently. Active values are available through `get_server_status` and are
configured in `plugins/DirtMCP/config.yml`; the complete shipped file is shown
in the repository [README](../README.md#running-on-a-paper-server).

Limits bound resource use; they are not a permissions or land-policy system.
Every bridge endpoint requires the shared bearer token, the bridge binds only to
`127.0.0.1`, and the operator remains responsible for access and backups.
