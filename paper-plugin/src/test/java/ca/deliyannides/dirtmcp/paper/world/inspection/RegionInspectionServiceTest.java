package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
                        "world", position(0, 0, 0), Direction.NORTH, 0, 0, 3, 0, 2);

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
        RegionInspectionService service = new RegionInspectionService(source, 100, 2, 2, 16, 8, 1);
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
                () -> new RegionInspectionService(source, 0, 1, 1, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegionInspectionService(source, 1, 0, 1, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegionInspectionService(source, 1, 1, 0, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegionInspectionService(source, 1, 1, 1, 0, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegionInspectionService(source, 1, 1, 1, 1, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegionInspectionService(source, 1, 1, 1, 1, 1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegionInspectionService(source, 1, 2, 1, 1, 1, 1));
    }

    @Test
    void capsRawPatternCountAndDeduplicatesBeforePaperCapture() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        RegionInspectionService service =
                new RegionInspectionService(source, 100, 100, 10, 16, 2, 1);
        GetRegionBlocks.Request tooMany =
                new GetRegionBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(0, 0, 0),
                        List.of("stone", "stone"),
                        List.of("dirt"),
                        false,
                        1,
                        Format.BLOCKS);

        assertEquals(
                OperationFailure.INVALID_REQUEST,
                assertThrows(OperationException.class, () -> service.getRegionBlocks(tooMany))
                        .failure());
        assertFalse(source.captured);

        service.getRegionBlocks(
                new GetRegionBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(0, 0, 0),
                        List.of("stone", "stone"),
                        List.of(),
                        false,
                        1,
                        Format.BLOCKS));
        assertEquals(List.of("stone"), source.includes);
    }

    @Test
    void rejectsConcurrentInspectionWithoutQueueing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeSnapshotSource source =
                new FakeSnapshotSource() {
                    @Override
                    public CapturedRegion capture(
                            String world,
                            Cuboid region,
                            List<String> includes,
                            List<String> excludes)
                            throws OperationException {
                        entered.countDown();
                        try {
                            if (!release.await(2, TimeUnit.SECONDS)) {
                                throw new AssertionError("inspection test did not release");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new OperationException(
                                    OperationFailure.WORLD_UNAVAILABLE,
                                    "inspection interrupted",
                                    new ErrorDetails.WorldUnavailable.Interrupted(),
                                    exception);
                        }
                        return super.capture(world, region, includes, excludes);
                    }
                };
        RegionInspectionService service =
                new RegionInspectionService(source, 100, 100, 10, 16, 8, 1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () ->
                                    service.countRegionBlockStates(
                                            new Request(
                                                    "world",
                                                    position(0, 0, 0),
                                                    position(0, 0, 0))));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            OperationException busy =
                    assertThrows(
                            OperationException.class,
                            () ->
                                    service.countRegionBlockStates(
                                            new Request(
                                                    "world",
                                                    position(0, 0, 0),
                                                    position(0, 0, 0))));
            assertEquals(OperationFailure.SERVER_UNAVAILABLE, busy.failure());
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void invalidDirectRequestsReturnTypedFailures() {
        RegionInspectionService service = service(new FakeSnapshotSource(), 16);

        OperationException missingRequest =
                assertThrows(OperationException.class, () -> service.scanOrthographicView(null));
        assertEquals(
                new ErrorDetails.InvalidRequest.Missing("request"),
                missingRequest.details().orElseThrow());

        OperationException combinedPatternLimit =
                assertThrows(
                        OperationException.class,
                        () ->
                                service.getRegionBlocks(
                                        new GetRegionBlocks.Request(
                                                "world",
                                                position(0, 0, 0),
                                                position(0, 0, 0),
                                                java.util.Collections.nCopies(5, "stone"),
                                                java.util.Collections.nCopies(4, "dirt"),
                                                false,
                                                1,
                                                Format.BLOCKS)));
        assertEquals(
                new ErrorDetails.InvalidRequest.TooManyItems(
                        List.of("includeBlockStatePatterns", "excludeBlockStatePatterns"), 8),
                combinedPatternLimit.details().orElseThrow());

        assertEquals(
                OperationFailure.INVALID_REQUEST,
                assertThrows(
                                OperationException.class,
                                () -> service.countRegionBlockStates(new Request(" ", null, null)))
                        .failure());
        assertEquals(
                OperationFailure.INVALID_REQUEST,
                assertThrows(OperationException.class, () -> service.getRegionBlocks(null))
                        .failure());
        assertEquals(
                OperationFailure.INVALID_REQUEST,
                assertThrows(
                                OperationException.class,
                                () ->
                                        service.getRegionBlocks(
                                                new GetRegionBlocks.Request(
                                                        "world",
                                                        position(0, 0, 0),
                                                        position(0, 0, 0),
                                                        java.util.Collections.singletonList(null),
                                                        List.of(),
                                                        false,
                                                        1,
                                                        Format.BLOCKS)))
                        .failure());
        assertEquals(
                OperationFailure.INVALID_REQUEST,
                assertThrows(
                                OperationException.class,
                                () ->
                                        service.scanOrthographicView(
                                                new ScanOrthographicView.Request(
                                                        "world", null, null, 0, 0, 1, 0, 1)))
                        .failure());
    }

    private static RegionInspectionService service(FakeSnapshotSource source, int maxChunks) {
        return new RegionInspectionService(source, 100, 100, 10, maxChunks, 8, 1);
    }

    private static BlockPosition position(int x, int y, int z) {
        return new BlockPosition(x, y, z);
    }

    private static BlockSample solid(String state) {
        return new BlockSample(state, false, true);
    }

    private static class FakeSnapshotSource implements RegionSnapshotSource {
        private final Map<BlockPosition, BlockSample> samples = new HashMap<>();
        private boolean captured;
        private List<String> includes = List.of();
        private List<String> excludes = List.of();

        @Override
        public CapturedRegion capture(
                String world,
                Cuboid region,
                List<String> includeBlockStatePatterns,
                List<String> excludeBlockStatePatterns)
                throws OperationException {
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
