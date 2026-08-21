package ca.deliyannides.dirtmcp.paper.command;

import ca.deliyannides.dirtmcp.paper.config.McpTool;

/** Concise operator-facing synopsis; the MCP schemas remain the exact wire contract. */
final class McpToolHelp {
    private McpToolHelp() {}

    static ToolSpec spec(McpTool tool) {
        return switch (tool) {
            case PING_SERVER ->
                    new ToolSpec(
                            "Ping Dirt server",
                            ToolKind.READ_ONLY,
                            "Checks the authenticated bridge, Dirt, Paper, and FAWE end to end.",
                            "None.",
                            "status = ok after every required service passes its health check.",
                            "The quickest non-mutating connectivity check.");
            case GET_SERVER_STATUS ->
                    new ToolSpec(
                            "Get Dirt server status",
                            ToolKind.READ_ONLY,
                            "Returns current server context used to ground later world operations.",
                            "Optional include.players (default false), include.worlds (true), and "
                                    + "include.configuration (false).",
                            "builds and performance; requested players, worlds, and configuration, "
                                    + "or null. Configuration groups limits, editHistory, defaults, "
                                    + "logging, and tools.",
                            "Read-only; excluded sections are null. Include configuration when "
                                    + "active limits or defaults are needed.");
            case GET_PLAYER_CONTEXT ->
                    new ToolSpec(
                            "Get player context",
                            ToolKind.READ_ONLY,
                            "Captures an online player's exact pose, items, vitals, movement, client state, and effects.",
                            "player (case-insensitive exact online name or UUID); optional include "
                                    + "flags (equipment defaults true; all others false).",
                            "One coherent player snapshot; requested sections are non-null and "
                                    + "excluded sections are null.",
                            "Read-only and point-in-time; use get_perspective_view for block-collision sightlines.");
            case GET_PERSPECTIVE_VIEW ->
                    new ToolSpec(
                            "Get perspective view",
                            ToolKind.READ_ONLY,
                            "Traces a bounded perspective grid from an online player's eye or an arbitrary camera location.",
                            "source = player with a case-insensitive exact online name or UUID, or "
                                    + "location with world, cameraPosition, and yaw/pitch rotation; "
                                    + "optional width, height, verticalFieldOfViewDegrees, maxDistance, "
                                    + "fluidCollision, and ignorePassableBlocks.",
                            "Resolved source, world, camera pose and basis, viewport, palette, sparse "
                                    + "first block-collision hits, and center-ray hit index.",
                            "Read-only; block collisions only, from already-loaded chunks. It is not "
                                    + "a client framebuffer, entity view, resource-pack rendering, or FOV setting.");
            case COUNT_REGION_BLOCK_STATES ->
                    new ToolSpec(
                            "Count region block states",
                            ToolKind.READ_ONLY,
                            "Builds a complete canonical block-state histogram for an inclusive cuboid.",
                            "world, min{x,y,z}, max{x,y,z}.",
                            "world, normalized bounds, dimensions, volume, and blockStateCounts.",
                            "Read-only; the histogram includes air and full block-state properties.");
            case GET_REGION_BLOCKS ->
                    new ToolSpec(
                            "Get region blocks",
                            ToolKind.READ_ONLY,
                            "Retrieves filtered exact block geometry from an inclusive cuboid.",
                            "world, min, max; optional includeBlockStatePatterns, "
                                    + "excludeBlockStatePatterns, includeAir, maxResults, and format "
                                    + "= blocks|runs. Pattern lists default empty; other omissions "
                                    + "use the active Paper defaults.",
                            "world, bounds, volume, matchedBlockCount, format, and either exact "
                                    + "blocks or lossless runs.",
                            "Read-only; active size limits fail the call rather than truncate results.");
            case SCAN_ORTHOGRAPHIC_VIEW ->
                    new ToolSpec(
                            "Scan an orthographic view",
                            ToolKind.READ_ONLY,
                            "Selects a non-air depth along bounded, axis-aligned sightlines.",
                            "world, origin, direction = north|east|south|west|up|down, "
                                    + "horizontalRadius, verticalRadius, maxDistance; optional depth, "
                                    + "maxResults, and format = blocks|grid. depth defaults to 0, "
                                    + "format to blocks, and maxResults to the active Paper default.",
                            "world, origin, direction, basis, viewport, bounds, scannedVolume, "
                                    + "visibleBlockCount, format, and either blocks or "
                                    + "blockStatePalette/blockStateIndexRows/distanceRows.",
                            "Read-only; depth is zero-based and the origin itself is not scanned. "
                                    + "Active result ceilings fail the call rather than truncate.");
            case REPLACE_REGION_BLOCKS ->
                    new ToolSpec(
                            "Replace region blocks",
                            ToolKind.WORLD_MUTATION,
                            "Replaces blocks matching source patterns throughout inclusive bounds.",
                            "world, min, max, sourceBlockStatePatterns, destinationPalette entries "
                                    + "{blockState,weight?}; optional seed and dryRun. Omit all "
                                    + "weights for equal choice or make them total 100. An omitted "
                                    + "seed is generated and returned; dryRun uses the Paper default.",
                            "world, bounds, sourceBlockStatePatterns, destinationPalette, seed, "
                                    + "outcome, edit, matchedBlockCount, and changedBlockCount.",
                            "Successful results use outcome preview, no_change, or committed; "
                                    + "committed non-empty edits enter undo history. A failed edit "
                                    + "can retain a recovery_required record.");
            case FILL_REGION ->
                    new ToolSpec(
                            "Fill a region",
                            ToolKind.WORLD_MUTATION,
                            "Fills inclusive bounds from a weighted palette of exact block states.",
                            "world, min, max, destinationPalette entries {blockState,weight?}; "
                                    + "optional seed and dryRun. Omit all weights for equal choice or "
                                    + "make them total 100. An omitted seed is generated and returned; "
                                    + "dryRun uses the Paper default.",
                            "world, bounds, destinationPalette, seed, outcome, edit, volume, and "
                                    + "changedBlockCount.",
                            "Successful results use outcome preview, no_change, or committed; "
                                    + "committed non-empty edits enter undo history. A failed edit "
                                    + "can retain a recovery_required record.");
            case SET_BLOCKS ->
                    new ToolSpec(
                            "Set blocks",
                            ToolKind.WORLD_MUTATION,
                            "Applies one edit at distinct origin-relative positions using palettes.",
                            "world, origin, palettes (arrays of {blockState,weight?}), placements = "
                                    + "[paletteIndex,xOffset,yOffset,zOffset]; optional seed and "
                                    + "dryRun. paletteIndex is zero-based; palette weights are all "
                                    + "omitted or total 100. An omitted seed is generated and returned; "
                                    + "dryRun uses the Paper default.",
                            "world, bounds, palettes, seed, outcome, edit, blockCount, "
                                    + "changedBlockCount, and unchangedBlockCount.",
                            "Validates every position first and does not trigger neighbor physics. "
                                    + "Successful results use outcome preview, no_change, or committed; "
                                    + "committed non-empty edits enter undo history, and a failed edit "
                                    + "can retain a recovery_required record.");
            case GET_EDIT_HISTORY ->
                    new ToolSpec(
                            "Get edit history",
                            ToolKind.READ_ONLY,
                            "Lists every currently retained and undoable Dirt edit newest first.",
                            "world.",
                            "world and edits, an ordered array of retained edit records.",
                            "Dry runs, no-ops, consumed edits, and evicted edits are not returned. "
                                    + "In-memory history clears on world unload or Paper restart.");
            case UNDO_EDIT ->
                    new ToolSpec(
                            "Undo an edit",
                            ToolKind.WORLD_MUTATION,
                            "Restores and consumes one identified retained Dirt edit.",
                            "world, editId (UUIDv4 of the newest retained edit).",
                            "edit (the consumed pre-undo EditRecord), undoCallId, and undoneAt.",
                            "Newest-only identity checking prevents an intervening edit from being "
                                    + "undone accidentally.");
            case RUN_MINECRAFT_COMMANDS ->
                    new ToolSpec(
                            "Run Minecraft commands",
                            ToolKind.SERVER_MUTATION,
                            "Runs an ordered batch of registered Minecraft commands with operator authority.",
                            "commands (one to the active per-request limit); each command may have "
                                    + "surrounding whitespace and one leading slash, which are removed.",
                            "sender, feedbackTruncated, and an ordered attempted-prefix result with "
                                    + "each normalized command, lower-case outcome, captured feedback, "
                                    + "and failure details when applicable.",
                            "Commands use a non-player sender and stop after the first not-found or "
                                    + "dispatch failure. Dispatch is synchronous, but command-defined "
                                    + "effects are non-atomic and may outlive the response; they remain "
                                    + "outside Dirt's FAWE edit locks and undo history.");
        };
    }

    enum ToolKind {
        READ_ONLY("Read-only"),
        SERVER_MUTATION("Server mutation"),
        WORLD_MUTATION("World mutation");

        private final String label;

        ToolKind(String label) {
            this.label = label;
        }

        String label() {
            return this.label;
        }
    }

    record ToolSpec(
            String title,
            ToolKind kind,
            String purpose,
            String arguments,
            String returns,
            String notes) {}
}
