# MCP tool reference

Dirt MCP exposes synchronous tools for a live Paper server. Tool availability
comes from the Paper plugin's `tools` allowlist and is snapshotted when Paper and
the MCP process start. A disabled tool is absent from MCP discovery.

The schemas below describe each tool's `structuredContent`. They use compact
TypeScript notation: `?` marks an optional field, `|` marks a union, and comments
show defaults. All objects are strict and reject unknown fields. The TypeScript
Zod schemas are authoritative for the MCP surface; the
[OpenAPI contract](../protocol/openapi.yaml) is authoritative for the internal
HTTP bridge.

## Common conventions

- Coordinates are signed 32-bit integers. Region corners are inclusive and may
  be supplied in either order; returned bounds are normalized.
- World tools accept the exact name of an already-loaded Paper world.
  Inspection and normal edits do not load or generate chunks.
- A canonical block state is a namespaced string such as
  `minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]`.
- A block-state pattern may omit properties. For example,
  `minecraft:oak_stairs` matches every oak-stair state. Destination states must
  be exact states accepted by Paper.
- Configured request, region, chunk, result, change, command, concurrency, and
  history limits apply in addition to the schema constraints. Use
  `get_server_status` with `include.configuration=true` to read active values.
- Inspection and edit results fail rather than truncate when a hard result or
  operation limit would be exceeded.

### Shared types

```ts
type int32 = number; // integer from -2,147,483,648 to 2,147,483,647
type nonnegativeInt32 = number; // integer from 0 to 2,147,483,647
type positiveInt = number; // positive safe integer
type nonnegativeInt = number; // safe integer >= 0
type uuid = string; // canonical UUID
type uuidV4 = string; // canonical version 4 UUID
type timestamp = string; // ISO-8601 datetime with an offset

type BlockPosition = { x: int32; y: int32; z: int32 };
type ExactPosition = { x: number; y: number; z: number }; // finite values
type UnitVector = ExactPosition; // length 1 within numeric tolerance
type Bounds = { min: BlockPosition; max: BlockPosition };
type Dimensions = { x: positiveInt; y: positiveInt; z: positiveInt };

type PaletteEntry = {
  blockState: string;
  weight?: number; // integer 1-100
};
type DestinationPalette = PaletteEntry[]; // 1-64 distinct exact states

type EditOperation = 'replace_region_blocks' | 'set_blocks';
type EditLabel = string; // 1-120 Unicode code points; trimmed, single-line, and control-free

type EditRecord = {
  editId: uuidV4;
  callId: uuidV4;
  label: EditLabel;
  operation: EditOperation;
  world: string;
  worldId: uuid;
  bounds: Bounds;
  changedBlockCount: positiveInt;
  completedAt: timestamp;
  status: 'committed' | 'recovery_required';
};
```

Palette entries must be distinct. Omit every `weight` for equal probability, or
provide a whole-number weight for every entry with a total of 100. Palette
selection is probabilistic per coordinate, not an exact quota.

Every tool output is the documented success object or this common failure
envelope:

```ts
type ToolFailure = {
  callId: uuidV4;
  error: {
    code:
      | 'bridge_busy'
      | 'bridge_http_error'
      | 'bridge_invalid_response'
      | 'bridge_unauthorized'
      | 'bridge_unavailable'
      | 'change_limit_exceeded'
      | 'dirt_internal_error'
      | 'edit_not_found'
      | 'edit_not_latest'
      | 'history_capacity_exceeded'
      | 'internal_error'
      | 'invalid_request'
      | 'method_not_allowed'
      | 'not_found'
      | 'player_not_found'
      | 'player_unavailable'
      | 'region_too_large'
      | 'result_too_large'
      | 'server_unavailable'
      | 'unauthorized'
      | 'unhealthy'
      | 'world_busy'
      | 'world_not_found'
      | 'world_unavailable';
    message: string;
    details?: object;
    editId?: uuidV4;
  };
};

type UndoEditsRuntimeFailure = ToolFailure & {
  error: ToolFailure['error'] & { editId: uuidV4 };
  undoneEdits: EditRecord[]; // successfully consumed newest-first prefix; may be empty
};
```

