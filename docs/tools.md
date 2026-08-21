# MCP tool reference

Dirt MCP exposes 12 synchronous tools for a live Paper server. Tool availability
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

type EditOperation = 'replace_region_blocks' | 'fill_region' | 'set_blocks';

type EditRecord = {
  editId: uuidV4;
  callId: uuidV4;
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
```

Correctable failures have code-specific `details`; internal failures deliberately
do not. The OpenAPI contract defines every bridge detail variant. Local transport
failures use a reason or HTTP status in `details`. An `editId` means the caller
may need to reconcile the failure with `get_edit_history` before retrying.

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
      regionBlocksIncludeAir: boolean;
      regionBlocksFormat: 'blocks' | 'runs';
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

`ToolName` is the set of the 12 headings in this document.

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

### `get_region_blocks`

Returns filtered exact blocks or a deterministic lossless cover of axis-aligned
runs. Include patterns are applied first, followed by exclude patterns. The two
pattern lists may contain at most 64 entries combined.

```ts
type Input = {
  world: string;
  min: BlockPosition;
  max: BlockPosition;
  includeBlockStatePatterns?: string[]; // default [], allowing all states
  excludeBlockStatePatterns?: string[]; // default []
  includeAir?: boolean; // plugin default when omitted
  maxResults?: positiveInt; // fails rather than truncates
  format?: 'blocks' | 'runs'; // plugin default when omitted
};

type Success =
  | {
      world: string;
      bounds: Bounds;
      volume: positiveInt;
      matchedBlockCount: nonnegativeInt;
      format: 'blocks';
      blocks: Array<{
        position: BlockPosition;
        blockState: string;
      }>;
    }
  | {
      world: string;
      bounds: Bounds;
      volume: positiveInt;
      matchedBlockCount: nonnegativeInt;
      format: 'runs';
      runs: Array<{
        blockState: string;
        from: BlockPosition;
        to: BlockPosition;
      }>;
    };
```

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

The optional view is an odd-sized perspective ray grid sampled from the captured
eye pose using Paper collision shapes. It reports first block hits, not a client
framebuffer, and cannot observe entities or client-only presentation state.

```ts
type Input = {
  player: string; // exact online name or canonical UUID, at most 36 characters
  include?: {
    view?: boolean; // default true
    equipment?: boolean; // default true
    inventory?: boolean; // default false
    enderChest?: boolean; // default false
    vitals?: boolean; // default false
    movement?: boolean; // default false
    client?: boolean; // default false
    effects?: boolean; // default false
  };
  view?: {
    width?: number; // odd integer 1-255, default 21
    height?: number; // odd integer 1-255, default 13
    verticalFieldOfViewDegrees?: number; // integer 1-170, default 70
    maxDistance?: number; // integer 1-128, default 32
    fluidCollision?: 'never' | 'source_only' | 'always'; // default "never"
    ignorePassableBlocks?: boolean; // default false
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

type PlayerView = {
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
    blockStateIndex: positiveInt;
    blockPosition: BlockPosition;
    hitPosition: ExactPosition;
    face: 'up' | 'down' | 'north' | 'east' | 'south' | 'west' | null;
    distance: number;
  }>;
  crosshairHitIndex: nonnegativeInt | null;
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
  view: PlayerView | null;
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

`view` must be omitted when `include.view=false`. A spectating player whose
camera is attached to another entity can be captured only with the view
disabled. Hits are sparse and row-major; `blockStateIndex` is one-based and
`crosshairHitIndex` addresses the `hits` array.

## Editing and undo

All three block-edit tools accept an optional signed 32-bit `seed`. Omission
generates a seed returned in the result. Reusing it with the same ordered
palettes and unchanged world reproduces per-coordinate choices, allowing an
exact preview to be replayed.

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
  min: BlockPosition;
  max: BlockPosition;
  sourceBlockStatePatterns: string[]; // 1-64 distinct patterns
  destinationPalette: DestinationPalette;
  seed?: int32;
  dryRun?: boolean; // plugin default when omitted
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

### `fill_region`

Fills every block in an inclusive region from one exact-state destination
palette.

```ts
type Input = {
  world: string;
  min: BlockPosition;
  max: BlockPosition;
  destinationPalette: DestinationPalette;
  seed?: int32;
  dryRun?: boolean; // plugin default when omitted
};

type Success = {
  world: string;
  bounds: Bounds;
  destinationPalette: DestinationPalette;
  seed: int32;
  outcome: 'preview' | 'no_change' | 'committed';
  edit: EditRecord | null;
  volume: positiveInt;
  changedBlockCount: nonnegativeInt;
};
```

### `set_blocks`

Places blocks from one or more palettes at distinct origin-relative positions as
one edit. Each placement is `[paletteIndex, x, y, z]`; `paletteIndex` is
zero-based and the coordinates are signed offsets added to `origin`.

```ts
type Input = {
  world: string;
  origin: BlockPosition;
  palettes: DestinationPalette[]; // 1-64 palettes, at most 64 entries total
  placements: Array<[int32, int32, int32, int32]>; // non-empty
  seed?: int32;
  dryRun?: boolean; // plugin default when omitted
};

type Success = {
  world: string;
  bounds: Bounds;
  palettes: DestinationPalette[];
  seed: int32;
  outcome: 'preview' | 'no_change' | 'committed';
  edit: EditRecord | null;
  blockCount: positiveInt;
  changedBlockCount: nonnegativeInt;
  unchangedBlockCount: nonnegativeInt;
};
```

Every palette index must exist, resolved positions must fit signed 32-bit
coordinates, and resolved positions must be distinct. The configured region
volume limits the placement count. Placement does not request Minecraft neighbor
physics.

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

### `undo_edit`

Restores and consumes the named retained edit. The ID must belong to the loaded
world and must be the newest retained edit, preventing an intervening edit from
being undone accidentally.

```ts
type Input = {
  world: string;
  editId: uuidV4;
};

type Success = {
  edit: EditRecord;
  undoCallId: uuidV4;
  undoneAt: timestamp;
};
```

A failed chunk load or undo leaves the record available for retry. A successful
undo returns the record as it existed immediately before consumption.

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
