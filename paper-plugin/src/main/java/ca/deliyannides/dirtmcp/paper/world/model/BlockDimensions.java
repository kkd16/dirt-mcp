package ca.deliyannides.dirtmcp.paper.world.model;

public record BlockDimensions(long x, long y, long z) {
    public BlockDimensions {
        if (x < 1 || y < 1 || z < 1) {
            throw new IllegalArgumentException("Block dimensions must be positive");
        }
    }
}
