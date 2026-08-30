# MCP tool reference

Dirt exposes synchronous tools for inspecting and editing a live Paper world.
The usual workflow is:

```text
inspect -> preview -> edit -> verify -> undo if needed
```

The web service owns this catalog, its schemas, annotations, and model-facing
defaults. For each authenticated MCP request it reads Paper's allowed bridge
operation IDs from `/v1/capabilities` and registers only the corresponding
tools. Operations that are not admitted do not appear in MCP discovery.

## What is sent over MCP?

Yes: MCP Streamable HTTP messages are JSON-RPC, and each tool's arguments and
structured result are JSON objects. Dirt accepts independent authenticated
`POST /mcp` requests; it does not expose legacy SSE, GET streams, or MCP
sessions. During discovery, `tools/list` advertises an
`inputSchema` and `outputSchema` in JSON Schema. Those live schemas, generated
from Dirt's Zod schemas, define the machine-readable structure for inputs and
non-error completions. Dirt also enforces documented cross-field refinements at
runtime. Every input object is strict, so unknown fields are rejected. Results
with `isError=true` instead use the common, command, or partial-undo failure
shapes documented below.

The agent chooses `name` and `arguments`. The MCP host adds the JSON-RPC
envelope and protocol metadata. A complete call looks like this:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "ping_server",
    "arguments": {},
    "_meta": {
      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
      "io.modelcontextprotocol/clientInfo": {
        "name": "example-host",
        "version": "1.0.0"
      },
      "io.modelcontextprotocol/clientCapabilities": {}
    }
  }
}
```

The response contains a short text summary and the canonical result in
`structuredContent`:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [{ "type": "text", "text": "ok" }],
    "structuredContent": { "status": "ok" },
    "resultType": "complete",
    "_meta": {
      "io.modelcontextprotocol/serverInfo": {
        "name": "dirt-mcp",
        "version": "0.1.0"
      }
    }
  }
}
```

Most hosts hide this envelope from the agent. The examples below therefore show
only the exact `arguments` it supplies and the `structuredContent` it uses.
Example values are concrete JSON payloads, not a replacement for the exhaustive
schemas returned by `tools/list`. The
[OpenAPI contract](../protocol/openapi.yaml) describes the separate, internal
HTTP bridge between the web service and Paper. MCP generates an
`X-Dirt-Call-Id` UUIDv4 for every bridge request and fully materializes fixed
defaults before sending it.

## Tools at a glance

| Tool                        | Use it to                                             | Changes state |
| --------------------------- | ----------------------------------------------------- | ------------- |
| `ping_server`               | Check Dirt, Paper, the bridge, and FAWE end to end    | No            |
| `get_server_status`         | Read builds, performance, worlds, players, and limits | No            |
| `count_region_block_states` | Count block states without returning positions        | No            |
| `get_blocks`                | Read exact, replay-ready block geometry               | No            |
| `scan_orthographic_view`    | Inspect a flat world-axis view at a chosen depth      | No            |
| `get_player_context`        | Capture a player's pose, location, and optional state | No            |
| `get_perspective_view`      | Trace block-collision hits from a player or camera    | No            |
| `replace_region_blocks`     | Replace matching states throughout a cuboid           | Yes           |
| `set_blocks`                | Place singleton blocks and inclusive cuboids          | Yes           |
| `get_edit_history`          | Read retained, undoable Dirt edits                    | No            |
| `undo_edits`                | Restore an exact newest-first history prefix          | Yes           |
| `run_minecraft_commands`    | Dispatch an ordered operator-level command batch      | Yes           |

## Rules shared by tools

- World names are exact and case-sensitive and must identify an already-loaded
  Paper world. Inspection and new edits do not load or generate chunks. Undo
  may reload existing chunks without generating terrain.
- Block coordinates are signed 32-bit integers. Region corners are inclusive,
  may be supplied in either order, and are normalized in results.
- A canonical state includes its namespace and resolved properties, for example
  `minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]`.
  A match pattern may omit properties to match any value. A destination may omit
  properties; Paper resolves those properties to its defaults before editing.
- Paper's request, region, chunk, result, palette, change, command, concurrency,
  and history safety limits still apply. Read client-relevant operation values with
  `get_server_status` and `include.configuration=true`. Inspections and edits
  fail rather than truncate when a hard limit is exceeded.
- Inspection structures use zero-based palette indices and origin-relative
  tuples. A placement is `[paletteIndex, x, y, z]`; a run is
  `[paletteIndex, x, y, z, toX, toY, toZ]` with forward, inclusive corners.
