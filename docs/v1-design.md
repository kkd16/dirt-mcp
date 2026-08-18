# Version 1 design

V1 exposes a small synchronous tool surface. Mutation tools execute immediately
unless `dryRun` is explicitly enabled; there is no separate approval or commit
step.

## Tools

### `inspect_region`

Inputs:

- loaded world name;
- two inclusive block positions defining the region.

Returns normalized bounds, dimensions, volume, and block-state counts. V1 does
not return entities, player data, rendered images, or every block coordinate.

The bridge request is `POST /v1/inspect-region` with a JSON body:

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
  "blockStates": {
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

### `inspect_blocks`

Exact inspection returns geometry rather than palette totals. It defaults to a
sparse list of non-air blocks with canonical states and exact coordinates:

```json
{
  "world": "world",
  "min": { "x": -40, "y": 68, "z": -10 },
  "max": { "x": -24, "y": 85, "z": 7 },
  "include": ["minecraft:spruce_door[half=lower]"],
  "exclude": ["minecraft:snow"],
  "maxResults": 10000
}
```

`include` is an optional allowlist and `exclude` is applied afterward. Filter
values are validated block-state patterns: omitted properties match every value
of that property, so `minecraft:spruce_door` matches every spruce-door state
while `minecraft:spruce_door[half=lower]` is narrower. Air-family states are
excluded unless `includeAir` is `true`, even when an include pattern matches
them.

The default `mode` is `blocks`:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": -40, "y": 68, "z": -10 },
    "max": { "x": -24, "y": 85, "z": 7 }
  },
  "volume": 5508,
  "matchedBlocks": 1,
  "mode": "blocks",
  "blocks": [
    {
      "position": { "x": -32, "y": 71, "z": -4 },
      "state": "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false,powered=false]"
    }
  ]
}
```

Set `mode` to `runs` to return a deterministic, exact cover using
non-overlapping axis-aligned runs with inclusive endpoints:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": -40, "y": 68, "z": -10 },
    "max": { "x": -24, "y": 85, "z": 7 }
  },
  "volume": 5508,
  "matchedBlocks": 5,
  "mode": "runs",
  "runs": [
    {
      "state": "minecraft:stripped_spruce_log[axis=y]",
      "from": { "x": -37, "y": 71, "z": -4 },
      "to": { "x": -37, "y": 75, "z": -4 }
    }
  ]
}
```

Exact inspection has a hard inclusive-volume limit of 32,768 blocks, or the
configured general region limit when that is lower. `maxResults` defaults to
and cannot exceed 10,000; it limits block entries in `blocks` mode and run
entries in `runs` mode. The bridge returns `result_too_large` (413) instead of
truncating. Other failures match `inspect_region`. Both modes use snapshots and
never load or generate chunks.

### `inspect_view`

Structured view inspection returns one sparse orthographic surface layer. For
each viewport cell, it scans away from an integer origin and returns the first
non-air block. The origin is not scanned; distance `1` is the adjacent block.
Glass, liquids, leaves, and every other non-air state stop their sightline.

