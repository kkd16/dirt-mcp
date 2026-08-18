package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Dimensions;

final class RegionGeometry {
    private RegionGeometry() {}

    static NormalizedRegion normalize(
            BlockPosition first, BlockPosition second, long maxVolume) throws RegionTooLargeException {
        BlockPosition min = new BlockPosition(
                Math.min(first.x(), second.x()),
                Math.min(first.y(), second.y()),
                Math.min(first.z(), second.z()));
        BlockPosition max = new BlockPosition(
                Math.max(first.x(), second.x()),
                Math.max(first.y(), second.y()),
                Math.max(first.z(), second.z()));

        long sizeX = (long) max.x() - min.x() + 1;
        long sizeY = (long) max.y() - min.y() + 1;
        long sizeZ = (long) max.z() - min.z() + 1;
        if (sizeX > maxVolume
                || sizeY > maxVolume / sizeX
                || sizeZ > maxVolume / (sizeX * sizeY)) {
            throw new RegionTooLargeException(maxVolume);
        }

        return new NormalizedRegion(
                min, max, new Dimensions(sizeX, sizeY, sizeZ), sizeX * sizeY * sizeZ);
    }

    record NormalizedRegion(BlockPosition min, BlockPosition max, Dimensions dimensions, long volume) {}

    static final class RegionTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;

        private RegionTooLargeException(long maximum) {
            super("Region exceeds the maximum volume of " + maximum + " blocks");
        }
    }
}