- Every edit needs a concise label. `dryRun=true` previews exact counts without
  mutation. Reusing the returned seed with the same input and unchanged world
  reproduces palette choices.

## Status

### `ping_server`

Use this to verify the complete authenticated path through Dirt, Paper, and a
Paper-backed FAWE session.

Arguments:

```json
{}
```

Structured result:

```json
{
  "status": "ok"
}
```

### `get_server_status`

Use this to inspect runtime versions and health, discover loaded worlds and
players, or read the active limits that constrain other tools.

MCP sends these selections as three explicit booleans in the bridge's
`POST /v1/server-status` request.

Arguments:

```json
{
  "include": {
    "players": false,
    "worlds": true,
    "configuration": false
  }
}
```

Structured result:

```json
{
  "builds": {
    "minecraft": "26.2",
    "paper": "26.2-121-main",
    "dirtPlugin": "0.1.0",
    "fawe": "2.15.4"
  },
  "performance": {
    "tpsOneMinute": 19.98,
    "averageTickTimeMillis": 4.25
  },
  "players": null,
  "worlds": [
    {
      "name": "world",
      "environment": "normal",
      "minY": -64,
      "maxY": 319,
      "spawn": { "x": 0, "y": 64, "z": 0 },
      "timeOfDay": 6000,
      "storm": false,
      "thundering": false,
      "playerCount": 1
    }
  ],
  "configuration": null
}
```

The MCP defaults include worlds and exclude players and configuration. Excluded
sections are always `null`. Configuration contains client-relevant operation
limits and bounded edit-history settings; it does not expose transport,
concurrency, logging, or MCP tool policy.

## Inspection

### `count_region_block_states`

Use this when totals are enough and exact block positions are unnecessary. Air
is included in the histogram.

Arguments:

```json
{
  "world": "world",
  "min": { "x": 1, "y": 2, "z": 3 },
  "max": { "x": 2, "y": 2, "z": 3 }
}
```

