package ca.deliyannides.dirtmcp.paper.world.edit;

public enum EditStatus {
    COMMITTED("committed"),
    RECOVERY_REQUIRED("recovery_required");

    private final String wireName;

    EditStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return this.wireName;
    }
}
