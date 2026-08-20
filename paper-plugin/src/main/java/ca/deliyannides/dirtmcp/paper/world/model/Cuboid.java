package ca.deliyannides.dirtmcp.paper.world.model;

import java.util.Objects;

public record Cuboid(BlockPosition min, BlockPosition max) {
    public Cuboid {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Cuboid bounds must be normalized");
        }
        try {
            volume(min, max);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Cuboid volume exceeds signed 64-bit range", exception);
        }
    }

    public BlockDimensions dimensions() {
        return new BlockDimensions(
                (long) this.max.x() - this.min.x() + 1,
                (long) this.max.y() - this.min.y() + 1,
                (long) this.max.z() - this.min.z() + 1);
    }

    public long volume() {
        return volume(this.min, this.max);
    }

    public BlockBounds bounds() {
        return new BlockBounds(this.min, this.max);
    }

    private static long volume(BlockPosition min, BlockPosition max) {
        long sizeX = (long) max.x() - min.x() + 1;
        long sizeY = (long) max.y() - min.y() + 1;
        long sizeZ = (long) max.z() - min.z() + 1;
        return Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
    }
}
