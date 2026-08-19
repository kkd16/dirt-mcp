# Version 1 design

V1 exposes a small synchronous tool surface. Mutation tools execute immediately
unless `dryRun` is explicitly enabled; there is no separate approval or commit
step.

## Tools

### `count_region_block_states`

Inputs:

- loaded world name;
- two inclusive block positions defining the region.

Returns normalized bounds, dimensions, volume, and block-state counts. V1 does
not return entities, player data, rendered images, or every block coordinate.

The bridge request is `POST /v1/count-region-block-states` with a JSON body:

```json
{
  "world": "world",
  "min": { "x": 0, "y": 60, "z": 0 },
  "max": { "x": 15, "y": 80, "z": 15 }
}
```

Coordinates are signed 32-bit integers. The request object and both position
objects reject unknown fields. A successful response has this shape:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": 0, "y": 60, "z": 0 },
    "max": { "x": 15, "y": 80, "z": 15 }
  },
  "dimensions": { "x": 16, "y": 21, "z": 16 },
  "volume": 5376,
  "blockStateCounts": {
    "minecraft:air": 4096,
    "minecraft:stone": 1280
  }
}
```

Bounds in the response are normalized independently on each axis. Dimensions
and volume are positive integers, counts are non-negative integers, and
block-state keys use canonical namespaced state strings. Counts sum to
`volume`.

The endpoint uses the common error envelope:

```json
{
  "error": {
    "code": "world_not_found",
    "message": "World is not loaded: example"
  }
}
```

Its defined failures are `invalid_request` (400), `unauthorized` (401),
`world_not_found` (404), `region_too_large` (413), `world_unavailable` (503),
and `internal_error` (500). Inspection uses snapshots of already-loaded chunks;
it never loads or generates terrain.

### `get_region_blocks`

Exact inspection returns geometry rather than palette totals. The shipped
configuration defaults to a sparse list of non-air blocks with canonical states
and exact coordinates:

```json
{
  "world": "world",
  "min": { "x": -40, "y": 68, "z": -10 },
  "max": { "x": -24, "y": 85, "z": 7 },
  "includeBlockStatePatterns": ["minecraft:spruce_door[half=lower]"],
  "excludeBlockStatePatterns": ["minecraft:snow"],
  "maxResults": 10000
}
```

`includeBlockStatePatterns` is an optional allowlist and
`excludeBlockStatePatterns` is applied afterward. Filter values are validated
block-state patterns: omitted properties match every value of that property,
so `minecraft:spruce_door` matches every spruce-door state while
`minecraft:spruce_door[half=lower]` is narrower. Air-family states are excluded
unless `includeAir` is `true`, even when an include pattern matches them.

The shipped default `format` is `blocks`:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": -40, "y": 68, "z": -10 },
    "max": { "x": -24, "y": 85, "z": 7 }
  },
  "volume": 5508,
  "matchedBlockCount": 1,
  "format": "blocks",
  "blocks": [
    {
      "position": { "x": -32, "y": 71, "z": -4 },
      "blockState": "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false,powered=false]"
    }
  ]
}
```

