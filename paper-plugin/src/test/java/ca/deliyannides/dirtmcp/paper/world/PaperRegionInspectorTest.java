package ca.deliyannides.dirtmcp.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockRun;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ExactInspectionMode;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ExactInspectionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.NormalizedRegion;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PaperRegionInspectorTest {
    @Test
    void normalizesInclusiveBounds() throws Exception {
        InspectionRequest request = new InspectionRequest(
                "world", new BlockPosition(4, 12, 9), new BlockPosition(2, 10, 6));

        NormalizedRegion region = PaperRegionInspector.normalize(request, 1_000);

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

    @Test
    void appliesTheSmallerExactInspectionVolumeLimit() {
        ExactInspectionRequest request = new ExactInspectionRequest(
                "world",
                new BlockPosition(0, 0, 0),
                new BlockPosition(RegionInspector.MAX_EXACT_VOLUME, 0, 0),
                List.of(),
                List.of(),
                false,
                RegionInspector.MAX_EXACT_RESULTS,
                ExactInspectionMode.BLOCKS);

        InspectionException exception = assertThrows(
                InspectionException.class,
                () -> PaperRegionInspector.normalizeExact(request, 1_000_000));

        assertEquals(Failure.REGION_TOO_LARGE, exception.failure());
    }

    @Test
    void groupsIdenticalBlocksAlongTheirLongestAxis() throws Exception {
        List<InspectedBlock> blocks = List.of(
                new InspectedBlock(new BlockPosition(0, 0, 0), "minecraft:stone"),
                new InspectedBlock(new BlockPosition(1, 0, 0), "minecraft:glass"),
                new InspectedBlock(new BlockPosition(2, 0, 0), "minecraft:glass"),
                new InspectedBlock(new BlockPosition(0, 1, 0), "minecraft:stone"),
                new InspectedBlock(new BlockPosition(0, 2, 0), "minecraft:stone"));

        List<BlockRun> runs = PaperRegionInspector.groupSortedRuns(blocks, 2);

        assertEquals(List.of(
                new BlockRun(
                        "minecraft:stone",
                        new BlockPosition(0, 0, 0),
                        new BlockPosition(0, 2, 0)),
                new BlockRun(
                        "minecraft:glass",
                        new BlockPosition(1, 0, 0),
                        new BlockPosition(2, 0, 0))), runs);
    }

    @Test
    void rejectsRunResultsOverTheRequestedCap() {
        List<InspectedBlock> blocks = List.of(
                new InspectedBlock(new BlockPosition(0, 0, 0), "minecraft:stone"),
                new InspectedBlock(new BlockPosition(2, 0, 0), "minecraft:stone"));

        InspectionException exception = assertThrows(
                InspectionException.class,
                () -> PaperRegionInspector.groupSortedRuns(blocks, 1));

        assertEquals(Failure.RESULT_TOO_LARGE, exception.failure());
    }
}