Correctable failures have code-specific `details`; internal failures deliberately
do not. The OpenAPI contract defines every bridge detail variant. Local transport
failures use a reason or HTTP status in `details`. An `editId` means the caller
may need to reconcile the failure with `get_edit_history` before retrying.
Only a runtime `undo_edits` failure adds the required top-level `undoneEdits`
array; its `error.editId` identifies the retained edit whose undo failed.

## Status

### `ping_server`

Runs a read-only health check across the MCP server, authenticated bridge, Paper
plugin, and a Paper-backed FAWE session.

```ts
type Input = {};

type Success = {
  status: 'ok';
};
```

### `get_server_status`

Returns runtime builds and performance plus selected player, world, and active
configuration sections. Worlds default on; players and configuration default
off. Excluded sections are `null`.

```ts
type Input = {
  include?: {
    players?: boolean; // default false
    worlds?: boolean; // default true
    configuration?: boolean; // default false
  };
};

type Success = {
  builds: {
    minecraft: string;
    paper: string;
    dirtMcp: string;
    fawe: string;
  };
  performance: {
    tpsOneMinute: number;
    averageTickTimeMillis: number;
  };
  players: {
    online: nonnegativeInt;
    maximum: nonnegativeInt;
    entries: Array<{
      name: string;
      world: string;
      gameMode: 'survival' | 'creative' | 'adventure' | 'spectator';
      facing: 'north' | 'east' | 'south' | 'west';
      blockPosition: BlockPosition;
    }>;
  } | null;
  worlds: Array<{
    name: string;
    environment: string;
    minY: int32;
    maxY: int32;
    spawn: BlockPosition;
    timeOfDay: number; // integer from 0 to 23,999
    storm: boolean;
    thundering: boolean;
    playerCount: nonnegativeInt;
  }> | null;
  configuration: {
    limits: {
      maxRequestBytes: positiveInt;
      maxRegionVolume: positiveInt;
      maxTouchedChunks: positiveInt;
      maxInspectionTouchedChunks: positiveInt;
      maxBlockStatePatterns: positiveInt;
      maxChangedBlocks: positiveInt;
      maxInspectionVolume: positiveInt;
      defaultInspectionResultLimit: positiveInt;
      maxInspectionResultLimit: positiveInt;
      maxCommandsPerRequest: positiveInt;
      maxCommandFeedbackCharacters: positiveInt;
    };
    editHistory: {
      maxEntriesPerWorld: positiveInt;
      maxEntriesTotal: positiveInt;
      maxRetainedChangedBlocks: positiveInt;
    };
    defaults: {
      getBlocksIncludeAir: boolean;
      editDryRun: boolean;
    };
    logging: {
      consoleLevel: 'info' | 'warning' | 'error';
      detailFileMaxBytes: positiveInt;
      detailFileRetainedFiles: positiveInt; // 2-100
    };
    tools: Record<ToolName, boolean>;
  } | null;
};
```

`ToolName` is the set of tool headings in this document.

## Inspection

### `count_region_block_states`

Counts every canonical block state, including air, in an inclusive region. Use
this when totals are sufficient and exact positions are unnecessary.

```ts
type Input = {
  world: string;
  min: BlockPosition;
  max: BlockPosition;
};

type Success = {
  world: string;
  bounds: Bounds;
  dimensions: Dimensions;
  volume: positiveInt;
  blockStateCounts: Record<string, nonnegativeInt>;
};
```

### `get_blocks`

Returns a replay-ready exact structure using singleton palettes, individual
placements, and inclusive cuboids. Include patterns are applied first, followed
by exclude patterns. The two pattern lists may contain at most 64 entries
combined.

