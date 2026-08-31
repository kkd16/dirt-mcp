package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.CountRegionBlockStates.Request;
import ca.deliyannides.dirtmcp.paper.world.inspection.ExactBlockStructure.ExactPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.BlockSample;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.Direction;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockDimensions;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.ArrayList;
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
        GetBlocks.Request request =
                new GetBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(2, 0, 0),
                        List.of("#mineable"),
                        List.of("minecraft:dirt"),
                        false,
                        4);

        ExactBlockStructure result = service.getBlocks(request);

        assertEquals("resolved-world", result.world());
        assertEquals(position(0, 0, 0), result.origin());
        assertEquals(List.of(List.of(new ExactPaletteEntry("stone"))), result.palettes());
        assertEquals(List.of(new Placement(0, 0, 0, 0)), result.placements());
        assertEquals(List.of(), result.runs());
        assertEquals(List.of("#mineable"), source.includes);
        assertEquals(List.of("minecraft:dirt"), source.excludes);
    }

    @Test
    void packsMatchedBlocksWithoutApplyingTheEntryLimitToExpandedBlocks() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        source.samples.put(position(0, 0, 0), solid("stone"));
        source.samples.put(position(1, 0, 0), solid("stone"));
        source.samples.put(position(2, 0, 0), solid("stone"));
        RegionInspectionService service = service(source, 16);
        GetBlocks.Request request =
                new GetBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(2, 0, 0),
                        List.of(),
                        List.of(),
                        false,
                        1);

        ExactBlockStructure result = service.getBlocks(request);

        assertEquals(3, result.blockCount());
        assertEquals(List.of(), result.placements());
        assertEquals(1, result.runs().size());
        assertEquals(new Run(0, 0, 0, 0, 2, 0, 0), result.runs().getFirst());
    }

    @Test
    void returnsAReplayReadyOrthographicStructure() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        source.samples.put(position(0, 0, -2), solid("stone"));
        RegionInspectionService service = service(source, 16);
        ScanOrthographicView.Request request =
                new ScanOrthographicView.Request(
                        "world", position(0, 0, 0), Direction.NORTH, 0, 0, 3, 0, 2);

        ExactBlockStructure result = service.scanOrthographicView(request);

        assertEquals("resolved-world", result.world());
        assertEquals(position(0, 0, -3), result.origin());
        assertEquals(List.of(List.of(new ExactPaletteEntry("stone"))), result.palettes());
        assertEquals(List.of(new Placement(0, 0, 0, 1)), result.placements());
        assertEquals(List.of(), result.runs());
    }

    @Test
    void appliesTheOrthographicResultLimitAfterPacking() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        source.samples.put(position(-1, 0, -1), solid("stone"));
        source.samples.put(position(0, 0, -1), solid("stone"));
        source.samples.put(position(1, 0, -1), solid("stone"));
        RegionInspectionService service = service(source, 16);
        ScanOrthographicView.Request request =
                new ScanOrthographicView.Request(
                        "world", position(0, 0, 0), Direction.NORTH, 1, 0, 1, 0, 1);

        ExactBlockStructure packed = service.scanOrthographicView(request);

        assertEquals(position(-1, 0, -1), packed.origin());
        assertEquals(List.of(), packed.placements());
        assertEquals(List.of(new Run(0, 0, 0, 0, 2, 0, 0)), packed.runs());

        source.samples.put(position(0, 0, -1), solid("dirt"));
        OperationException exception =
                assertThrows(OperationException.class, () -> service.scanOrthographicView(request));
        assertEquals(OperationFailure.RESULT_TOO_LARGE, exception.failure());
        assertEquals(
                new ErrorDetails.ResultTooLarge.StructureEntries(2, 1),
                exception.details().orElseThrow());
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
        RegionInspectionService service =
                new RegionInspectionService(
                        source, 100, 2, 2, 16, 8, 8, new InspectionAdmission(1));
        GetBlocks.Request oversized =
                new GetBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(2, 0, 0),
                        List.of(),
                        List.of(),
                        false,
                        1);
        GetBlocks.Request invalidResultLimit =
                new GetBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(0, 0, 0),
                        List.of(),
                        List.of(),
                        false,
                        0);

        assertEquals(
                OperationFailure.REGION_TOO_LARGE,
                assertThrows(OperationException.class, () -> service.getBlocks(oversized))
                        .failure());
        assertEquals(
                OperationFailure.INVALID_REQUEST,
                assertThrows(OperationException.class, () -> service.getBlocks(invalidResultLimit))
                        .failure());
        assertFalse(source.captured);
    }

    @Test
    void appliesConfiguredResultCeilingWithoutRejectingALargerCallerCeiling() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        source.samples.put(position(0, 0, 0), solid("stone"));
        RegionInspectionService service =
                new RegionInspectionService(
                        source, 100, 3, 2, 16, 8, 8, new InspectionAdmission(1));

        ExactBlockStructure withinConfiguredLimit =
                service.getBlocks(
                        new GetBlocks.Request(
                                "world",
                                position(0, 0, 0),
                                position(0, 0, 0),
                                List.of(),
                                List.of(),
                                false,
                                3));

        assertEquals(1, withinConfiguredLimit.blockCount());

        source.samples.put(position(1, 0, 0), solid("dirt"));
        source.samples.put(position(2, 0, 0), solid("stone"));
        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () ->
                                service.getBlocks(
                                        new GetBlocks.Request(
                                                "world",
                                                position(0, 0, 0),
                                                position(2, 0, 0),
                                                List.of(),
                                                List.of(),
                                                false,
                                                3)));

        assertEquals(OperationFailure.RESULT_TOO_LARGE, failure.failure());
        assertEquals(
                new ErrorDetails.ResultTooLarge.StructureEntries(3, 2),
                failure.details().orElseThrow());
    }

    @Test
    void validatesConstructorLimits() {
        FakeSnapshotSource source = new FakeSnapshotSource();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RegionInspectionService(
                                source, 0, 1, 1, 1, 1, 1, new InspectionAdmission(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RegionInspectionService(
                                source, 1, 0, 1, 1, 1, 1, new InspectionAdmission(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RegionInspectionService(
                                source, 1, 1, 0, 1, 1, 1, new InspectionAdmission(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RegionInspectionService(
                                source, 1, 1, 1, 0, 1, 1, new InspectionAdmission(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RegionInspectionService(
                                source, 1, 1, 1, 1, 0, 1, new InspectionAdmission(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RegionInspectionService(
                                source, 1, 1, 1, 1, 1, 0, new InspectionAdmission(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RegionInspectionService(
                                source, 1, 2, 1, 1, 1, 1, new InspectionAdmission(1)));
    }

    @Test
    void capsPatternCountAndRejectsDuplicatesBeforePaperCapture() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        RegionInspectionService service =
                new RegionInspectionService(
                        source, 100, 100, 10, 16, 2, 8, new InspectionAdmission(1));
        GetBlocks.Request tooMany =
                new GetBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(0, 0, 0),
                        List.of("stone", "stone"),
                        List.of("dirt"),
                        false,
                        1);

        assertEquals(
                OperationFailure.INVALID_REQUEST,
                assertThrows(OperationException.class, () -> service.getBlocks(tooMany)).failure());
        assertFalse(source.captured);

        OperationException duplicate =
                assertThrows(
                        OperationException.class,
                        () ->
                                service.getBlocks(
                                        new GetBlocks.Request(
                                                "world",
                                                position(0, 0, 0),
                                                position(0, 0, 0),
                                                List.of("stone", "stone"),
                                                List.of(),
                                                false,
                                                1)));
        assertEquals(
                new ErrorDetails.InvalidRequest.Duplicate("includeBlockStatePatterns[1]"),
                duplicate.details().orElseThrow());
        assertFalse(source.captured);
    }

    @Test
    void appliesThePaletteLimitIndependentlyFromThePatternLimit() throws Exception {
        FakeSnapshotSource source = new FakeSnapshotSource();
        source.samples.put(position(0, 0, 0), solid("stone"));
        source.samples.put(position(1, 0, 0), solid("dirt"));
        GetBlocks.Request request =
                new GetBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        position(1, 0, 0),
                        List.of(),
                        List.of(),
                        false,
                        10);

        RegionInspectionService twoPalettes =
                new RegionInspectionService(
                        source, 100, 100, 10, 16, 1, 2, new InspectionAdmission(1));
        assertEquals(2, twoPalettes.getBlocks(request).palettes().size());

        RegionInspectionService onePalette =
                new RegionInspectionService(
                        source, 100, 100, 10, 16, 1, 1, new InspectionAdmission(1));
        assertEquals(
                OperationFailure.RESULT_TOO_LARGE,
                assertThrows(OperationException.class, () -> onePalette.getBlocks(request))
                        .failure());
    }

    @Test
    void admitsFourConcurrentInspectionsAndRejectsAZeroQueueFifth() throws Exception {
        InspectionAdmission admission = new InspectionAdmission(4);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var inspections = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index = 0; index < 4; index++) {
                inspections.add(
                        executor.submit(
                                () ->
                                        admission.execute(
                                                () -> {
                                                    entered.countDown();
                                                    try {
                                                        if (!release.await(5, TimeUnit.SECONDS)) {
                                                            throw new AssertionError(
                                                                    "inspection test did not release");
                                                        }
                                                    } catch (InterruptedException exception) {
                                                        Thread.currentThread().interrupt();
                                                        throw new AssertionError(
                                                                "inspection test interrupted",
                                                                exception);
                                                    }
                                                    return 1;
                                                })));
            }

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OperationException busy =
                    assertThrows(OperationException.class, () -> admission.execute(() -> 1));
            assertEquals(OperationFailure.SERVER_UNAVAILABLE, busy.failure());
            assertEquals(
                    new ErrorDetails.ServerUnavailable.InspectionBusy(4),
                    busy.details().orElseThrow());

            release.countDown();
            for (var inspection : inspections) {
                assertEquals(1, inspection.get(5, TimeUnit.SECONDS));
            }
        } finally {
            release.countDown();
        }
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
                new RegionInspectionService(
                        source, 100, 100, 10, 16, 8, 8, new InspectionAdmission(1));

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
                                service.getBlocks(
                                        new GetBlocks.Request(
                                                "world",
                                                position(0, 0, 0),
                                                position(0, 0, 0),
                                                List.of(
                                                        "stone0", "stone1", "stone2", "stone3",
                                                        "stone4"),
                                                List.of("dirt0", "dirt1", "dirt2", "dirt3"),
                                                false,
                                                1)));
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
                assertThrows(OperationException.class, () -> service.getBlocks(null)).failure());
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
        return new RegionInspectionService(
                source, 100, 100, 10, maxChunks, 8, 8, new InspectionAdmission(1));
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