Structured result:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": 1, "y": 2, "z": 3 },
    "max": { "x": 2, "y": 2, "z": 3 }
  },
  "dimensions": { "x": 2, "y": 1, "z": 1 },
  "volume": 2,
  "blockStateCounts": {
    "minecraft:stone": 1,
    "minecraft:air": 1
  }
}
```

### `get_blocks`

Use this to retrieve filtered, exact block geometry that can be copied or
replayed by `set_blocks`.

Arguments:

```json
{
  "world": "world",
  "min": { "x": 1, "y": 2, "z": 3 },
  "max": { "x": 2, "y": 2, "z": 3 },
  "includeBlockStatePatterns": ["minecraft:stone"],
  "excludeBlockStatePatterns": [],
  "includeAir": false,
  "maxResults": 100
}
```

Structured result:

```json
{
  "world": "world",
  "origin": { "x": 1, "y": 2, "z": 3 },
  "palettes": [[{ "blockState": "minecraft:stone" }]],
  "placements": [[0, 0, 0, 0]],
  "runs": []
}
```

Include patterns are applied first, then exclusions. The two pattern arrays may
contain at most 64 entries combined. MCP defaults both arrays to empty,
`includeAir` to false, and `maxResults` to 1024 before calling Paper. The lower
of `maxResults` and Paper's configured maximum is the effective ceiling; a
result exceeding it fails rather than truncates. Add `label` to the result before
sending it to `set_blocks`; changing `origin` copies the structure elsewhere.

### `scan_orthographic_view`

Use this for a flat view along a world axis. `depth=0` selects the first non-air
block on each sightline, `depth=1` the second, and so on.

Arguments:

```json
{
  "world": "world",
  "origin": { "x": 1, "y": 2, "z": 4 },
  "direction": "north",
  "horizontalRadius": 1,
  "verticalRadius": 1,
  "maxDistance": 3,
  "depth": 1,
  "maxResults": 100
}
```

Structured result:

```json
{
  "world": "world",
  "origin": { "x": 0, "y": 1, "z": 1 },
  "palettes": [
    [{ "blockState": "minecraft:gold_block" }],
    [{ "blockState": "minecraft:stone" }],
    [{ "blockState": "minecraft:oak_stairs[facing=north]" }]
  ],
  "placements": [
    [0, 1, 1, 0],
    [1, 0, 2, 1],
    [2, 2, 2, 2]
  ],
  "runs": []
}
```

Scanning starts one block away and excludes the requested origin. Directions
are `north`, `east`, `south`, `west`, `up`, or `down`. The result has the same
copy-ready structure as `get_blocks`. MCP defaults `depth` to 0 and
`maxResults` to 1024 before calling Paper; the same effective result ceiling
applies.

### `get_player_context`

Use this to capture one online player's identity, pose, exact position, and any
optional state needed for a later inspection or edit.

Arguments:

```json
{
  "player": "Builder"
}
```

Structured result:

```json
{
  "capturedAt": "2026-08-20T20:15:30Z",
  "player": {
    "name": "Builder",
    "uuid": "55555555-5555-4555-8555-555555555555"
  },
  "world": "world",
  "worldId": "22222222-2222-4222-8222-222222222222",
  "gameMode": "creative",
  "feetPosition": { "x": 12.25, "y": 70, "z": -3.5 },
  "blockPosition": { "x": 12, "y": 70, "z": -4 },
  "eyePosition": { "x": 12.25, "y": 71.62, "z": -3.5 },
  "rotation": { "yaw": 0, "pitch": 0 },
  "lookDirection": { "x": 0, "y": 0, "z": 1 },
  "pose": "standing",
  "onGround": true,
  "equipment": {
    "selectedHotbarSlot": 0,
    "mainHand": {
      "type": "minecraft:diamond_pickaxe",
      "amount": 1,
      "maxStackSize": 1,
      "damage": 12,
      "maxDamage": 1561,
      "unbreakable": false,
      "enchantments": [{ "type": "minecraft:efficiency", "level": 5 }]
    },
    "offHand": null,
    "helmet": null,
    "chestplate": null,
    "leggings": null,
    "boots": null
  },
  "inventory": null,
  "enderChest": null,
  "vitals": null,
  "movement": null,
  "client": null,
  "effects": null
}
```

The selector is an exact case-insensitive online name or canonical UUID.
MCP defaults equipment on and inventory, ender chest, vitals, movement, client
data, and effects off, then sends every include boolean to Paper. Every excluded
optional section is `null`. The capture is point-in-time, so recapture it before
relying on player state that may have changed.

### `get_perspective_view`

Use this to trace a sparse grid of first Paper block-collision hits from a
player's current eye pose or from a synthetic camera.

Arguments:

```json
{
  "source": {
    "type": "player",
    "player": "Builder"
  }
}
```

Structured result:

```json
{
  "capturedAt": "2026-08-20T20:15:30Z",
  "source": {
    "type": "player",
    "player": {
      "name": "Builder",
      "uuid": "55555555-5555-4555-8555-555555555555"
    }
  },
  "world": "world",
  "worldId": "22222222-2222-4222-8222-222222222222",
  "cameraPosition": { "x": 12.25, "y": 71.62, "z": -3.5 },
  "rotation": { "yaw": 0, "pitch": 0 },
  "lookDirection": { "x": 0, "y": 0, "z": 1 },
  "basis": {
    "forward": { "x": 0, "y": 0, "z": 1 },
    "right": { "x": -1, "y": 0, "z": 0 },
    "up": { "x": 0, "y": 1, "z": 0 }
  },
  "viewport": {
    "width": 21,
    "height": 13,
    "verticalFieldOfViewDegrees": 70,
    "horizontalFieldOfViewDegrees": 97.04074224762336,
    "maxDistance": 32,
    "fluidCollision": "never",
    "ignorePassableBlocks": false
  },
  "checkedChunkCount": 4,
  "blockStatePalette": ["minecraft:stone"],
  "hits": [
    {
      "row": 6,
      "column": 10,
      "blockStateIndex": 1,
      "blockPosition": { "x": 12, "y": 71, "z": 5 },
      "hitPosition": { "x": 12.25, "y": 71.62, "z": 5 },
      "face": "north",
      "distance": 8.5
    }
  ],
  "crosshairHitIndex": 0
}
```

MCP defaults the viewport to 21 by 13 rays, 70-degree vertical FOV, 32-block
distance, no fluid collision, and retaining passable collisions, then sends all
options to Paper. Width and height must be odd. Palette indices in hits are one-based;
`crosshairHitIndex` is a zero-based index into `hits`, or `null` on a miss. This
is collision geometry, not entities, lighting, particles, resource packs, or a
client framebuffer. No terrain is loaded.

## Editing and undo

Both edit tools return `outcome` as `preview`, `no_change`, or `committed`.
Only a positive committed edit has a non-null `edit` record. Committed success
means FAWE finished and Dirt retained the undo data.

At the MCP surface, `dryRun` defaults to false, `seed` is optional, and
`maxChangedBlocks` is optional. Before calling Paper, MCP generates any omitted
seed and sends null for an omitted caller ceiling; the bridge request itself is
fully explicit.

A palette contains one or more destination block-state inputs, which Paper
resolves to exact states. Omit every `weight` for equal probability, or give
every entry a whole-number weight whose total is 100. Selection happens
independently at every coordinate.

### `replace_region_blocks`

Use this to replace every block matching any source pattern inside one inclusive
cuboid.

Arguments:

```json
{
  "world": "world",
  "label": "Preview floor replacement",
  "min": { "x": 1, "y": 2, "z": 3 },
  "max": { "x": 2, "y": 2, "z": 3 },
  "sourceBlockStatePatterns": ["minecraft:stone"],
  "destinationPalette": [{ "blockState": "minecraft:dirt", "weight": 100 }],
  "seed": 123,
  "dryRun": true,
  "maxChangedBlocks": 1000
}
```

Structured result:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": 1, "y": 2, "z": 3 },
    "max": { "x": 2, "y": 2, "z": 3 }
  },
  "seed": 123,
  "outcome": "preview",
  "edit": null,
  "matchedBlockCount": 1,
  "changedBlockCount": 1
}
```

