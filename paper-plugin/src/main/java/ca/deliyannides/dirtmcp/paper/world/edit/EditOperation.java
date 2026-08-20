package ca.deliyannides.dirtmcp.paper.world.edit;

public enum EditOperation {
    REPLACE_REGION_BLOCKS("replace_region_blocks"),
    FILL_REGION("fill_region"),
    SET_BLOCKS("set_blocks");

    private final String wireName;

    EditOperation(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return this.wireName;
    }
}
