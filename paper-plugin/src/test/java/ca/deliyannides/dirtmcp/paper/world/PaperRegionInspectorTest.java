package ca.deliyannides.dirtmcp.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionRequest;
import org.junit.jupiter.api.Test;

final class PaperRegionInspectorTest {
    @Test
    void normalizesInclusiveBounds() throws Exception {
        InspectionRequest request = new InspectionRequest(
                "world", new BlockPosition(4, 12, 9), new BlockPosition(2, 10, 6));

        PaperRegionInspector.NormalizedRegion region = PaperRegionInspector.normalize(request, 1_000);

        assertEquals(new BlockPosition(2, 10, 6), region.min());
        assertEquals(new BlockPosition(4, 12, 9), region.max());
        assertEquals(new RegionInspector.Dimensions(3, 3, 4), region.dimensions());
        assertEquals(36, region.volume());
    }

    @Test
    void rejectsOversizedRegionsWithoutOverflow() {
        InspectionRequest request = new InspectionRequest(
                "world",
                new BlockPosition(Integer.MIN_VALUE, 0, Integer.MIN_VALUE),
                new BlockPosition(Integer.MAX_VALUE, 0, Integer.MAX_VALUE));

        InspectionException exception = assertThrows(
                InspectionException.class, () -> PaperRegionInspector.normalize(request, 1_000_000));

        assertEquals(Failure.REGION_TOO_LARGE, exception.failure());
    }
}
