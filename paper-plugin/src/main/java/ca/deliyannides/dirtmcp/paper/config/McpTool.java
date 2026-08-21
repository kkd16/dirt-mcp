package ca.deliyannides.dirtmcp.paper.config;

/** Canonical MCP tool identifiers shared by configuration and runtime reporting. */
public enum McpTool {
    PING_SERVER("ping_server"),
    GET_SERVER_STATUS("get_server_status"),
    COUNT_REGION_BLOCK_STATES("count_region_block_states"),
    GET_BLOCKS("get_blocks"),
    SCAN_ORTHOGRAPHIC_VIEW("scan_orthographic_view"),
    GET_PLAYER_CONTEXT("get_player_context"),
    GET_PERSPECTIVE_VIEW("get_perspective_view"),
    REPLACE_REGION_BLOCKS("replace_region_blocks"),
    FILL_REGION("fill_region"),
    SET_BLOCKS("set_blocks"),
    GET_EDIT_HISTORY("get_edit_history"),
    UNDO_EDIT("undo_edit"),
    RUN_MINECRAFT_COMMANDS("run_minecraft_commands");

    private final String id;

    McpTool(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }
}