```ts
type Input = {
  world: string;
  min: BlockPosition;
  max: BlockPosition;
  includeBlockStatePatterns?: string[]; // default [], allowing all states
  excludeBlockStatePatterns?: string[]; // default []
  includeAir?: boolean; // plugin default when omitted
  maxResults?: positiveInt; // maximum placements plus runs; fails rather than truncates
};

type PalettePlacement = [paletteIndex: nonnegativeInt32, x: int32, y: int32, z: int32];
type PaletteRun = [paletteIndex: nonnegativeInt32, x: int32, y: int32, z: int32, toX: int32, toY: int32, toZ: int32];

type Success = {
  world: string;
  origin: BlockPosition;
  palettes: Array<[{ blockState: string }]>;
  placements: PalettePlacement[];
  runs: PaletteRun[];
};
```

Every tuple coordinate is a signed offset from `origin`; run endpoints are
component-wise forward and inclusive. Geometry never overlaps. Palettes are
ordered by first appearance and contain exactly one unweighted state because
inspection is exact. Blocks are scanned in Y/Z/X order and greedily packed
along +X, then +Z, then +Y; singletons remain placements. Both geometry arrays
are always present and may be empty.

Add the required `label` to the success object before using it as `set_blocks`
input. Changing `origin` as well copies the exact structure to another location.

### `scan_orthographic_view`

Scans away from an origin along world-axis sightlines. `depth=0` selects the
first non-air block on each line, `depth=1` the second, and so on; air gaps do
not increase depth. Scanning starts at distance one and excludes the origin.

Horizontal views use world-up vertically. Up and down views use east
horizontally and north vertically. Prefer `grid` for larger results.

```ts
type Direction = 'north' | 'east' | 'south' | 'west' | 'up' | 'down';
type AxisVector = { x: -1 | 0 | 1; y: -1 | 0 | 1; z: -1 | 0 | 1 };

type Input = {
  world: string;
  origin: BlockPosition;
  direction: Direction;
  horizontalRadius: nonnegativeInt;
  verticalRadius: nonnegativeInt;
  maxDistance: positiveInt;
  depth?: nonnegativeInt; // default 0
  maxResults?: positiveInt; // fails rather than truncates
  format?: 'blocks' | 'grid'; // default "blocks"
};

type ViewMetadata = {
  world: string;
  origin: BlockPosition;
  direction: Direction;
  basis: {
    forward: AxisVector;
    horizontal: AxisVector;
    vertical: AxisVector;
  };
  viewport: {
    horizontalRadius: nonnegativeInt;
    verticalRadius: nonnegativeInt;
    maxDistance: positiveInt;
    depth: nonnegativeInt;
  };
  bounds: Bounds;
  scannedVolume: positiveInt;
  visibleBlockCount: nonnegativeInt;
};

type Success =
  | (ViewMetadata & {
      format: 'blocks';
      blocks: Array<{
        position: BlockPosition;
        offset: {
          horizontal: int32;
          vertical: int32;
          distance: positiveInt;
        };
        blockState: string;
      }>;
    })
  | (ViewMetadata & {
      format: 'grid';
      blockStatePalette: string[];
      blockStateIndexRows: nonnegativeInt[][];
      distanceRows: nonnegativeInt[][];
    });
```

Grid palette indices start at one; zero in either aligned matrix means the
sightline was empty.

### `get_player_context`

Captures one online player by exact case-insensitive name or canonical UUID.
Identity, pose, and positioning are always returned. Optional sections come from
the same main-thread capture and are `null` when excluded.