Set `format` to `runs` to return a deterministic, exact cover using
non-overlapping axis-aligned runs with inclusive endpoints:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": -40, "y": 68, "z": -10 },
    "max": { "x": -24, "y": 85, "z": 7 }
  },
  "volume": 5508,
  "matchedBlockCount": 5,
  "format": "runs",
  "runs": [
    {
      "blockState": "minecraft:stripped_spruce_log[axis=y]",
      "from": { "x": -37, "y": 71, "z": -4 },
      "to": { "x": -37, "y": 75, "z": -4 }
    }
  ]
}
```

Exact inspection uses the lower of `max-exact-inspection-volume` and the general
region limit. The shipped exact-volume default is 32,768 blocks. Its default and
maximum result counts are independently configurable and ship as 10,000; the
request cap limits block entries in `blocks` format and run entries in `runs`
format. The default format and air inclusion are configurable. The bridge returns
`result_too_large` (413) instead of truncating. Other failures match
`count_region_block_states`. Both formats use snapshots and never load or
generate chunks.

### `scan_orthographic_view`

Structured view inspection returns one sparse orthographic surface layer. For
each viewport cell, it scans away from an integer origin and returns the first
non-air block. The origin is not scanned; distance `1` is the adjacent block.
Glass, liquids, leaves, and every other non-air state stop their sightline.

The bridge request is `POST /v1/scan-orthographic-view`:

```json
{
  "world": "world",
  "origin": { "x": 0, "y": 70, "z": 5 },
  "direction": "north",
  "horizontalRadius": 1,
  "verticalRadius": 1,
  "maxDistance": 8,
  "maxResults": 2048
}
```

Directions are `north`, `east`, `south`, `west`, `up`, and `down`. They use
Minecraft world axes: east is +X, up is +Y, south is +Z, and their opposites
use the negative axes. Horizontal views use world-up as the positive vertical
axis. Both vertical views use east as positive horizontal and north as positive
vertical so they read like a Minecraft map.

A successful response includes both absolute positions and view-relative
offsets:

```json
{
  "world": "world",
  "origin": { "x": 0, "y": 70, "z": 5 },
  "direction": "north",
  "format": "blocks",
  "basis": {
    "forward": { "x": 0, "y": 0, "z": -1 },
    "horizontal": { "x": 1, "y": 0, "z": 0 },
    "vertical": { "x": 0, "y": 1, "z": 0 }
  },
  "viewport": {
    "horizontalRadius": 1,
    "verticalRadius": 1,
    "maxDistance": 8
  },
  "bounds": {
    "min": { "x": -1, "y": 69, "z": -3 },
    "max": { "x": 1, "y": 71, "z": 4 }
  },
  "scannedVolume": 72,
  "visibleBlockCount": 1,
  "blocks": [
    {
      "position": { "x": -1, "y": 71, "z": 2 },
      "offset": { "horizontal": -1, "vertical": 1, "distance": 3 },
      "blockState": "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"
    }
  ]
}
```

Blocks are ordered from the viewport's top row to bottom row, then left to
right; empty sightlines are omitted. The shipped `maxResults` default is 2,048
and the shipped maximum is 10,000; both are configurable. Oversized results
fail with `result_too_large` instead of truncating. Scan volume is
`(2 * horizontalRadius + 1) * (2 * verticalRadius + 1) * maxDistance` and may
not exceed the configured view-volume or general region limit, whichever is
lower. The shipped view-volume default is 32,768 blocks. The complete scan
prism must be within world height and already-loaded chunks. This tool returns
block data rather than an image or perspective render.

The MCP tool additionally accepts `format: "grid"`; this option is consumed by
the TypeScript process and is not sent to the bridge. Grid responses retain the
same view metadata and encode the sparse bridge result as `blockStatePalette`,
`blockStateIndexRows`, and `distanceRows`. Palette indices are one-based, while
`0` in both aligned matrices means an empty sightline. Rows remain
top-to-bottom and cells remain left-to-right, so basis vectors, offsets inferred
from the viewport radii, and distances reconstruct every absolute position.
Omitting `format`, or setting it to `blocks`, preserves the explicit bridge
response.

View scan-limit errors report both the requested scan volume and the effective
maximum to make radius and distance adjustments mechanical.

### `replace_region_blocks`

Inputs:

- loaded world and bounded region;
- one source block state;
- one destination block state; and
- optional `dryRun`, using the configured replace default when omitted.

A dry-run returns the exact matching and estimated changed-block counts. An
executed call replaces matches through one FAWE edit session and records one
undo entry. The bridge request is `POST /v1/replace-region-blocks`:

```json
{
  "world": "world",
  "min": { "x": 0, "y": 60, "z": 0 },
  "max": { "x": 15, "y": 80, "z": 15 },
  "sourceBlockState": "minecraft:stone",
  "destinationBlockState": "minecraft:dirt",
  "dryRun": false
}
```

`dryRun` may be omitted; the shipped replace default is `false`. A successful
response contains canonical block states and normalized bounds:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": 0, "y": 60, "z": 0 },
    "max": { "x": 15, "y": 80, "z": 15 }
  },
  "sourceBlockState": "minecraft:stone",
  "destinationBlockState": "minecraft:dirt",
  "dryRun": false,
  "matchedBlockCount": 1280,
  "changedBlockCount": 1280
}
```

