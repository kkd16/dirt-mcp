package ca.deliyannides.dirtmcp.paper.world.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import org.junit.jupiter.api.Test;

final class RegionGeometryTest {
    @Test
    void normalizesInclusiveBounds() throws Exception {
        Cuboid region =
                RegionGeometry.normalize(
                        new BlockPosition(4, 12, 9), new BlockPosition(2, 10, 6), 1_000);

        assertEquals(new BlockPosition(2, 10, 6), region.min());
        assertEquals(new BlockPosition(4, 12, 9), region.max());
        assertEquals(new BlockDimensions(3, 3, 4), region.dimensions());
        assertEquals(36, region.volume());
        assertEquals(new BlockBounds(region.min(), region.max()), region.bounds());
    }

    @Test
    void rejectsOversizedRegionsWithoutOverflow() {
        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () ->
                                RegionGeometry.normalize(
                                        new BlockPosition(Integer.MIN_VALUE, 0, Integer.MIN_VALUE),
                                        new BlockPosition(Integer.MAX_VALUE, 0, Integer.MAX_VALUE),
                                        1_000_000));

        assertEquals(OperationFailure.REGION_TOO_LARGE, exception.failure());
    }

    @Test
    void countsChunksAcrossNegativeCoordinates() throws Exception {
        Cuboid region =
                RegionGeometry.normalize(
                        new BlockPosition(-17, 0, -1), new BlockPosition(16, 0, 16), 1_000);

        assertEquals(12, RegionGeometry.touchedChunks(region, 12));
    }

    @Test
    void rejectsHugeThinRegionsByTouchedChunkCount() throws Exception {
        Cuboid region =
                RegionGeometry.normalize(
                        new BlockPosition(Integer.MIN_VALUE, 0, 0),
                        new BlockPosition(Integer.MAX_VALUE, 0, 0),
                        Long.MAX_VALUE);

        OperationException exception =
                assertThrows(
                        OperationException.class, () -> RegionGeometry.touchedChunks(region, 256));

        assertEquals(OperationFailure.REGION_TOO_LARGE, exception.failure());
        assertEquals(
                "Operation touches more than the maximum of 256 chunks", exception.getMessage());
    }

    @Test
    void rejectsInvalidGeometryLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RegionGeometry.normalize(
                                new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0), 0));
    }
}