```ts
type Input = {
  player: string; // exact case-insensitive online name or canonical UUID, at most 36 characters
  include?: {
    equipment?: boolean; // default true
    inventory?: boolean; // default false
    enderChest?: boolean; // default false
    vitals?: boolean; // default false
    movement?: boolean; // default false
    client?: boolean; // default false
    effects?: boolean; // default false
  };
};

type PlayerItem = {
  type: string;
  amount: positiveInt;
  maxStackSize: positiveInt;
  damage: nonnegativeInt | null;
  maxDamage: positiveInt | null;
  unbreakable: boolean;
  enchantments: Array<{ type: string; level: int32 }>;
};

type Inventory = {
  size: positiveInt;
  slots: Array<{ slot: nonnegativeInt; item: PlayerItem }>;
};

type Success = {
  capturedAt: timestamp;
  player: { name: string; uuid: uuid };
  world: string;
  worldId: uuid;
  gameMode: 'survival' | 'creative' | 'adventure' | 'spectator';
  feetPosition: ExactPosition;
  blockPosition: BlockPosition;
  eyePosition: ExactPosition;
  rotation: { yaw: number; pitch: number };
  lookDirection: UnitVector;
  pose:
    | 'standing'
    | 'fall_flying'
    | 'sleeping'
    | 'swimming'
    | 'spin_attack'
    | 'sneaking'
    | 'long_jumping'
    | 'dying'
    | 'croaking'
    | 'using_tongue'
    | 'sitting'
    | 'roaring'
    | 'sniffing'
    | 'emerging'
    | 'digging'
    | 'sliding'
    | 'shooting'
    | 'inhaling';
  onGround: boolean;
  equipment: {
    selectedHotbarSlot: number; // integer 0-8
    mainHand: PlayerItem | null;
    offHand: PlayerItem | null;
    helmet: PlayerItem | null;
    chestplate: PlayerItem | null;
    leggings: PlayerItem | null;
    boots: PlayerItem | null;
  } | null;
  inventory: Inventory | null;
  enderChest: Inventory | null;
  vitals: {
    health: number;
    maxHealth: number;
    absorptionAmount: number;
    foodLevel: int32;
    saturation: number;
    exhaustion: number;
    remainingAir: int32;
    maximumAir: int32;
    experienceLevel: nonnegativeInt;
    experienceProgress: number; // 0-1
    calculatedExperiencePoints: int32;
    fireTicks: int32;
    freezeTicks: nonnegativeInt;
  } | null;
  movement: {
    velocity: ExactPosition;
    fallDistance: number;
    allowFlight: boolean;
    flying: boolean;
    sneaking: boolean;
    sprinting: boolean;
    swimming: boolean;
    gliding: boolean;
    sleeping: boolean;
    blocking: boolean;
    riptiding: boolean;
  } | null;
  client: {
    pingMillis: int32;
    locale: string;
    clientViewDistance: nonnegativeInt;
    viewDistance: int32;
    sendViewDistance: int32;
  } | null;
  effects: Array<{
    type: string;
    amplifier: int32;
    durationTicks: int32;
    ambient: boolean;
    particles: boolean;
    icon: boolean;
  }> | null;
};
```

### `get_perspective_view`

Traces an odd-sized grid of first Paper block-collision hits from either an
online player's current eye pose or a synthetic camera. Synthetic coordinates
are the exact camera and ray origin; no player eye-height offset is added.

