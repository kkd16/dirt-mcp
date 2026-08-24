package ca.deliyannides.dirtmcp.paper.bridge;

/** Operations that may be admitted by the authenticated Paper bridge. */
public enum BridgeOperation {
    PING_SERVER("pingServer"),
    GET_SERVER_STATUS("getServerStatus"),
    COUNT_REGION_BLOCK_STATES("countRegionBlockStates"),
    GET_BLOCKS("getBlocks"),
    SCAN_ORTHOGRAPHIC_VIEW("scanOrthographicView"),
    GET_PLAYER_CONTEXT("getPlayerContext"),
    GET_PERSPECTIVE_VIEW("getPerspectiveView"),
    REPLACE_REGION_BLOCKS("replaceRegionBlocks"),
    SET_BLOCKS("setBlocks"),
    GET_EDIT_HISTORY("getEditHistory"),
    UNDO_EDITS("undoEdits"),
    RUN_MINECRAFT_COMMANDS("runMinecraftCommands");

    private final String operationId;

    BridgeOperation(String operationId) {
        this.operationId = operationId;
    }

    public String operationId() {
        return this.operationId;
    }

    public static BridgeOperation parse(String value) {
        for (BridgeOperation operation : values()) {
            if (operation.operationId.equals(value)) {
                return operation;
            }
        }
        throw new IllegalArgumentException(
                "bridge.allowed-operations contains unknown operationId: " + value);
    }
}
