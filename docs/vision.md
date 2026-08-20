# Vision

Dirt MCP gives local AI agents controlled access to a live Minecraft world. It
is designed for technical server owners and builders who want to inspect and
make large, deterministic changes without reducing world editing to thousands
of individual block calls.

The core workflow is:

```text
inspect region -> describe an edit -> optionally preview -> execute -> inspect -> undo
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
- replace matching blocks;
- fill a bounded region;
- set weighted-palette block states at origin-relative offsets as one edit;
- undo the most recent Dirt MCP edit in a world; and
- run an ordered batch of registered Minecraft commands with operator-level
  permissions through a non-player Paper sender.

[FastAsyncWorldEdit (FAWE)](https://github.com/IntellectualSites/FastAsyncWorldEdit)
is a required server dependency. Dirt MCP validates inputs and enforces
configurable region and changed-block limits. Server access and world backups
remain the operator's responsibility.

## Outside v1

- schematics and structure generation;
- terrain, biome, road, or vegetation tools;
- image rendering or visual critique;
- Mineflayer or an embodied player;
- durable jobs or undo history across restarts;
- databases, web interfaces, Docker orchestration, and remote MCP hosting; and
- support for multiple Paper versions.
