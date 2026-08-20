package ca.deliyannides.dirtmcp.paper.world.edit;

public enum EditOutcome {
    PREVIEW("preview"),
    NO_CHANGE("no_change"),
    COMMITTED("committed");

    private final String wireName;

    EditOutcome(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return this.wireName;
    }
}