Replacement requires already-loaded chunks and returns `invalid_request` (400),
`world_not_found` (404), `world_busy` (409), `region_too_large` (413),
`change_limit_exceeded` (413), `world_unavailable` (503), or `internal_error`
(500). A dry-run does not mutate or record history. Replacing a state with
itself reports matches but zero changes and records no history.

### `fill_region`

Inputs:

- loaded world and bounded region;
- destination block state; and
- optional `dryRun`, using the configured fill default when omitted.

A dry-run returns the region volume and expected changed-block count. An
executed call fills through one FAWE edit session and records one undo entry.
The expected count excludes blocks already in the destination state.

The bridge request is `POST /v1/fill-region`:

```json
{
  "world": "world",
  "min": { "x": 0, "y": 60, "z": 0 },
  "max": { "x": 15, "y": 80, "z": 15 },
  "blockState": "minecraft:stone",
  "dryRun": false
}
```

A successful response contains the normalized bounds and canonical destination
state:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": 0, "y": 60, "z": 0 },
    "max": { "x": 15, "y": 80, "z": 15 }
  },
  "blockState": "minecraft:stone",
  "dryRun": false,
  "volume": 5376,
  "changedBlockCount": 5376
}
```

Fill requires already-loaded chunks and returns the same edit failures as
`replace_region_blocks`. A dry-run does not mutate or record history. Filling a
region already in the destination state reports zero changes and records no
history.

### `set_blocks`

Sets distinct explicit positions to independently chosen block states in one
sparse mutation. This is the compact operation for mixed-material structures,
redstone layouts, furniture, and other edits that are not one cuboid fill.

The bridge request is `POST /v1/set-blocks`:

```json
{
  "world": "world",
  "changes": [
    {
      "position": { "x": 60, "y": 76, "z": 16 },
      "blockState": "minecraft:lever[face=floor,facing=west,powered=false]"
    },
    {
      "position": { "x": 61, "y": 76, "z": 16 },
      "blockState": "minecraft:redstone_wire[power=0]"
    }
  ],
  "dryRun": false
}
```

Every entry is validated before editing begins. Positions must be distinct,
within the loaded world's height range, and located in already-loaded chunks;
duplicate positions are rejected rather than assigned ordering semantics. Block
states are canonicalized at the Paper boundary. The number of positions may not
exceed `max-region-volume`, and the number that differ from the live world may
not exceed `max-changed-blocks`.

One FAWE edit session applies the complete list and a successful non-empty batch
becomes one Dirt undo entry. Dirt explicitly selects FAWE's API side-effect
profile, which omits neighbor updates; recalculating redstone or other
physics-sensitive structures remains a separate operation. A dry-run returns
the same exact counts without mutation or history:

```json
{
  "world": "world",
  "dryRun": false,
  "blockCount": 2,
  "changedBlockCount": 1,
  "unchangedBlockCount": 1
}
```

Sparse setting returns the same edit failures as `fill_region`. Omitted
`dryRun` uses the configured set-blocks default.

### `undo_last_dirt_edit`

Input: loaded world name.

Undoes the newest successful Dirt MCP mutation for that world. It does not undo
console, player, WorldEdit, or other plugin activity. A successful undo consumes
the history entry. History does not survive restart.

The bridge request is `POST /v1/undo-last-dirt-edit`:

```json
{ "world": "world" }
```

A successful response reports the number of restored blocks:

```json
{ "world": "world", "changedBlockCount": 1280 }
```

Undo returns `invalid_request` (400), `unauthorized` (401), `world_not_found`
(404), `nothing_to_undo` (409), `world_busy` (409), `world_unavailable`
(503), or `internal_error` (500).

`ping_server` calls `GET /v1/ping`. It returns only `{ "status": "ok" }` after
the authenticated bridge, Dirt plugin, Paper world integration, FAWE
world-editing platform, and a non-mutating Paper-backed FAWE edit session are
all ready.

`get_server_status` calls `GET /v1/server-status` and returns lightweight
grounding context: the Minecraft, Paper, Dirt MCP, and FAWE builds; current TPS
and tick time; online players with worlds, game modes, and block positions;
loaded-world bounds, spawn, time, and weather; and active Dirt tool limits and
defaults.

### `run_minecraft_commands`

Runs one or more registered vanilla, Paper, or plugin commands in supplied
order. The bridge request is `POST /v1/run-minecraft-commands`; even one command
uses the `commands` array:

```json
{ "commands": ["/say hello", "time set day"] }
```

One in-game leading slash is optional and removed before dispatch. Paper runs
the batch synchronously on its main thread with a feedback-capturing sender that
has console-equivalent permissions. This supported sender is not a player; its
current Paper name is `FeedbackForwardingSender`, and player-only commands,
`@s`, and relative position context therefore differ from a real operator.

Each response entry contains the normalized command, bounded plain-text
feedback, a nullable message, and one of `dispatched`, `not_found`, or
`dispatch_failed`. Every command is attempted once in order, including after a
failure. `dispatched` means Paper found and invoked the command without a
dispatch exception; Bukkit does not expose the command's Brigadier result
value, so semantic failures reported as ordinary feedback are not inferred.

The shipped limits are 20 commands and 32,768 retained feedback characters per
request. Command effects execute immediately and are not subject to Dirt's FAWE
volume or changed-block limits, per-world mutation lock, or undo history.

## Common rules

- Positions use integer block coordinates and inclusive bounds.
- Worlds must already be loaded; v1 does not load or create worlds.
- Block states use namespaced Minecraft identifiers and explicit state
  properties where needed.
- Unknown worlds, invalid states, oversized regions, and busy worlds produce
  structured errors without mutation.
- No-op mutations return `changedBlockCount: 0` and do not create undo history.
- Cuboid reads and writes are limited by normalized region volume; sparse edits
  apply the same limit to their number of explicit positions. Writes are also
  limited by their estimated changed-block count.
- Shipped defaults are a maximum region volume of 1,000,000 blocks, 250,000
  changed blocks per mutation, and 20 undo entries per world. All numeric
  resource limits and omission defaults listed below are configurable. Numeric
  configuration uses signed 32-bit integers because the bridge and FAWE
  counters use that domain.
- A world accepts one Dirt MCP mutation at a time.

These checks bound resource use; they are not a permissions system. The server
operator controls who can reach the local MCP process and is responsible for
backups.

## Configuration

`plugins/DirtMCP/config.yml` contains all user-adjustable Paper behavior:

```yaml
bridge:
  port: 8765
  backlog: 0
  shutdown-delay-seconds: 0
  max-request-bytes: 8192
  minimum-token-bytes: 32

limits:
  max-region-volume: 1000000
  max-changed-blocks: 250000
  max-exact-inspection-volume: 32768
  default-exact-results: 10000
  max-exact-results: 10000
  max-view-volume: 32768
  default-view-results: 2048
  max-view-results: 10000
  max-commands-per-request: 20
  max-command-feedback-characters: 32768
  undo-history-per-world: 20

defaults:
  exact-inspection-include-air: false
  exact-inspection-mode: blocks
  replace-dry-run: false
  fill-dry-run: false
  set-blocks-dry-run: false
```

The bearer token is supplied to both processes as `DIRT_MCP_BRIDGE_TOKEN` and is
sent on every bridge request as `Authorization: Bearer <token>`. It is never
committed. Source-development commands generate an ignored token under
`paper-plugin/run/` and pass it to both processes. The bridge address is not
configurable and remains `127.0.0.1`. `DIRT_MCP_BRIDGE_PORT` overrides the YAML
port for deployments that inject networking configuration. Settings are
validated and snapshotted at plugin startup, so changes require a Paper restart.