```ts
type Input = {
  source:
    | {
        type: 'player';
        player: string; // exact case-insensitive online name or canonical UUID
      }
    | {
        type: 'location';
        world: string; // exact loaded-world name
        cameraPosition: ExactPosition;
        rotation: {
          yaw: number; // any finite angle; resolved output is normalized to [-180, 180)
          pitch: number; // -90 through 90
        };
      };
  width?: number; // odd integer 1-255, default 21
  height?: number; // odd integer 1-255, default 13
  verticalFieldOfViewDegrees?: number; // integer 1-170, default 70
  maxDistance?: number; // integer 1-128, default 32
  fluidCollision?: 'never' | 'source_only' | 'always'; // default "never"
  ignorePassableBlocks?: boolean; // default false
};

type Success = {
  capturedAt: timestamp;
  source: { type: 'player'; player: { name: string; uuid: uuid } } | { type: 'location' };
  world: string;
  worldId: uuid;
  cameraPosition: ExactPosition;
  rotation: { yaw: number; pitch: number };
  lookDirection: UnitVector;
  basis: {
    forward: UnitVector;
    right: UnitVector;
    up: UnitVector;
  };
  viewport: {
    width: number;
    height: number;
    verticalFieldOfViewDegrees: number;
    horizontalFieldOfViewDegrees: number;
    maxDistance: number;
    fluidCollision: 'never' | 'source_only' | 'always';
    ignorePassableBlocks: boolean;
  };
  checkedChunkCount: nonnegativeInt;
  blockStatePalette: string[];
  hits: Array<{
    row: nonnegativeInt;
    column: nonnegativeInt;
    blockStateIndex: positiveInt; // one-based palette index
    blockPosition: BlockPosition;
    hitPosition: ExactPosition;
    face: 'up' | 'down' | 'north' | 'east' | 'south' | 'west' | null;
    distance: number;
  }>;
  crosshairHitIndex: nonnegativeInt | null;
};
```

Hits are sparse and row-major. `crosshairHitIndex` addresses the `hits` array
and is `null` when the center ray misses. The tool preflights loaded chunks and
never loads terrain. A player source fails while that player is spectating
another entity. Results describe server collision geometry, not entities,
lighting, particles, resource packs, third-person state, or a client framebuffer.

## Editing and undo

Both block-edit tools accept an optional signed 32-bit `seed`. Omission
generates a seed returned in the result. Reusing it with the same ordered
palettes and unchanged world reproduces per-coordinate choices, allowing an
exact preview to be replayed.

Every edit request requires a concise `label` describing one reversible intent.
Labels are retained on committed and recovery records so history remains useful
to the model; previews and no-ops create no record. Use one `set_blocks` call for
related placements and runs, and separate unrelated refinements into separately
labeled edits.

An optional `maxChangedBlocks` sets a request-specific positive int32 ceiling.
Dirt enforces the lower of this value and the configured maximum during exact
preflight and in FAWE execution. A request whose preflight count exceeds the
ceiling fails before mutation; changes after preflight remain protected by
FAWE's limit and normal rollback or recovery handling.

`dryRun=true` returns `outcome="preview"` without mutation. An executed no-op
returns `outcome="no_change"`; a positive completed edit returns
`outcome="committed"` and an `EditRecord`. Committed success is returned only
after FAWE undo data has been retained.

### `replace_region_blocks`

Replaces blocks matching the union of one or more source patterns throughout an
inclusive region.

```ts
type Input = {
  world: string;
  label: EditLabel;
  min: BlockPosition;
  max: BlockPosition;
  sourceBlockStatePatterns: string[]; // 1-64 distinct patterns
  destinationPalette: DestinationPalette;
  seed?: int32;
  dryRun?: boolean; // plugin default when omitted
  maxChangedBlocks?: positiveInt; // at most 2,147,483,647
};

type Success = {
  world: string;
  bounds: Bounds;
  sourceBlockStatePatterns: string[];
  destinationPalette: DestinationPalette;
  seed: int32;
  outcome: 'preview' | 'no_change' | 'committed';
  edit: EditRecord | null;
  matchedBlockCount: nonnegativeInt;
  changedBlockCount: nonnegativeInt;
};
```

### `set_blocks`

Places blocks from palettes using origin-relative singleton placements and
inclusive cuboids as one edit. Palette indices are zero-based. Every coordinate
in both tuple forms is a signed offset added to `origin`.

