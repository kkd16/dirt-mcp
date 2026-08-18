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
  two limits are configurable.
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