Source patterns may omit state properties and are unioned. `maxChangedBlocks`
adds a stricter per-call ceiling; omission means no caller ceiling beyond
Paper's safety cap.

### `set_blocks`

Use this to apply related singleton placements and inclusive cuboid runs as one
labeled, undoable edit.

Arguments:

```json
{
  "world": "world",
  "label": "Build west accent",
  "origin": { "x": 1, "y": 2, "z": 3 },
  "palettes": [
    [
      { "blockState": "minecraft:stone", "weight": 75 },
      { "blockState": "minecraft:glass", "weight": 25 }
    ]
  ],
  "placements": [[0, 0, 0, 0]],
  "runs": [[0, 4, 0, 0, 4, 0, 0]],
  "seed": 123,
  "dryRun": false,
  "maxChangedBlocks": 2
}
```

Structured result:

```json
{
  "world": "world",
  "bounds": {
    "min": { "x": 1, "y": 2, "z": 3 },
    "max": { "x": 5, "y": 2, "z": 3 }
  },
  "seed": 123,
  "outcome": "committed",
  "edit": {
    "editId": "11111111-1111-4111-8111-111111111111",
    "callId": "33333333-3333-4333-8333-333333333333",
    "label": "Build west accent",
    "operation": "set_blocks",
    "world": "world",
    "worldId": "22222222-2222-4222-8222-222222222222",
    "bounds": {
      "min": { "x": 1, "y": 2, "z": 3 },
      "max": { "x": 5, "y": 2, "z": 3 }
    },
    "changedBlockCount": 1,
    "completedAt": "2026-08-19T12:34:56Z",
    "status": "committed"
  },
  "blockCount": 2,
  "changedBlockCount": 1,
  "unchangedBlockCount": 1
}
```

Palette indices must exist, geometry may not overlap, and expanded coordinates
must fit signed 32-bit values. Empty `palettes`, `placements`, and `runs` form a
valid no-op with null bounds and zero counts. Placement does not trigger
Minecraft neighbor physics.

### `get_edit_history`

Use this immediately before undo to read every currently retained edit in one
loaded world, newest first.

Arguments:

```json
{
  "world": "world"
}
```

Structured result:

```json
{
  "world": "world",
  "edits": [
    {
      "editId": "11111111-1111-4111-8111-111111111111",
      "callId": "33333333-3333-4333-8333-333333333333",
      "label": "Build west accent",
      "operation": "set_blocks",
      "world": "world",
      "worldId": "22222222-2222-4222-8222-222222222222",
      "bounds": {
        "min": { "x": 1, "y": 2, "z": 3 },
        "max": { "x": 5, "y": 2, "z": 3 }
      },
      "changedBlockCount": 1,
      "completedAt": "2026-08-19T12:34:56Z",
      "status": "committed"
    }
  ]
}
```

Dry runs, no-ops, consumed or evicted edits, commands, and changes made outside
Dirt are absent. History is bounded, in memory, flat, and has no redo or
branching. It is cleared when its world unloads or the server stops.

### `undo_edits`

Use this to restore and consume one or more retained edits. IDs must be the
exact newest-first prefix returned by `get_edit_history`; edits cannot be
reordered, skipped, or bypassed.

Arguments:

```json
{
  "world": "world",
  "editIds": ["11111111-1111-4111-8111-111111111111"]
}
```

