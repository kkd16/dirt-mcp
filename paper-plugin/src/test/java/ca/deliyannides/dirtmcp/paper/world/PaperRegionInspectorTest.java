package ca.deliyannides.dirtmcp.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.NormalizedRegion;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.AxisVector;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockRun;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockStateCountRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.OrthographicViewDirection;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.OrthographicViewRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RegionBlocksFormat;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RegionBlocksRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewBasis;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewBlock;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PaperRegionInspectorTest {
    @Test
    void normalizesInclusiveBounds() throws Exception {
        BlockStateCountRequest request =
                new BlockStateCountRequest(
                        "world", new BlockPosition(4, 12, 9), new BlockPosition(2, 10, 6));

        NormalizedRegion region = PaperRegionInspector.normalize(request, 1_000);

        assertEquals(new BlockPosition(2, 10, 6), region.min());
        assertEquals(new BlockPosition(4, 12, 9), region.max());
        assertEquals(new RegionInspector.Dimensions(3, 3, 4), region.dimensions());
        assertEquals(36, region.volume());
    }

    @Test
    void rejectsOversizedRegionsWithoutOverflow() {
        BlockStateCountRequest request =
                new BlockStateCountRequest(
                        "world",
                        new BlockPosition(Integer.MIN_VALUE, 0, Integer.MIN_VALUE),
                        new BlockPosition(Integer.MAX_VALUE, 0, Integer.MAX_VALUE));

        InspectionException exception =
                assertThrows(
                        InspectionException.class,
                        () -> PaperRegionInspector.normalize(request, 1_000_000));

        assertEquals(Failure.REGION_TOO_LARGE, exception.failure());
    }

    @Test
    void appliesTheSmallerRegionBlocksVolumeLimit() {
        RegionBlocksRequest request =
                new RegionBlocksRequest(
                        "world",
                        new BlockPosition(0, 0, 0),
                        new BlockPosition(32_768, 0, 0),
                        List.of(),
                        List.of(),
                        false,
                        10_000,
                        RegionBlocksFormat.BLOCKS);

        InspectionException exception =
                assertThrows(
                        InspectionException.class,
                        () ->
                                PaperRegionInspector.normalizeRegionBlocks(
                                        request, 1_000_000, 32_768));

        assertEquals(Failure.REGION_TOO_LARGE, exception.failure());
    }

    @Test
    void groupsIdenticalBlocksAlongTheirLongestAxis() throws Exception {
        List<InspectedBlock> blocks =
                List.of(
                        new InspectedBlock(new BlockPosition(0, 0, 0), "minecraft:stone"),
                        new InspectedBlock(new BlockPosition(1, 0, 0), "minecraft:glass"),
                        new InspectedBlock(new BlockPosition(2, 0, 0), "minecraft:glass"),
                        new InspectedBlock(new BlockPosition(0, 1, 0), "minecraft:stone"),
                        new InspectedBlock(new BlockPosition(0, 2, 0), "minecraft:stone"));

        List<BlockRun> runs = PaperRegionInspector.groupSortedRuns(blocks, 2);

        assertEquals(
                List.of(
                        new BlockRun(
                                "minecraft:stone",
                                new BlockPosition(0, 0, 0),
                                new BlockPosition(0, 2, 0)),
                        new BlockRun(
                                "minecraft:glass",
                                new BlockPosition(1, 0, 0),
                                new BlockPosition(2, 0, 0))),
                runs);
    }

    @Test
    void rejectsRunResultsOverTheRequestedCap() {
        List<InspectedBlock> blocks =
                List.of(
                        new InspectedBlock(new BlockPosition(0, 0, 0), "minecraft:stone"),
                        new InspectedBlock(new BlockPosition(2, 0, 0), "minecraft:stone"));

        InspectionException exception =
                assertThrows(
                        InspectionException.class,
                        () -> PaperRegionInspector.groupSortedRuns(blocks, 1));

        assertEquals(Failure.RESULT_TOO_LARGE, exception.failure());
    }

    @Test
    void mapsEveryOrthographicViewDirectionToMinecraftWorldAxes() throws Exception {
        BlockPosition origin = new BlockPosition(10, 20, 30);

        assertViewGeometry(
                viewRequest(origin, OrthographicViewDirection.NORTH, 1, 2, 3, 10),
                new ViewBasis(
                        new AxisVector(0, 0, -1), new AxisVector(1, 0, 0), new AxisVector(0, 1, 0)),
                new BlockPosition(9, 18, 27),
                new BlockPosition(11, 22, 29));
        assertViewGeometry(
                viewRequest(origin, OrthographicViewDirection.EAST, 1, 2, 3, 10),
                new ViewBasis(
                        new AxisVector(1, 0, 0), new AxisVector(0, 0, 1), new AxisVector(0, 1, 0)),
                new BlockPosition(11, 18, 29),
                new BlockPosition(13, 22, 31));
        assertViewGeometry(
                viewRequest(origin, OrthographicViewDirection.SOUTH, 1, 2, 3, 10),
                new ViewBasis(
                        new AxisVector(0, 0, 1), new AxisVector(-1, 0, 0), new AxisVector(0, 1, 0)),
                new BlockPosition(9, 18, 31),
                new BlockPosition(11, 22, 33));
        assertViewGeometry(
                viewRequest(origin, OrthographicViewDirection.WEST, 1, 2, 3, 10),
                new ViewBasis(
                        new AxisVector(-1, 0, 0),
                        new AxisVector(0, 0, -1),
                        new AxisVector(0, 1, 0)),
                new BlockPosition(7, 18, 29),
                new BlockPosition(9, 22, 31));
        ViewBasis verticalBasis =
                new ViewBasis(
                        new AxisVector(0, 1, 0), new AxisVector(1, 0, 0), new AxisVector(0, 0, -1));
        assertViewGeometry(
                viewRequest(origin, OrthographicViewDirection.UP, 1, 2, 3, 10),
                verticalBasis,
                new BlockPosition(9, 21, 28),
                new BlockPosition(11, 23, 32));
        assertViewGeometry(
                viewRequest(origin, OrthographicViewDirection.DOWN, 1, 2, 3, 10),
                new ViewBasis(
                        new AxisVector(0, -1, 0),
                        verticalBasis.horizontal(),
                        verticalBasis.vertical()),
                new BlockPosition(9, 17, 28),
                new BlockPosition(11, 19, 32));
    }

    @Test
    void returnsTheFirstNonAirBlockPerSightlineInViewOrder() throws Exception {
        OrthographicViewRequest request =
                viewRequest(
                        new BlockPosition(0, 0, 0), OrthographicViewDirection.NORTH, 1, 1, 3, 10);
        PaperRegionInspector.ViewGeometry geometry =
                PaperRegionInspector.normalizeOrthographicView(request, 1_000, 1_000, 10);
        Map<BlockPosition, String> states =
                Map.of(
                        new BlockPosition(-1, 1, -2),
                        "minecraft:glass",
                        new BlockPosition(-1, 1, -3),
                        "minecraft:stone",
                        new BlockPosition(1, 1, -1),
                        "minecraft:oak_stairs[facing=north]",
                        new BlockPosition(0, 0, -3),
                        "minecraft:gold_block",
                        request.origin(),
                        "minecraft:barrier");

        List<ViewBlock> blocks =
                PaperRegionInspector.collectViewBlocks(request, geometry, states::get);

        assertEquals(
                List.of(
                        new ViewBlock(
                                new BlockPosition(-1, 1, -2),
                                new ViewOffset(-1, 1, 2),
                                "minecraft:glass"),
                        new ViewBlock(
                                new BlockPosition(1, 1, -1),
                                new ViewOffset(1, 1, 1),
                                "minecraft:oak_stairs[facing=north]"),
                        new ViewBlock(
                                new BlockPosition(0, 0, -3),
                                new ViewOffset(0, 0, 3),
                                "minecraft:gold_block")),
                blocks);
    }

    @Test
    void rejectsOversizedAndOverflowingViews() {
        OrthographicViewRequest oversized =
                viewRequest(
                        new BlockPosition(0, 0, 0),
                        OrthographicViewDirection.NORTH,
                        100,
                        100,
                        1,
                        10);
        OrthographicViewRequest overflowing =
                viewRequest(
                        new BlockPosition(Integer.MAX_VALUE, 0, 0),
                        OrthographicViewDirection.EAST,
                        0,
                        0,
                        1,
                        10);

        InspectionException oversizedFailure =
                assertThrows(
                        InspectionException.class,
                        () ->
                                PaperRegionInspector.normalizeOrthographicView(
                                        oversized, 1_000_000, 32_768, 10));
        InspectionException overflowFailure =
                assertThrows(
                        InspectionException.class,
                        () ->
                                PaperRegionInspector.normalizeOrthographicView(
                                        overflowing, 1_000_000, 32_768, 10));

        assertEquals(Failure.REGION_TOO_LARGE, oversizedFailure.failure());
        assertEquals(
                "View scan volume 40401 exceeds the maximum of 32768 blocks",
                oversizedFailure.getMessage());
        assertEquals(Failure.INVALID_REQUEST, overflowFailure.failure());
    }

    @Test
    void rejectsOrthographicViewResultsOverTheRequestedCapWithoutTruncating() throws Exception {
        OrthographicViewRequest request =
                viewRequest(
                        new BlockPosition(0, 0, 0), OrthographicViewDirection.NORTH, 1, 0, 1, 1);
        PaperRegionInspector.ViewGeometry geometry =
                PaperRegionInspector.normalizeOrthographicView(request, 100, 100, 1);

        InspectionException exception =
                assertThrows(
                        InspectionException.class,
                        () ->
                                PaperRegionInspector.collectViewBlocks(
                                        request, geometry, ignored -> "minecraft:stone"));

        assertEquals(Failure.RESULT_TOO_LARGE, exception.failure());
    }

    private static OrthographicViewRequest viewRequest(
            BlockPosition origin,
            OrthographicViewDirection direction,
            int horizontalRadius,
            int verticalRadius,
            int maxDistance,
            int maxResults) {
        return new OrthographicViewRequest(
                "world",
                origin,
                direction,
                horizontalRadius,
                verticalRadius,
                maxDistance,
                maxResults);
    }

    private static void assertViewGeometry(
            OrthographicViewRequest request, ViewBasis basis, BlockPosition min, BlockPosition max)
            throws Exception {
        PaperRegionInspector.ViewGeometry geometry =
                PaperRegionInspector.normalizeOrthographicView(request, 1_000, 1_000, 10);

        assertEquals(basis, geometry.basis());
        assertEquals(min, geometry.region().min());
        assertEquals(max, geometry.region().max());
        assertEquals(45, geometry.scannedVolume());
    }
}
