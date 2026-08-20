# Vision

Dirt MCP gives local AI agents controlled access to a live Minecraft world. It
is designed for technical server owners and builders who want to inspect and
make large, deterministic changes without reducing world editing to thousands
of individual block calls.

The core workflow is:

```text
inspect -> describe -> preview -> execute -> verify -> get history -> undo newest by ID
```

## Product principles

- **Live-world ownership:** Paper remains the sole owner of loaded worlds and
  persistence. Dirt MCP never edits region files underneath a running server.
- **Semantic tools:** tools batch bounded regions or palette-based placements
  instead of requiring one model call per block.
- **Deterministic execution:** the model chooses an operation; Paper and FAWE
  execute it predictably.
- **Local by design:** the MCP process and Paper bridge run on the same machine.
  Remote and cloud transports are outside v1.
- **Simple control:** edits may execute immediately. The same tools support a
  dry-run when the caller wants an estimate first.
- **Current platform:** Dirt MCP supports the latest stable Paper release only.
  Older Minecraft versions are not a compatibility target.

## Version 1

V1 provides these world capabilities:

- inspect bounded regions as summaries, exact geometry, or sparse orthographic
  views;
- inspect an online player's exact position, selected state, and sampled
  server-authoritative perspective block view;
- replace matching blocks;
- fill a bounded region;
- set weighted-palette block states at origin-relative offsets as one edit;
- receive identity and bounds metadata for every committed non-empty edit;
- inspect the retained, undoable edit history for a loaded world;
- undo the identified newest retained edit.

[FastAsyncWorldEdit (FAWE)](https://github.com/IntellectualSites/FastAsyncWorldEdit)
is a required server dependency. Dirt MCP validates inputs and enforces
configurable region and changed-block limits. Server access and world backups
remain the operator's responsibility.

V1 edit history is deliberately bounded and in memory. Positive per-world,
global-entry, and aggregate changed-block limits keep every listed record backed
by live undo data. History survives chunk unloads but not world unloads or Paper
restarts; it is not a substitute for backups.

## Outside v1

- schematics and structure generation;
- terrain, biome, road, or vegetation tools;
- image rendering or visual critique;
- player control, Mineflayer, or autonomous embodied clients;
- durable jobs or retained edit history across restarts;
- databases, web interfaces, Docker orchestration, and remote MCP hosting; and
- support for multiple Paper versions.
