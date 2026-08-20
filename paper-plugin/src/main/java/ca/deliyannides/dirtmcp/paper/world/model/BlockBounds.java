package ca.deliyannides.dirtmcp.paper.world.model;

import java.util.Objects;

public record BlockBounds(BlockPosition min, BlockPosition max) {
    public BlockBounds {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("min must not exceed max on any axis");
        }
    }
}
