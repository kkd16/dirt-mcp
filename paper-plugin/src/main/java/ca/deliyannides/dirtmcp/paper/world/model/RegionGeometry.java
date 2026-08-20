package ca.deliyannides.dirtmcp.paper.world.model;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.util.Objects;

public final class RegionGeometry {
    private RegionGeometry() {}

    public static Cuboid normalize(BlockPosition first, BlockPosition second, int maxVolume)
            throws OperationException {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (maxVolume < 1) {
            throw new IllegalArgumentException("Maximum region volume must be positive");
        }

        BlockPosition min =
                new BlockPosition(
                        Math.min(first.x(), second.x()),
                        Math.min(first.y(), second.y()),
                        Math.min(first.z(), second.z()));
        BlockPosition max =
                new BlockPosition(
                        Math.max(first.x(), second.x()),
                        Math.max(first.y(), second.y()),
                        Math.max(first.z(), second.z()));

        long sizeX = (long) max.x() - min.x() + 1;
        long sizeY = (long) max.y() - min.y() + 1;
        long sizeZ = (long) max.z() - min.z() + 1;
        if (sizeX > maxVolume || sizeY > maxVolume / sizeX || sizeZ > maxVolume / (sizeX * sizeY)) {
            throw new OperationException(
                    OperationFailure.REGION_TOO_LARGE,
                    "Region exceeds the maximum volume of " + maxVolume + " blocks",
                    new ErrorDetails.RegionTooLarge.Volume(
                            new ErrorDetails.Dimensions(sizeX, sizeY, sizeZ), maxVolume));
        }

        return new Cuboid(min, max);
    }

    public static long touchedChunks(Cuboid region, int maximum) throws OperationException {
        long minChunkX = region.min().x() >> 4;
        long maxChunkX = region.max().x() >> 4;
        long minChunkZ = region.min().z() >> 4;
        long maxChunkZ = region.max().z() >> 4;
        long width = maxChunkX - minChunkX + 1;
        long depth = maxChunkZ - minChunkZ + 1;
        if (width > maximum || depth > maximum / width) {
            throw tooManyChunks(maximum);
        }
        long count = width * depth;
        if (count > maximum) {
            throw tooManyChunks(maximum);
        }
        return count;
    }

    private static OperationException tooManyChunks(int maximum) {
        return new OperationException(
                OperationFailure.REGION_TOO_LARGE,
                "Operation touches more than the maximum of " + maximum + " chunks",
                new ErrorDetails.RegionTooLarge.TouchedChunks((long) maximum + 1, maximum));
    }
}
