package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.CountRegionBlockStates.Request;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.BlockListResult;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.BlockRunsResult;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.Format;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.BlockSample;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.Direction;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.ViewBlock;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockDimensions;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RegionInspectionServiceTest {
    @Test
    void countsARegionThroughTheSnapshotBoundary() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        source.samples.put(position(-1, 2, -1), solid("minecraft:stone"));
        source.samples.put(position(0, 2, -1), solid("minecraft:air"));
        source.samples.put(position(-1, 2, 0), solid("minecraft:stone"));
        source.samples.put(position(0, 2, 0), solid("minecraft:dirt"));
        RegionInspectionService service = service(source, 16);

        CountRegionBlockStates.Result result =
                service.countRegionBlockStates(
                        new Request("world", position(0, 2, 0), position(-1, 2, -1)));

        assertEquals("resolved-world", result.world());
        assertEquals(new BlockBounds(position(-1, 2, -1), position(0, 2, 0)), result.bounds());
        assertEquals(new BlockDimensions(2, 1, 2), result.dimensions());
        assertEquals(4, result.volume());
        assertEquals(
                Map.of("minecraft:air", 1L, "minecraft:dirt", 1L, "minecraft:stone", 2L),
                result.blockStateCounts());
        assertEquals(List.of(), source.includes);
        assertEquals(List.of(), source.excludes);
    }

    @Test
    void returnsFilteredBlocksAndPassesPatternsToTheAdapter() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        source.samples.put(position(0, 0, 0), solid("stone"));
        source.samples.put(position(1, 0, 0), new BlockSample("air", true, true));
        source.samples.put(position(2, 0, 0), new BlockSample("dirt", false, false));
        RegionInspectionService service = service(source, 16);
        GetRegionBlocks.Request request =
                new GetRegionBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(2, 0, 0),
                        List.of("#mineable"),
                        List.of("minecraft:dirt"),
                        false,
                        4,
                        Format.BLOCKS);

        GetRegionBlocks.Result result = service.getRegionBlocks(request);

        BlockListResult blocks = assertInstanceOf(BlockListResult.class, result);
        assertEquals("blocks", blocks.format());
        assertEquals(1, blocks.matchedBlockCount());
        assertEquals(List.of(new InspectedBlock(position(0, 0, 0), "stone")), blocks.blocks());
        assertEquals(List.of("#mineable"), source.includes);
        assertEquals(List.of("minecraft:dirt"), source.excludes);
    }

    @Test
    void returnsRunsWithoutApplyingTheLimitToMatchedBlocks() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        source.samples.put(position(0, 0, 0), solid("stone"));
        source.samples.put(position(1, 0, 0), solid("stone"));
        source.samples.put(position(2, 0, 0), solid("stone"));
        RegionInspectionService service = service(source, 16);
        GetRegionBlocks.Request request =
                new GetRegionBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(2, 0, 0),
                        List.of(),
                        List.of(),
                        false,
                        1,
                        Format.RUNS);

        BlockRunsResult result =
                assertInstanceOf(BlockRunsResult.class, service.getRegionBlocks(request));

        assertEquals(3, result.matchedBlockCount());
        assertEquals(1, result.runs().size());
        assertEquals(position(0, 0, 0), result.runs().getFirst().from());
        assertEquals(position(2, 0, 0), result.runs().getFirst().to());
    }

    @Test
    void returnsACompleteOrthographicViewContract() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        source.samples.put(position(0, 0, -2), solid("stone"));
        RegionInspectionService service = service(source, 16);
        ScanOrthographicView.Request request =
                new ScanOrthographicView.Request(
                        "world", position(0, 0, 0), Direction.NORTH, 0, 0, 3, 2);

        ScanOrthographicView.Result result = service.scanOrthographicView(request);

        assertEquals("north", result.direction());
        assertEquals("blocks", result.format());
        assertEquals(3, result.scannedVolume());
        assertEquals(1, result.visibleBlockCount());
        assertEquals(
                List.of(
                        new ViewBlock(
                                position(0, 0, -2),
                                new ScanOrthographicView.ViewOffset(0, 0, 2),
                                "stone")),
                result.blocks());
    }

    @Test
    void rejectsChunkHeavyRegionsBeforeSnapshotCapture() {
        FakeSnapshotSource source = new FakeSnapshotSource();
        RegionInspectionService service = service(source, 2);

        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () ->
                                service.countRegionBlockStates(
                                        new Request(
                                                "world", position(0, 0, 0), position(32, 0, 0))));

        assertEquals(OperationFailure.REGION_TOO_LARGE, exception.failure());
        assertFalse(source.captured);
    }

    @Test
    void appliesInspectionVolumeAndResultLimits() {
        FakeSnapshotSource source = new FakeSnapshotSource();
        RegionInspectionService service = new RegionInspectionService(source, 100, 2, 2, 16);
        GetRegionBlocks.Request oversized =
                new GetRegionBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(2, 0, 0),
                        List.of(),
                        List.of(),
                        false,
                        1,
                        Format.BLOCKS);
        GetRegionBlocks.Request tooManyResults =
                new GetRegionBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(0, 0, 0),
                        List.of(),
                        List.of(),
                        false,
                        3,
                        Format.BLOCKS);

        assertEquals(
                OperationFailure.REGION_TOO_LARGE,
                assertThrows(OperationException.class, () -> service.getRegionBlocks(oversized))
                        .failure());
        assertEquals(
                OperationFailure.INVALID_REQUEST,
                assertThrows(
                                OperationException.class,
                                () -> service.getRegionBlocks(tooManyResults))
                        .failure());
        assertFalse(source.captured);
    }

    @Test
    void validatesConstructorLimits() {
        FakeSnapshotSource source = new FakeSnapshotSource();
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegionInspectionService(source, 0, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegionInspectionService(source, 1, 0, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegionInspectionService(source, 1, 1, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegionInspectionService(source, 1, 1, 1, 0));
    }

    private static RegionInspectionService service(FakeSnapshotSource source, long maxChunks) {
        return new RegionInspectionService(source, 100, 100, 10, maxChunks);
    }

    private static BlockPosition position(int x, int y, int z) {
        return new BlockPosition(x, y, z);
    }

    private static BlockSample solid(String state) {
        return new BlockSample(state, false, true);
    }

    private static final class FakeSnapshotSource implements RegionSnapshotSource {
        private final Map<BlockPosition, BlockSample> samples = new HashMap<>();
        private boolean captured;
        private List<String> includes = List.of();
        private List<String> excludes = List.of();

        @Override
        public CapturedRegion capture(
                String world,
                Cuboid region,
                List<String> includeBlockStatePatterns,
                List<String> excludeBlockStatePatterns) {
            this.captured = true;
            this.includes = List.copyOf(includeBlockStatePatterns);
            this.excludes = List.copyOf(excludeBlockStatePatterns);
            return new CapturedRegion() {
                @Override
                public String worldName() {
                    return "resolved-world";
                }

                @Override
                public BlockSample sample(BlockPosition position) {
                    return samples.getOrDefault(position, new BlockSample("air", true, true));
                }
            };
        }
    }
}