```ts
type Input = {
  world: string;
  label: EditLabel;
  origin: BlockPosition;
  palettes: DestinationPalette[]; // at most 64 entries total
  placements: PalettePlacement[];
  runs: PaletteRun[];
  seed?: int32;
  dryRun?: boolean; // plugin default when omitted
  maxChangedBlocks?: positiveInt; // at most 2,147,483,647
};

type Success = {
  world: string;
  bounds: Bounds | null;
  palettes: DestinationPalette[];
  seed: int32;
  outcome: 'preview' | 'no_change' | 'committed';
  edit: EditRecord | null;
  blockCount: nonnegativeInt;
  changedBlockCount: nonnegativeInt;
  unchangedBlockCount: nonnegativeInt;
};
```

Every run uses component-wise forward inclusive corners. Every palette index
must exist, resolved positions must fit signed 32-bit coordinates, and no block
represented by a placement or run may overlap another. Limits apply to the
expanded block count. Palette selection, including weighted selection, happens
independently at every represented coordinate using the returned seed.

When both geometry arrays are empty, `palettes` must also be empty. This is a
valid no-op returning null bounds, zero counts, `outcome: 'no_change'`, and no
edit record. Non-empty geometry requires at least one palette. Placement does
not request Minecraft neighbor physics.

### `get_edit_history`

Returns every currently retained and undoable Dirt edit in one loaded world,
newest first. Dry runs, no-ops, consumed edits, evicted edits, command effects,
and edits made outside Dirt are absent.

```ts
type Input = {
  world: string;
};

type Success = {
  world: string;
  edits: EditRecord[];
};
```

### `undo_edits`

Restores and consumes one or more retained edits. `editIds` must be a non-empty,
case-insensitively distinct array equal to the exact newest-first prefix of
current history, with at most the configured per-world history capacity. Dirt
validates the complete array before restoration, so a missing, reordered,
skipped, or intervening edit fails without changing the world.

```ts
type Input = {
  world: string;
  editIds: uuidV4[];
};

type Success = {
  world: string;
  edits: EditRecord[]; // requested newest-first order
  undoCallId: uuidV4;
  undoneAt: timestamp;
};
```

Execution is sequential and is not all-or-nothing. If an edit fails at runtime,
newer edits already restored by the call remain consumed, `undoneEdits` returns
that successful prefix (including an empty array when the first edit fails),
and `error.editId` identifies the failed current record. The failed record stays
retained for recovery and older requested edits are not attempted. Re-read
`get_edit_history` before retrying.

History is a flat, volatile undo stack. Undo does not create redo entries, and
Dirt provides no grouping, branching, or persistent history.

## Commands

### `run_minecraft_commands`

Validates and attempts a non-empty ordered command batch once through an
operator-level sender that is not a player or the literal console. Dirt removes
outer Java whitespace and at most one leading slash. ISO control characters and
commands empty after normalization are rejected.

```ts
type Input = {
  commands: string[]; // non-empty; active limits apply
};

type CommandResult =
  | {
      command: string;
      feedback: string[];
      outcome: 'dispatched';
      message: null;
      rawMessage: null;
    }
  | {
      command: string;
      feedback: string[];
      outcome: 'not_found';
      message: string;
      rawMessage: null;
    }
  | {
      command: string;
      feedback: string[];
      outcome: 'dispatch_failed';
      message: string;
      rawMessage: string;
    };

type Success = {
  sender: {
    name: string;
    isOperator: true;
    isPlayer: false;
  };
  feedbackTruncated: boolean;
  results: CommandResult[];
};
```

Results contain the attempted prefix. Execution stops after the first
`not_found` or `dispatch_failed` result, which is included as the final entry;
later commands are not attempted. A `dispatched` outcome means Paper found and
invoked a target, not that the command reported semantic success.

Command effects are non-atomic, are not retained in Dirt history, and may
outlive synchronous dispatch. Player-only commands, `@s`, relative positions,
and plugins requiring a concrete console sender can behave differently. Inspect
server state before retrying after a timeout, disconnect, or unexpected internal
failure.