Structured result:

```json
{
  "outcome": "completed",
  "world": "world",
  "undoneEdits": [
    {
      "editId": "11111111-1111-4111-8111-111111111111",
      "callId": "33333333-3333-4333-8333-333333333333",
      "label": "Build west accent",
      "operation": "set_blocks",
      "world": "world",
      "worldId": "22222222-2222-4222-8222-222222222222",
      "bounds": {
        "min": { "x": 1, "y": 2, "z": 3 },
        "max": { "x": 5, "y": 2, "z": 3 }
      },
      "changedBlockCount": 1,
      "completedAt": "2026-08-19T12:34:56Z",
      "status": "committed"
    }
  ],
  "undoCallId": "44444444-4444-4444-8444-444444444444",
  "undoneAt": "2026-08-19T12:35:30Z"
}
```

Undo validates the complete array before restoration, then runs sequentially.
If execution stops, newer edits already restored remain consumed and the
HTTP bridge result has `outcome="partial"`, its `undoneEdits` contains that
successful prefix, and `failure.editId` identifies the retained failed edit.
MCP converts that partial result into `isError=true`. Read history again before
retrying.

## Commands

### `run_minecraft_commands`

Use this for a bounded ordered batch of registered Minecraft commands when a
Dirt block-edit tool is not appropriate.

Arguments:

```json
{
  "commands": ["say ready", "time set day"]
}
```

Structured result:

```json
{
  "sender": {
    "name": "FeedbackForwardingSender",
    "isOperator": true,
    "isPlayer": false
  },
  "feedbackTruncated": false,
  "results": [
    {
      "command": "say ready",
      "feedback": [],
      "outcome": "dispatched",
      "message": null,
      "rawMessage": null
    },
    {
      "command": "time set day",
      "feedback": ["Set the time to 1000"],
      "outcome": "dispatched",
      "message": null,
      "rawMessage": null
    }
  ]
}
```

Paper strips outer Java whitespace and one optional leading slash. Commands run
once, in order, through an operator-level sender that is not a player or the
literal console. A `dispatched` outcome means Paper invoked a target, not that
the command reported semantic success.

The first `not_found` or `dispatch_failed` result is included and stops the
batch; later commands are absent. That outcome sets the MCP result's
`isError=true` while preserving this command-specific `structuredContent`
instead of replacing it with the common Dirt error envelope. Command effects
are non-atomic, may outlive dispatch, and are outside Dirt history and undo.
Inspect state before retrying after an ambiguous timeout or disconnect.

## Failures

A Dirt execution failure sets `isError=true`. Its text content is only a
summary; use the structured error. Correctable failures include code-specific
`details`. Internal failures deliberately do not. Only mutation failures can
include `editId`; it means edit history may need reconciliation before retrying.

```json
{
  "isError": true,
  "content": [
    {
      "type": "text",
      "text": "Could not replace region blocks: Too many changes"
    }
  ],
  "structuredContent": {
    "callId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "error": {
      "code": "change_limit_exceeded",
      "message": "Too many changes",
      "details": { "maximum": 100000 }
    }
  },
  "resultType": "complete",
  "_meta": {
    "io.modelcontextprotocol/serverInfo": {
      "name": "dirt-mcp",
      "version": "0.1.0"
    }
  }
}
```

An `undo_edits` runtime failure additionally reports the successfully restored
prefix. This example means the first edit was consumed, the second remains
retained, and older requested edits were not attempted:

```json
{
  "callId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "world": "world",
  "error": {
    "code": "world_unavailable",
    "message": "Undo stopped",
    "details": { "reason": "operation_failed" },
    "editId": "22222222-2222-4222-8222-222222222222"
  },
  "undoneEdits": [
    {
      "editId": "11111111-1111-4111-8111-111111111111",
      "callId": "44444444-4444-4444-8444-444444444444",
      "label": "Build west accent",
      "operation": "set_blocks",
      "world": "world",
      "worldId": "55555555-5555-4555-8555-555555555555",
      "bounds": {
        "min": { "x": 0, "y": 0, "z": 0 },
        "max": { "x": 0, "y": 0, "z": 0 }
      },
      "changedBlockCount": 7,
      "completedAt": "2026-08-19T12:34:56Z",
      "status": "committed"
    }
  ]
}
```

Input-schema failures happen before Dirt calls Paper. The MCP SDK returns
`isError=true` with validation text and may omit `structuredContent`; correct
the arguments instead of treating that as a Dirt runtime failure.
