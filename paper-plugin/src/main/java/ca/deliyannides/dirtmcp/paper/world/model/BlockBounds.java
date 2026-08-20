package ca.deliyannides.dirtmcp.paper.world.model;

import java.util.Objects;

public record BlockBounds(BlockPosition min, BlockPosition max) {
    public BlockBounds {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
    }
}
