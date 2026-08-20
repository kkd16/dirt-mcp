package ca.deliyannides.dirtmcp.paper.world.model;

import java.util.Objects;

public record Cuboid(
        BlockPosition min, BlockPosition max, BlockDimensions dimensions, long volume) {
    public Cuboid {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(dimensions, "dimensions");
        if (volume < 1) {
            throw new IllegalArgumentException("Cuboid volume must be positive");
        }
    }

    public BlockBounds bounds() {
        return new BlockBounds(this.min, this.max);
    }
}