The bridge request is `POST /v1/inspect-view`:

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
  "visibleBlocks": 1,
  "blocks": [
    {
      "position": { "x": -1, "y": 71, "z": 2 },
      "offset": { "horizontal": -1, "vertical": 1, "distance": 3 },
      "state": "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"
    }
  ]
}
```

Blocks are ordered from the viewport's top row to bottom row, then left to
right; empty sightlines are omitted. `maxResults` defaults to 2,048 and may be
set from 1 through 10,000. Oversized results fail with `result_too_large`
instead of truncating. Scan volume is
`(2 * horizontalRadius + 1) * (2 * verticalRadius + 1) * maxDistance` and may
not exceed 32,768 blocks or the configured general region limit, whichever is
lower. The complete scan prism must be within world height and already-loaded
chunks. This tool returns block data rather than an image or perspective render.

### `replace_blocks`

Inputs:

- loaded world and bounded region;
- one source block state;
- one destination block state; and
- optional `dryRun`, defaulting to `false`.

A dry-run returns the exact matching and estimated changed-block counts. An
executed call replaces matches through one FAWE edit session and records one
undo entry. The bridge request is `POST /v1/replace-blocks`:

```json
{
  "world": "world",
  "min": { "x": 0, "y": 60, "z": 0 },
  "max": { "x": 15, "y": 80, "z": 15 },
  "source": "minecraft:stone",
  "destination": "minecraft:dirt",
  "dryRun": false
}
```

`dryRun` may be omitted and defaults to `false`. A successful response contains
canonical block states and normalized bounds:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": 0, "y": 60, "z": 0 },
    "max": { "x": 15, "y": 80, "z": 15 }
  },
  "source": "minecraft:stone",
  "destination": "minecraft:dirt",
  "dryRun": false,
  "matchedBlocks": 1280,
  "changedBlocks": 1280
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
- optional `dryRun`, defaulting to `false`.

A dry-run returns the region volume and expected changed-block count. An
executed call fills through one FAWE edit session and records one undo entry.
The expected count excludes blocks already in the destination state.

The bridge request is `POST /v1/fill-region`:

```json
{
  "world": "world",
  "min": { "x": 0, "y": 60, "z": 0 },
  "max": { "x": 15, "y": 80, "z": 15 },
  "destination": "minecraft:stone",
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
  "destination": "minecraft:stone",
  "dryRun": false,
  "volume": 5376,
  "changedBlocks": 5376
}
```

Fill requires already-loaded chunks and returns the same edit failures as
`replace_blocks`. A dry-run does not mutate or record history. Filling a region
already in the destination state reports zero changes and records no history.

### `undo_last_edit`

Input: loaded world name.

Undoes the newest successful Dirt MCP mutation for that world. It does not undo
console, player, WorldEdit, or other plugin activity. A successful undo consumes
the history entry. History does not survive restart.

The bridge request is `POST /v1/undo-last-edit`:

```json
{ "world": "world" }
```

A successful response reports the number of restored blocks:

```json
{ "world": "world", "changedBlocks": 1280 }
```

Undo returns `invalid_request` (400), `unauthorized` (401), `world_not_found`
(404), `nothing_to_undo` (409), `world_busy` (409), `world_unavailable`
(503), or `internal_error` (500).

The existing `dirt_status` tool remains available for bridge and version
diagnostics.

## Common rules

- Positions use integer block coordinates and inclusive bounds.
- Worlds must already be loaded; v1 does not load or create worlds.
- Block states use namespaced Minecraft identifiers and explicit state
  properties where needed.
- Unknown worlds, invalid states, oversized regions, and busy worlds produce
  structured errors without mutation.
- No-op mutations return `changedBlocks: 0` and do not create undo history.
- Reads and writes are limited by normalized region volume. Writes are also
  limited by their estimated changed-block count.
- V1 defaults are a maximum region volume of 1,000,000 blocks, a maximum of
  250,000 changed blocks per mutation, and 20 undo entries per world. The first
  two limits are configurable. Region volume cannot be configured above
  2,147,483,647 blocks because FAWE's affected-block counters are signed
  32-bit integers.
- A world accepts one Dirt MCP mutation at a time.

These checks bound resource use; they are not a permissions system. The server
operator controls who can reach the local MCP process and is responsible for
backups.

## Configuration

The v1 configuration surface is intentionally small:

```yaml
bridge:
  port: 8765

limits:
  max-region-volume: 1000000
  max-changed-blocks: 250000
```

The bearer token is supplied to both processes as `DIRT_MCP_BRIDGE_TOKEN` and is
sent on every bridge request as `Authorization: Bearer <token>`. It is never
committed. Source-development commands generate an ignored token under
`paper-plugin/run/` and pass it to both processes. The bridge address is not
configurable and remains `127.0.0.1`.
