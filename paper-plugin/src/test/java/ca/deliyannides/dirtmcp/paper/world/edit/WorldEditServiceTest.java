package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

final class WorldEditServiceTest {
    private static final int MAX_BLOCK_STATE_PATTERNS = 64;
    private static final int MAX_PALETTE_ENTRIES = 256;
    private static final UUID WORLD_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_WORLD_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CALL_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

    @Test
    void replaceNormalizesBoundsAndRetainsCommittedHistory() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextMatches = 9;
        platform.nextChanges = 4;
        WorldEditService editor = editor(platform, 10);

        ReplaceRegionBlocks.Result result =
                replace(
                        editor,
                        new ReplaceRegionBlocks.Request(
                                "world",
                                position(5, 4, 3),
                                position(1, 2, 0),
                                List.of("minecraft:stone"),
                                palette(),
                                17,
                                false,
                                "Shape the entry arch",
                                null));

        assertEquals(position(1, 2, 0), result.bounds().min());
        assertEquals(position(5, 4, 3), result.bounds().max());
        assertEquals(9, result.matchedBlockCount());
        assertEquals(4, result.changedBlockCount());
        assertEquals(EditOutcome.COMMITTED, result.outcome());
        assertNotNull(result.edit());
        assertEquals(CALL_ID, result.edit().callId());
        assertEquals(EditOperation.REPLACE_REGION_BLOCKS, result.edit().operation());
        assertEquals(WORLD_ID, result.edit().worldId());
        assertEquals(result.bounds(), result.edit().bounds());
        assertEquals(4, result.edit().changedBlockCount());
        assertEquals(EditStatus.COMMITTED, result.edit().status());
        assertNotNull(result.edit().completedAt());
        assertTrue(platform.lastPrepared.closed);
        assertTrue(platform.lastPrepared.executedBeforeClose);
    }

    @Test
    void retainsCommittedReplaceWhenResponseCountsConflict() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextMatches = 1;
        platform.nextChanges = 2;
        WorldEditService editor = editor(platform, 10);

        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () ->
                                replace(
                                        editor,
                                        new ReplaceRegionBlocks.Request(
                                                "world",
                                                position(0, 0, 0),
                                                position(1, 0, 0),
                                                List.of("minecraft:stone"),
                                                palette(),
                                                17,
                                                false,
                                                "Replace the wall",
                                                null)));

        EditRecord retained = history(editor, "world").getFirst();
        assertEquals(OperationFailure.INTERNAL_ERROR, failure.failure());
        assertEquals(java.util.Optional.of(retained.editId()), failure.editId());
        assertEquals(2, retained.changedBlockCount());
        assertFalse(platform.lastUndo.closed);
    }

    @Test
    void setReturnsCountsForRunsAndPlacements() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 2;
        WorldEditService editor = editor(platform, 10);

        SetBlocks.Result cuboid = set(editor, cuboidSetRequest("world", false));
        SetBlocks.Result set =
                set(
                        editor,
                        setRequest(
                                "world",
                                List.of(placement(0, 0, 0), placement(1, 0, 0), placement(2, 0, 0)),
                                false));

        assertEquals(8, cuboid.blockCount());
        assertEquals(2, cuboid.changedBlockCount());
        assertEquals(6, cuboid.unchangedBlockCount());
        assertEquals(3, set.blockCount());
        assertEquals(position(0, 0, 0), set.bounds().min());
        assertEquals(position(2, 0, 0), set.bounds().max());
        assertEquals(2, set.changedBlockCount());
        assertEquals(1, set.unchangedBlockCount());
        assertEquals(13, set.seed());
    }

    @Test
    void emptySetIsANoOpWithoutPreparingFaweOrHistory() throws Exception {
        FakePlatform platform = new FakePlatform();
        WorldEditService editor = editor(platform, 3);

        SetBlocks.Result result =
                set(
                        editor,
                        new SetBlocks.Request(
                                "world",
                                position(10, 64, 10),
                                List.of(),
                                List.of(),
                                List.of(),
                                13,
                                true,
                                "Preview empty geometry",
                                null));

        assertEquals("world", result.world());
        assertNull(result.bounds());
        assertEquals(13, result.seed());
        assertEquals(EditOutcome.PREVIEW, result.outcome());
        assertEquals(0, result.blockCount());
        assertEquals(0, result.changedBlockCount());
        assertEquals(0, result.unchangedBlockCount());
        assertNull(result.edit());
        assertEquals(1, platform.resolveCalls);
        assertEquals(0, platform.prepareCalls);
        assertEquals(0, platform.executeCalls);
        assertEquals(List.of(), history(editor, "world"));
    }

    @Test
    void passesCompactAbsoluteSetGeometryToThePlatformInWireOrder() throws Exception {
        FakePlatform platform = new FakePlatform();
        WorldEditService editor = editor(platform, 3);

        set(
                editor,
                new SetBlocks.Request(
                        "world",
                        position(15, 64, -17),
                        palettes(),
                        List.of(placement(-1, 2, 3)),
                        List.of(new Run(0, 2, -3, 20, 3, -3, 20), new Run(0, 4, -3, 20, 5, -3, 20)),
                        13,
                        true,
                        "Preview placements",
                        null));

        assertEquals(
                List.of(new SetBlockGeometry.ResolvedPlacement(0, position(14, 66, -14))),
                platform.lastSetGeometry.placements());
        assertEquals(
                List.of(
                        new SetBlockGeometry.ResolvedRun(
                                0, new Cuboid(position(17, 61, 3), position(18, 61, 3))),
                        new SetBlockGeometry.ResolvedRun(
                                0, new Cuboid(position(19, 61, 3), position(20, 61, 3)))),
                platform.lastSetGeometry.runs());
        assertEquals(5, platform.lastSetGeometry.blockCount());
        assertEquals(
                new BlockBounds(position(14, 61, -14), position(20, 66, 3)),
                platform.lastSetGeometry.bounds());
        assertEquals(
                List.of(new ChunkPosition(0, -1), new ChunkPosition(1, 0)),
                platform.lastSetGeometry.chunks());
    }

    @Test
    void retainsLargeRunsAsSingleCuboids() throws Exception {
        FakePlatform platform = new FakePlatform();
        WorldEditService editor =
                new WorldEditService(
                        platform,
                        64 * 64 * 64,
                        16,
                        MAX_BLOCK_STATE_PATTERNS,
                        MAX_PALETTE_ENTRIES,
                        100,
                        history(3));

        set(
                editor,
                new SetBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        palettes(),
                        List.of(),
                        List.of(new Run(0, 0, 0, 0, 63, 63, 63)),
                        13,
                        true,
                        "Preview large run",
                        null));

        assertEquals(64 * 64 * 64, platform.lastSetGeometry.blockCount());
        assertEquals(List.of(), platform.lastSetGeometry.placements());
        assertEquals(
                List.of(
                        new SetBlockGeometry.ResolvedRun(
                                0, new Cuboid(position(0, 0, 0), position(63, 63, 63)))),
                platform.lastSetGeometry.runs());
        assertEquals(16, platform.lastSetGeometry.chunks().size());
    }

    @Test
    void rejectsRegionTouchingTooManyChunksBeforeResolvingWorld() {
        FakePlatform platform = new FakePlatform();
        WorldEditService editor =
                new WorldEditService(
                        platform,
                        1_000,
                        2,
                        MAX_BLOCK_STATE_PATTERNS,
                        MAX_PALETTE_ENTRIES,
                        100,
                        history(3));

        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () ->
                                set(
                                        editor,
                                        new SetBlocks.Request(
                                                "world",
                                                position(0, 0, 0),
                                                palettes(),
                                                List.of(),
                                                List.of(new Run(0, 0, 0, 0, 32, 0, 0)),
                                                0,
                                                false,
                                                "Reject oversized run",
                                                null)));

        assertEquals(OperationFailure.REGION_TOO_LARGE, exception.failure());
        assertEquals(0, platform.resolveCalls);
        assertEquals(0, platform.prepareCalls);
    }

    @Test
    void rejectsSetBlockChunkLimitBeforeWorldAndStatePreparation() {
        FakePlatform platform = new FakePlatform();
        WorldEditService editor =
                new WorldEditService(
                        platform,
                        100,
                        1,
                        MAX_BLOCK_STATE_PATTERNS,
                        MAX_PALETTE_ENTRIES,
                        100,
                        history(3));

        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () ->
                                set(
                                        editor,
                                        setRequest(
                                                "world",
                                                List.of(placement(0, 0, 0), placement(16, 0, 0)),
                                                false)));

        assertEquals(OperationFailure.REGION_TOO_LARGE, exception.failure());
        assertEquals(0, platform.resolveCalls);
        assertEquals(0, platform.prepareCalls);
    }

    @Test
    void rejectsNoncanonicalWorldNamesBeforePreparation() {
        FakePlatform platform = new FakePlatform();
        platform.caseInsensitiveWorldLookup = true;
        WorldEditService editor = editor(platform, 3);

        assertFailure(
                OperationFailure.WORLD_NOT_FOUND,
                () -> set(editor, cuboidSetRequest("WORLD", false)));

        assertEquals(1, platform.resolveCalls);
        assertEquals(0, platform.prepareCalls);
    }

    @Test
    void validatesEditSemanticsBeforePlatformAccess() {
        WorldEditService editor = editor(new FakePlatform(), 3);

        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        set(
                                editor,
                                new SetBlocks.Request(
                                        "world",
                                        position(0, 0, 0),
                                        palettes(),
                                        List.of(),
                                        List.of(),
                                        0,
                                        false,
                                        "Reject empty geometry",
                                        null)));
        OperationException paletteReference =
                assertThrows(
                        OperationException.class,
                        () ->
                                set(
                                        editor,
                                        new SetBlocks.Request(
                                                "world",
                                                position(0, 0, 0),
                                                palettes(),
                                                List.of(new Placement(1, 0, 0, 0)),
                                                List.of(),
                                                0,
                                                false,
                                                "Reject palette reference",
                                                null)));
        assertEquals(OperationFailure.INVALID_REQUEST, paletteReference.failure());
        assertEquals(
                new ErrorDetails.InvalidRequest.OutOfRange("placements[0][0]", 1, 0, 0),
                paletteReference.details().orElseThrow());
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        set(
                                editor,
                                setRequest(
                                        "world",
                                        List.of(placement(0, 0, 0), placement(0, 0, 0)),
                                        false)));
        OperationException coordinateOverflow =
                assertThrows(
                        OperationException.class,
                        () ->
                                set(
                                        editor,
                                        new SetBlocks.Request(
                                                "world",
                                                position(Integer.MAX_VALUE, 0, 0),
                                                palettes(),
                                                List.of(new Placement(0, 1, 0, 0)),
                                                List.of(),
                                                0,
                                                false,
                                                "Reject coordinate overflow",
                                                null)));
        assertEquals(OperationFailure.INVALID_REQUEST, coordinateOverflow.failure());
        assertEquals(
                new ErrorDetails.InvalidRequest.OutOfRange(
                        "placements[0].resolved.x",
                        (long) Integer.MAX_VALUE + 1,
                        Integer.MIN_VALUE,
                        Integer.MAX_VALUE),
                coordinateOverflow.details().orElseThrow());
        assertFailure(OperationFailure.INVALID_REQUEST, () -> undo(editor, " ", UUID.randomUUID()));
        WorldEditService smallEditor =
                new WorldEditService(
                        new FakePlatform(),
                        3,
                        10,
                        MAX_BLOCK_STATE_PATTERNS,
                        MAX_PALETTE_ENTRIES,
                        3,
                        history(3));
        assertFailure(
                OperationFailure.REGION_TOO_LARGE,
                () ->
                        set(
                                smallEditor,
                                setRequest(
                                        "world",
                                        List.of(
                                                placement(0, 0, 0),
                                                placement(1, 0, 0),
                                                placement(2, 0, 0),
                                                placement(3, 0, 0)),
                                        false)));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        replace(
                                editor,
                                new ReplaceRegionBlocks.Request(
                                        "world",
                                        position(0, 0, 0),
                                        position(0, 0, 0),
                                        java.util.Collections.nCopies(
                                                MAX_BLOCK_STATE_PATTERNS + 1, "minecraft:stone"),
                                        palette(),
                                        0,
                                        false,
                                        "Reject excessive patterns",
                                        null)));
    }

    @Test
    void rejectsMalformedSetBlockPaletteAndPlacements() {
        WorldEditService editor = editor(new FakePlatform(), 3);
        BlockPosition origin = position(0, 0, 0);

        OperationException wrongWeightTotal =
                assertThrows(
                        OperationException.class,
                        () ->
                                set(
                                        editor,
                                        new SetBlocks.Request(
                                                "world",
                                                origin,
                                                List.of(
                                                        List.of(
                                                                new DestinationPaletteEntry(
                                                                        "minecraft:stone", 40))),
                                                List.of(placement(0, 0, 0)),
                                                List.of(),
                                                0,
                                                false,
                                                "Reject wrong weight",
                                                null)));
        assertEquals(OperationFailure.INVALID_REQUEST, wrongWeightTotal.failure());
        assertEquals(
                new ErrorDetails.InvalidRequest.PaletteWeightTotal("palettes[0]", 40),
                wrongWeightTotal.details().orElseThrow());
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        set(
                                editor,
                                new SetBlocks.Request(
                                        "world",
                                        origin,
                                        List.of(List.of(new DestinationPaletteEntry(" ", null))),
                                        List.of(placement(0, 0, 0)),
                                        List.of(),
                                        0,
                                        false,
                                        "Reject blank state",
                                        null)));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        set(
                                editor,
                                new SetBlocks.Request(
                                        "world",
                                        origin,
                                        List.of(
                                                List.of(
                                                        new DestinationPaletteEntry(
                                                                "minecraft:stone", 50),
                                                        new DestinationPaletteEntry(
                                                                "minecraft:dirt", null))),
                                        List.of(placement(0, 0, 0)),
                                        List.of(),
                                        0,
                                        false,
                                        "Reject mixed weights",
                                        null)));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        set(
                                editor,
                                new SetBlocks.Request(
                                        "world",
                                        origin,
                                        List.of(
                                                java.util.Collections.nCopies(
                                                        129,
                                                        new DestinationPaletteEntry(
                                                                "minecraft:stone", null)),
                                                java.util.Collections.nCopies(
                                                        128,
                                                        new DestinationPaletteEntry(
                                                                "minecraft:dirt", null))),
                                        List.of(placement(0, 0, 0)),
                                        List.of(),
                                        0,
                                        false,
                                        "Reject excessive palettes",
                                        null)));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        set(
                                editor,
                                new SetBlocks.Request(
                                        "world",
                                        origin,
                                        palettes(),
                                        List.of(new Placement(-1, 0, 0, 0)),
                                        List.of(),
                                        0,
                                        false,
                                        "Reject negative palette index",
                                        null)));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        set(
                                editor,
                                new SetBlocks.Request(
                                        "world",
                                        origin,
                                        List.of(java.util.Collections.emptyList()),
                                        List.of(placement(0, 0, 0)),
                                        List.of(),
                                        0,
                                        false,
                                        "Reject empty palette",
                                        null)));
    }

    @Test
    void rejectsReversedOverlappingOverflowingAndOversizedRuns() {
        WorldEditService editor = editor(new FakePlatform(), 3);
        BlockPosition origin = position(0, 0, 0);

        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        set(
                                editor,
                                new SetBlocks.Request(
                                        "world",
                                        origin,
                                        palettes(),
                                        List.of(),
                                        List.of(new Run(0, 1, 0, 0, 0, 0, 0)),
                                        0,
                                        false,
                                        "Reject reversed run",
                                        null)));
        OperationException overlap =
                assertThrows(
                        OperationException.class,
                        () ->
                                set(
                                        editor,
                                        new SetBlocks.Request(
                                                "world",
                                                origin,
                                                palettes(),
                                                List.of(placement(1, 0, 0)),
                                                List.of(new Run(0, 0, 0, 0, 2, 0, 0)),
                                                0,
                                                false,
                                                "Reject overlapping placement",
                                                null)));
        assertEquals(
                new ErrorDetails.InvalidRequest.Duplicate("runs[0]"),
                overlap.details().orElseThrow());
        OperationException runOverlap =
                assertThrows(
                        OperationException.class,
                        () ->
                                set(
                                        editor,
                                        new SetBlocks.Request(
                                                "world",
                                                origin,
                                                palettes(),
                                                List.of(),
                                                List.of(
                                                        new Run(0, 0, 0, 0, 16, 0, 0),
                                                        new Run(0, 16, 0, 0, 17, 0, 0)),
                                                0,
                                                false,
                                                "Reject overlapping runs",
                                                null)));
        assertEquals(
                new ErrorDetails.InvalidRequest.Duplicate("runs[1]"),
                runOverlap.details().orElseThrow());
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        set(
                                editor,
                                new SetBlocks.Request(
                                        "world",
                                        position(Integer.MAX_VALUE, 0, 0),
                                        palettes(),
                                        List.of(),
                                        List.of(new Run(0, 0, 0, 0, 1, 0, 0)),
                                        0,
                                        false,
                                        "Reject run overflow",
                                        null)));

        WorldEditService smallEditor =
                new WorldEditService(
                        new FakePlatform(),
                        3,
                        10,
                        MAX_BLOCK_STATE_PATTERNS,
                        MAX_PALETTE_ENTRIES,
                        3,
                        history(3));
        OperationException oversized =
                assertThrows(
                        OperationException.class,
                        () ->
                                set(
                                        smallEditor,
                                        new SetBlocks.Request(
                                                "world",
                                                origin,
                                                palettes(),
                                                List.of(),
                                                List.of(new Run(0, 0, 0, 0, 3, 0, 0)),
                                                0,
                                                false,
                                                "Reject oversized edit",
                                                null)));
        assertEquals(OperationFailure.REGION_TOO_LARGE, oversized.failure());
        assertEquals(
                new ErrorDetails.RegionTooLarge.BlockCount(4, 3),
                oversized.details().orElseThrow());
    }

    @Test
    void closesPreparedResourcesWhenExecutionFails() {
        FakePlatform platform = new FakePlatform();
        platform.failExecution = true;
        WorldEditService editor = editor(platform, 3);

        assertThrows(OperationException.class, () -> set(editor, cuboidSetRequest("world", false)));

        assertNotNull(platform.lastPrepared);
        assertTrue(platform.lastPrepared.closed);
    }

    @Test
    void failedReplacementEditPreservesHistoryClaimedForEviction() throws OperationException {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        WorldEditService editor = editor(platform, 1);
        EditRecord retained = set(editor, cuboidSetRequest("world", false)).edit();
        FakeUndo retainedUndo = platform.lastUndo;

        platform.failExecution = true;
        assertThrows(OperationException.class, () -> set(editor, cuboidSetRequest("world", false)));

        assertEquals(List.of(retained), history(editor, "world"));
        assertFalse(retainedUndo.closed);
    }

    @Test
    void validatesPreparedMetadataBeforeMutation() {
        FakePlatform metadataFailure = new FakePlatform();
        metadataFailure.nextChanges = 1;
        metadataFailure.failPreparedAccess = true;
        WorldEditService metadataEditor = editor(metadataFailure, 3);

        assertThrows(
                IllegalStateException.class,
                () -> set(metadataEditor, cuboidSetRequest("world", false)));

        assertEquals(0, metadataFailure.executeCalls);
        assertNull(metadataFailure.lastUndo);
        assertTrue(metadataFailure.lastPrepared.closed);

        FakePlatform countMismatch = new FakePlatform();
        countMismatch.nextChanges = 1;
        countMismatch.preparedBlockCountDelta = 1;
        WorldEditService setEditor = editor(countMismatch, 3);

        assertThrows(
                IllegalStateException.class,
                () -> set(setEditor, setRequest("world", List.of(placement(0, 0, 0)), false)));

        assertEquals(0, countMismatch.executeCalls);
        assertNull(countMismatch.lastUndo);
        assertTrue(countMismatch.lastPrepared.closed);
    }

    @Test
    void rollsBackAndClosesUndoWhenHistoryTransferValidationFails() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.undoCountDelta = 1;
        WorldEditService editor = editor(platform, 3);

        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () -> set(editor, cuboidSetRequest("world", false)));

        assertEquals(OperationFailure.INTERNAL_ERROR, failure.failure());
        assertTrue(failure.editId().isPresent());
        assertEquals(List.of(1), platform.undoneIds);
        assertTrue(platform.lastUndo.closed);
        assertTrue(history(editor, "world").isEmpty());
    }

    @Test
    void retainsRecoveryWhenHistoryTransferAndInlineRollbackBothFail() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.undoCountDelta = 1;
        platform.failUndo = true;
        WorldEditService editor = editor(platform, 3);

        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () -> set(editor, cuboidSetRequest("world", false)));

        EditRecord recovery = history(editor, "world").getFirst();
        assertEquals(OperationFailure.INTERNAL_ERROR, failure.failure());
        assertEquals(java.util.Optional.of(recovery.editId()), failure.editId());
        assertEquals(EditStatus.RECOVERY_REQUIRED, recovery.status());
        assertEquals(2, recovery.changedBlockCount());
        assertFalse(platform.lastUndo.closed);

        platform.failUndo = false;
        undo(editor, "world", recovery.editId());
        assertTrue(platform.lastUndo.closed);
        assertTrue(history(editor, "world").isEmpty());
    }

    @Test
    void retainsCommittedHistoryWhenPreparedResourceCleanupFails() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.failClose = true;
        WorldEditService editor = editor(platform, 3);

        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () -> set(editor, cuboidSetRequest("world", false)));
        platform.failClose = false;

        EditRecord retained = history(editor, "world").getFirst();
        assertEquals(java.util.Optional.of(retained.editId()), failure.editId());
        UndoEdits.Completed result = (UndoEdits.Completed) undo(editor, "world", retained.editId());
        assertEquals(1, result.undoneEdits().getFirst().changedBlockCount());
    }

    @Test
    void retainsRecoveryHistoryWhenAutomaticRollbackFails() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 3;
        platform.failWithRecovery = true;
        WorldEditService editor = editor(platform, 0);

        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () -> set(editor, cuboidSetRequest("world", false)));
        platform.failWithRecovery = false;

        assertFailure(
                OperationFailure.WORLD_BUSY, () -> set(editor, cuboidSetRequest("world", false)));

        EditRecord recovery = history(editor, "world").getFirst();
        assertEquals(java.util.Optional.of(recovery.editId()), failure.editId());
        assertEquals(
                new ErrorDetails.WorldUnavailable.RollbackFailed(),
                failure.details().orElseThrow());
        assertEquals(EditStatus.RECOVERY_REQUIRED, recovery.status());
        UndoEdits.Completed result = (UndoEdits.Completed) undo(editor, "world", recovery.editId());
        assertEquals(3, result.undoneEdits().getFirst().changedBlockCount());
        assertEquals(3, set(editor, cuboidSetRequest("world", false)).changedBlockCount());
    }

    @Test
    void rejectsBeforeMutationWhenPinnedRecoveryExhaustsGlobalHistoryCapacity() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.failWithRecovery = true;
        WorldEditService editor =
                new WorldEditService(
                        platform,
                        100,
                        10,
                        MAX_BLOCK_STATE_PATTERNS,
                        MAX_PALETTE_ENTRIES,
                        1,
                        new DirtConfig.EditHistory(1, 1, 1));
        assertThrows(OperationException.class, () -> set(editor, cuboidSetRequest("world", false)));
        platform.failWithRecovery = false;

        OperationException capacity =
                assertThrows(
                        OperationException.class,
                        () -> set(editor, cuboidSetRequest("other", false)));

        assertEquals(OperationFailure.HISTORY_CAPACITY_EXCEEDED, capacity.failure());
        assertTrue(capacity.editId().isEmpty());
        assertTrue(platform.undoneIds.isEmpty());
        assertTrue(history(editor, "other").isEmpty());
        assertEquals(EditStatus.RECOVERY_REQUIRED, history(editor, "world").getFirst().status());
    }

    @Test
    void dryRunsAndNoOpsNeverEnterUndoHistory() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        WorldEditService editor = editor(platform, 3);

        SetBlocks.Result preview = set(editor, cuboidSetRequest("world", true));
        assertEquals(EditOutcome.PREVIEW, preview.outcome());
        assertNull(preview.edit());
        assertTrue(history(editor, "world").isEmpty());

        platform.nextChanges = 0;
        SetBlocks.Result noChange = set(editor, cuboidSetRequest("world", false));
        assertEquals(EditOutcome.NO_CHANGE, noChange.outcome());
        assertNull(noChange.edit());
        assertTrue(history(editor, "world").isEmpty());
    }

    @Test
    void validatesAndPreservesConciseUnicodeEditLabels() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        WorldEditService editor = editor(platform, 20);
        String decomposed = "Build cafe\u0301 roof";

        EditRecord retained =
                set(editor, cuboidSetRequest("world", false, decomposed, null)).edit();

        assertEquals(decomposed, retained.label());
        assertEquals(decomposed, history(editor, "world").getFirst().label());
        String maximum = "\ud83d\udee0".repeat(120);
        assertEquals(
                maximum,
                set(editor, cuboidSetRequest("world", false, maximum, null)).edit().label());

        for (String invalidLabel :
                List.of(
                        "",
                        " leading",
                        "trailing ",
                        "\ufeffleading",
                        "trailing\ufeff",
                        "line\nfeed",
                        "control\u0085character",
                        "line\u2028separator",
                        "x".repeat(121))) {
            OperationException failure =
                    assertThrows(
                            OperationException.class,
                            () ->
                                    set(
                                            editor,
                                            cuboidSetRequest("world", false, invalidLabel, null)));
            assertEquals(OperationFailure.INVALID_REQUEST, failure.failure());
            assertEquals(
                    new ErrorDetails.InvalidRequest.InvalidValue("label"),
                    failure.details().orElseThrow());
        }
    }

    @Test
    void appliesTheTighterPerCallChangedBlockCeiling() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 3;
        WorldEditService editor =
                new WorldEditService(
                        platform,
                        100,
                        10,
                        MAX_BLOCK_STATE_PATTERNS,
                        MAX_PALETTE_ENTRIES,
                        5,
                        history(5));

        OperationException limited =
                assertThrows(
                        OperationException.class,
                        () -> set(editor, cuboidSetRequest("world", false, "Limited edit", 2)));
        assertEquals(OperationFailure.CHANGE_LIMIT_EXCEEDED, limited.failure());
        assertEquals(new ErrorDetails.ChangeLimitExceeded(2), limited.details().orElseThrow());
        assertTrue(history(editor, "world").isEmpty());

        set(editor, cuboidSetRequest("world", false, "Server-limited edit", 99));
        assertEquals(5, platform.lastMaxChangedBlocks);

        int resolveCalls = platform.resolveCalls;
        OperationException invalid =
                assertThrows(
                        OperationException.class,
                        () -> set(editor, cuboidSetRequest("world", false, "Invalid limit", 0)));
        assertEquals(OperationFailure.INVALID_REQUEST, invalid.failure());
        assertEquals(resolveCalls, platform.resolveCalls);
    }

    @Test
    void undoesAnExactNewestFirstBatchAndReturnsItsRecords() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        WorldEditService editor = editor(platform, 3);
        EditRecord first = set(editor, cuboidSetRequest("world", false, "First edit", null)).edit();
        EditRecord second =
                set(editor, cuboidSetRequest("world", false, "Second edit", null)).edit();
        EditRecord third = set(editor, cuboidSetRequest("world", false, "Third edit", null)).edit();

        UndoEdits.Completed result =
                (UndoEdits.Completed)
                        undo(
                                editor,
                                "world",
                                List.of(third.editId(), second.editId(), first.editId()));

        assertEquals("world", result.world());
        assertEquals(List.of(third, second, first), result.undoneEdits());
        assertEquals(CALL_ID, result.undoCallId());
        assertEquals(List.of(3, 2, 1), platform.undoneIds);
        assertTrue(history(editor, "world").isEmpty());
        assertTrue(platform.createdUndos.stream().allMatch(undo -> undo.closed));
    }

    @Test
    void validatesTheWholeUndoPrefixBeforeRestoringAnything() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        WorldEditService editor = editor(platform, 3);
        EditRecord first = set(editor, cuboidSetRequest("world", false)).edit();
        EditRecord second = set(editor, cuboidSetRequest("world", false)).edit();
        EditRecord third = set(editor, cuboidSetRequest("world", false)).edit();

        assertFailure(
                OperationFailure.EDIT_NOT_LATEST,
                () -> undo(editor, "world", List.of(third.editId(), first.editId())));
        assertFailure(
                OperationFailure.EDIT_NOT_LATEST,
                () -> undo(editor, "world", List.of(second.editId(), third.editId())));
        assertFailure(
                OperationFailure.EDIT_NOT_FOUND,
                () -> undo(editor, "world", List.of(third.editId(), UUID.randomUUID())));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () -> undo(editor, "world", List.of(third.editId(), third.editId())));

        assertTrue(platform.undoneIds.isEmpty());
        assertEquals(List.of(third, second, first), history(editor, "world"));
    }

    @Test
    void batchUndoReportsAndConsumesOnlyTheSuccessfulPrefix() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        WorldEditService editor = editor(platform, 3);
        EditRecord first = set(editor, cuboidSetRequest("world", false)).edit();
        EditRecord second = set(editor, cuboidSetRequest("world", false)).edit();
        EditRecord third = set(editor, cuboidSetRequest("world", false)).edit();
        platform.failUndoId = 2;

        UndoEdits.Partial partial =
                (UndoEdits.Partial)
                        undo(
                                editor,
                                "world",
                                List.of(third.editId(), second.editId(), first.editId()));

        assertEquals(List.of(third), partial.undoneEdits());
        assertEquals(java.util.Optional.of(second.editId()), partial.failure().editId());
        assertEquals(List.of(3), platform.undoneIds);
        List<EditRecord> remaining = history(editor, "world");
        assertEquals(
                List.of(second.editId(), first.editId()),
                remaining.stream().map(EditRecord::editId).toList());
        assertEquals(EditStatus.RECOVERY_REQUIRED, remaining.getFirst().status());
        assertTrue(platform.createdUndos.get(2).closed);
        assertFalse(platform.createdUndos.get(1).closed);
        assertFalse(platform.createdUndos.get(0).closed);

        platform.failUndoId = null;
        undo(editor, "world", List.of(second.editId(), first.editId()));
        assertTrue(history(editor, "world").isEmpty());
    }

    @Test
    void boundedHistoryEvictsOldestAndListsNewestFirst() throws Exception {
        FakePlatform boundedPlatform = new FakePlatform();
        boundedPlatform.nextChanges = 1;
        WorldEditService bounded =
                new WorldEditService(
                        boundedPlatform,
                        100,
                        10,
                        MAX_BLOCK_STATE_PATTERNS,
                        MAX_PALETTE_ENTRIES,
                        100,
                        history(2));
        EditRecord first = set(bounded, cuboidSetRequest("world", false)).edit();
        EditRecord second = set(bounded, cuboidSetRequest("world", false)).edit();
        EditRecord third = set(bounded, cuboidSetRequest("world", false)).edit();

        assertEquals(List.of(third, second), history(bounded, "world"));
        assertFailure(
                OperationFailure.EDIT_NOT_LATEST, () -> undo(bounded, "world", second.editId()));
        assertFailure(
                OperationFailure.EDIT_NOT_FOUND, () -> undo(bounded, "world", first.editId()));
        undo(bounded, "world", third.editId());
        undo(bounded, "world", second.editId());

        assertEquals(List.of(3, 2), boundedPlatform.undoneIds);
        assertFailure(
                OperationFailure.EDIT_NOT_FOUND, () -> undo(bounded, "world", second.editId()));
    }

    @Test
    void failedUndoRemainsNewestAndSuccessfulUndoRemovesIt() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 7;
        WorldEditService editor = editor(platform, 3);
        EditRecord edit = set(editor, cuboidSetRequest("world", false)).edit();
        platform.failUndo = true;

        UndoEdits.Partial partial = (UndoEdits.Partial) undo(editor, "world", edit.editId());
        assertEquals(java.util.Optional.of(edit.editId()), partial.failure().editId());
        assertEquals(EditStatus.RECOVERY_REQUIRED, history(editor, "world").getFirst().status());
        platform.failUndo = false;
        UndoEdits.Completed result = (UndoEdits.Completed) undo(editor, "world", edit.editId());

        assertEquals(7, result.undoneEdits().getFirst().changedBlockCount());
        assertEquals(List.of(1), platform.undoneIds);
        assertFailure(OperationFailure.EDIT_NOT_FOUND, () -> undo(editor, "world", edit.editId()));
    }

    @Test
    void worldInvalidationClearsHistory() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        WorldEditService editor = editor(platform, 3);
        EditRecord edit = set(editor, cuboidSetRequest("world", false)).edit();

        editor.invalidateWorld(WORLD_ID);

        assertFailure(OperationFailure.EDIT_NOT_FOUND, () -> undo(editor, "world", edit.editId()));
    }

    @Test
    void invalidationDuringAnEditPreventsStaleHistory() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.blockWorld = WORLD_ID;
        WorldEditService editor = editor(platform, 3);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var edit = executor.submit(() -> set(editor, cuboidSetRequest("world", false)));
            platform.entered.await();
            editor.invalidateWorld(WORLD_ID);
            platform.release.countDown();
            OperationException failure =
                    (OperationException)
                            assertThrows(java.util.concurrent.ExecutionException.class, edit::get)
                                    .getCause();
            assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
            assertTrue(failure.editId().isPresent());
            assertEquals(
                    new ErrorDetails.WorldUnavailable.RolledBack(),
                    failure.details().orElseThrow());
            assertTrue(failure.getMessage().contains("was rolled back"));
            assertTrue(failure.getMessage().contains(failure.editId().orElseThrow().toString()));
        }

        assertEquals(List.of(1), platform.undoneIds);
        assertTrue(platform.lastUndo.closed);
        assertTrue(history(editor, "world").isEmpty());
    }

    @Test
    void invalidationRetriesAnUnretainedRecoveryBeforeClosingIt() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.blockWorld = WORLD_ID;
        platform.failWithRecovery = true;
        WorldEditService editor = editor(platform, 3);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var edit = executor.submit(() -> set(editor, cuboidSetRequest("world", false)));
            platform.entered.await();
            editor.invalidateWorld(WORLD_ID);
            platform.release.countDown();
            OperationException failure =
                    (OperationException)
                            assertThrows(java.util.concurrent.ExecutionException.class, edit::get)
                                    .getCause();

            assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
            assertTrue(failure.editId().isPresent());
            assertEquals(
                    new ErrorDetails.WorldUnavailable.RolledBack(),
                    failure.details().orElseThrow());
            assertTrue(failure.getMessage().contains("unretained recovery was rolled back"));
        }

        assertEquals(1, platform.preparedRollbackCalls);
        assertEquals(List.of(1), platform.undoneIds);
        assertTrue(platform.lastUndo.closed);
        assertTrue(history(editor, "world").isEmpty());
    }

    @Test
    void invalidationRollbackFailureReturnsTheUnrecoveredEditId() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.blockWorld = WORLD_ID;
        platform.failUndo = true;
        WorldEditService editor = editor(platform, 3);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var edit = executor.submit(() -> set(editor, cuboidSetRequest("world", false)));
            platform.entered.await();
            editor.invalidateWorld(WORLD_ID);
            platform.release.countDown();
            OperationException failure =
                    (OperationException)
                            assertThrows(java.util.concurrent.ExecutionException.class, edit::get)
                                    .getCause();

            assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
            assertTrue(failure.editId().isPresent());
            assertEquals(
                    new ErrorDetails.WorldUnavailable.RollbackFailed(),
                    failure.details().orElseThrow());
            assertTrue(failure.getMessage().contains("rollback failed"));
            assertTrue(failure.getMessage().contains(failure.editId().orElseThrow().toString()));
        }

        assertTrue(platform.undoneIds.isEmpty());
        assertTrue(platform.lastUndo.closed);
        assertTrue(history(editor, "world").isEmpty());
    }

    @Test
    void serializesSameWorldWithoutBlockingDifferentWorlds() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.blockWorld = WORLD_ID;
        WorldEditService editor = editor(platform, 3);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> set(editor, cuboidSetRequest("world", false)));
            platform.entered.await();

            assertFailure(
                    OperationFailure.WORLD_BUSY,
                    () -> set(editor, cuboidSetRequest("world", false)));
            SetBlocks.Result other = set(editor, cuboidSetRequest("other", false));
            assertEquals("other", other.world());

            platform.release.countDown();
            first.get();
        }
    }

    @Test
    void closeRejectsNewCoordinationAndClosesPlatform() {
        FakePlatform platform = new FakePlatform();
        WorldEditService editor = editor(platform, 3);

        editor.close();
        editor.close();

        assertTrue(platform.stopping);
        assertTrue(platform.closed);
        assertFailure(
                OperationFailure.WORLD_UNAVAILABLE,
                () -> set(editor, cuboidSetRequest("world", false)));
    }

    @Test
    void shutdownDoesNotClosePlatformUntilActiveEditQuiesces() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.blockWorld = WORLD_ID;
        WorldEditService editor = editor(platform, 3);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var edit = executor.submit(() -> set(editor, cuboidSetRequest("world", false)));
            platform.entered.await();

            assertFalse(editor.closeIfQuiescent());
            assertTrue(platform.stopping);
            assertFalse(platform.closed);

            platform.release.countDown();
            OperationException failure =
                    (OperationException)
                            assertThrows(java.util.concurrent.ExecutionException.class, edit::get)
                                    .getCause();
            assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
        }

        assertEquals(1, platform.preparedRollbackCalls);
        assertEquals(List.of(1), platform.undoneIds);
        assertTrue(platform.lastUndo.closed);
        assertTrue(editor.closeIfQuiescent());
        assertTrue(platform.closed);
    }

    @Test
    void requestListsAreDefensiveCopies() {
        List<DestinationPaletteEntry> palette = new ArrayList<>(palette());
        List<List<DestinationPaletteEntry>> palettes = new ArrayList<>(List.of(palette));
        List<Placement> placements = new ArrayList<>(List.of(placement(0, 0, 0)));
        List<Run> runs = new ArrayList<>(List.of(new Run(0, 1, 0, 0, 2, 0, 0)));
        SetBlocks.Request request =
                new SetBlocks.Request(
                        "world",
                        position(0, 0, 0),
                        palettes,
                        placements,
                        runs,
                        13,
                        false,
                        "Check immutable request",
                        null);

        palette.clear();
        palettes.clear();
        placements.clear();
        runs.clear();

        assertEquals(1, request.palettes().size());
        assertEquals(1, request.palettes().getFirst().size());
        assertEquals(1, request.placements().size());
        assertEquals(1, request.runs().size());
        assertThrows(UnsupportedOperationException.class, () -> request.palettes().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> request.palettes().getFirst().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.placements().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.runs().clear());
    }

    private static WorldEditService editor(FakePlatform platform, int history) {
        return new WorldEditService(
                platform,
                100,
                10,
                MAX_BLOCK_STATE_PATTERNS,
                MAX_PALETTE_ENTRIES,
                100,
                history(Math.max(1, history)));
    }

    private static DirtConfig.EditHistory history(int perWorld) {
        return new DirtConfig.EditHistory(perWorld, Math.max(perWorld, 10), 1_000);
    }

    private static SetBlocks.Result set(WorldEditService editor, SetBlocks.Request request)
            throws OperationException {
        return editor.setBlocks(request, CALL_ID);
    }

    private static ReplaceRegionBlocks.Result replace(
            WorldEditService editor, ReplaceRegionBlocks.Request request)
            throws OperationException {
        return editor.replaceRegionBlocks(request, CALL_ID);
    }

    private static List<EditRecord> history(WorldEditService editor, String world)
            throws OperationException {
        return editor.getEditHistory(new GetEditHistory.Request(world)).edits();
    }

    private static UndoEdits.Result undo(WorldEditService editor, String world, UUID editId)
            throws OperationException {
        return undo(editor, world, List.of(editId));
    }

    private static UndoEdits.Result undo(WorldEditService editor, String world, List<UUID> editIds)
            throws OperationException {
        return editor.undoEdits(new UndoEdits.Request(world, editIds), CALL_ID);
    }

    private static SetBlocks.Request cuboidSetRequest(String world, boolean dryRun) {
        return cuboidSetRequest(world, dryRun, "Build test cuboid", null);
    }

    private static SetBlocks.Request cuboidSetRequest(
            String world, boolean dryRun, String label, Integer maxChangedBlocks) {
        return new SetBlocks.Request(
                world,
                position(0, 0, 0),
                palettes(),
                List.of(),
                List.of(new Run(0, 0, 0, 0, 1, 1, 1)),
                13,
                dryRun,
                label,
                maxChangedBlocks);
    }

    private static List<DestinationPaletteEntry> palette() {
        return List.of(new DestinationPaletteEntry("minecraft:stone", null));
    }

    private static List<List<DestinationPaletteEntry>> palettes() {
        return List.of(palette());
    }

    private static SetBlocks.Request setRequest(
            String world, List<Placement> placements, boolean dryRun) {
        return new SetBlocks.Request(
                world,
                position(0, 0, 0),
                palettes(),
                placements,
                List.of(),
                13,
                dryRun,
                "Place test blocks",
                null);
    }

    private static Placement placement(int x, int y, int z) {
        return new Placement(0, x, y, z);
    }

    private static BlockPosition position(int x, int y, int z) {
        return new BlockPosition(x, y, z);
    }

    private static void assertFailure(OperationFailure failure, ThrowingOperation operation) {
        OperationException exception = assertThrows(OperationException.class, operation::run);
        assertEquals(failure, exception.failure());
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static final class FakePlatform implements EditPlatform {
        private final Map<String, FakeWorld> worlds = new HashMap<>();
        private final List<Integer> undoneIds = new ArrayList<>();
        private final List<FakeUndo> createdUndos = new ArrayList<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private int resolveCalls;
        private int prepareCalls;
        private long nextMatches;
        private long nextChanges;
        private int nextUndoId;
        private UUID blockWorld;
        private boolean failExecution;
        private boolean failWithRecovery;
        private boolean failClose;
        private boolean failUndo;
        private Integer failUndoId;
        private boolean failPreparedAccess;
        private boolean caseInsensitiveWorldLookup;
        private boolean stopping;
        private boolean closed;
        private int executeCalls;
        private int preparedBlockCountDelta;
        private int preparedRollbackCalls;
        private int undoCountDelta;
        private int lastMaxChangedBlocks;
        private SetBlockGeometry lastSetGeometry;
        private FakePrepared lastPrepared;
        private FakeUndo lastUndo;

        private FakePlatform() {
            this.worlds.put("world", new FakeWorld(WORLD_ID, "world"));
            this.worlds.put("other", new FakeWorld(OTHER_WORLD_ID, "other"));
        }

        @Override
        public WorldHandle resolveWorld(String worldName) throws OperationException {
            this.resolveCalls++;
            FakeWorld world = this.worlds.get(worldName);
            if (world == null && this.caseInsensitiveWorldLookup) {
                world =
                        this.worlds.entrySet().stream()
                                .filter(entry -> entry.getKey().equalsIgnoreCase(worldName))
                                .map(Map.Entry::getValue)
                                .findFirst()
                                .orElse(null);
            }
            if (world == null) {
                throw new OperationException(
                        OperationFailure.WORLD_NOT_FOUND,
                        "World is not loaded: " + worldName,
                        new ErrorDetails.WorldNotFound(worldName));
            }
            return world;
        }

        @Override
        public PreparedReplace prepareReplace(
                WorldHandle world, ReplaceRegionBlocks.Request request, Cuboid region) {
            return prepared(world, region.volume());
        }

        @Override
        public PreparedSet prepareSet(
                WorldHandle world, SetBlocks.Request request, SetBlockGeometry geometry) {
            this.lastSetGeometry = geometry;
            return prepared(world, geometry.blockCount());
        }

        private FakePrepared prepared(WorldHandle world, long count) {
            this.prepareCalls++;
            this.lastPrepared = new FakePrepared(world, Math.toIntExact(count));
            return this.lastPrepared;
        }

        @Override
        public EditResult replace(
                PreparedReplace prepared,
                Cuboid region,
                boolean dryRun,
                int maxChangedBlocks,
                MutationAdmission admission)
                throws OperationException {
            return execute((FakePrepared) prepared, dryRun, maxChangedBlocks, admission);
        }

        @Override
        public EditResult set(
                PreparedSet prepared,
                boolean dryRun,
                int maxChangedBlocks,
                MutationAdmission admission)
                throws OperationException {
            return execute((FakePrepared) prepared, dryRun, maxChangedBlocks, admission);
        }

        private EditResult execute(
                FakePrepared prepared,
                boolean dryRun,
                int maxChangedBlocks,
                MutationAdmission admission)
                throws OperationException {
            this.executeCalls++;
            this.lastMaxChangedBlocks = maxChangedBlocks;
            prepared.executedBeforeClose = !prepared.closed;
            if (this.nextChanges > maxChangedBlocks) {
                throw new OperationException(
                        OperationFailure.CHANGE_LIMIT_EXCEEDED,
                        "edit exceeds test change limit",
                        new ErrorDetails.ChangeLimitExceeded(maxChangedBlocks));
            }
            if (this.failExecution) {
                throw new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        "edit failed",
                        new ErrorDetails.WorldUnavailable.OperationFailed());
            }
            if (!dryRun && this.nextChanges > 0) {
                admission.beforeMutation();
            }
            if (prepared.world.id().equals(this.blockWorld)) {
                this.entered.countDown();
                try {
                    this.release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new OperationException(
                            OperationFailure.WORLD_UNAVAILABLE,
                            "interrupted",
                            new ErrorDetails.WorldUnavailable.Interrupted(),
                            exception);
                }
            }
            if (this.failWithRecovery) {
                this.lastUndo = new FakeUndo(++this.nextUndoId, this.nextChanges);
                this.createdUndos.add(this.lastUndo);
                throw new EditRecoveryException(
                        "rollback failed",
                        new ErrorDetails.WorldUnavailable.RollbackFailed(),
                        new IllegalStateException("edit failed"),
                        this.lastUndo);
            }
            this.lastUndo =
                    this.nextChanges > 0 && !dryRun
                            ? new FakeUndo(
                                    ++this.nextUndoId, this.nextChanges + this.undoCountDelta)
                            : null;
            if (this.lastUndo != null) {
                this.createdUndos.add(this.lastUndo);
            }
            return new EditResult(this.nextMatches, this.nextChanges, this.lastUndo);
        }

        @Override
        public void undo(WorldHandle world, UndoToken undo) throws OperationException {
            if (this.stopping) {
                throw new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        "world editing is stopping",
                        new ErrorDetails.WorldUnavailable.Stopping());
            }
            applyUndo(undo);
        }

        private void applyUndo(UndoToken undo) throws OperationException {
            int undoId = ((FakeUndo) undo).id();
            if (this.failUndo || this.failUndoId != null && this.failUndoId == undoId) {
                throw new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        "undo failed",
                        new ErrorDetails.WorldUnavailable.OperationFailed());
            }
            this.undoneIds.add(undoId);
        }

        @Override
        public void rollbackPrepared(PreparedOperation prepared, UndoToken undo)
                throws OperationException {
            this.preparedRollbackCalls++;
            this.applyUndo(undo);
        }

        @Override
        public void beginStopping() {
            this.stopping = true;
        }

        @Override
        public void close() {
            this.closed = true;
        }

        private final class FakePrepared implements PreparedReplace, PreparedSet {
            private final WorldHandle world;
            private final int count;
            private boolean executedBeforeClose;
            private boolean closed;

            private FakePrepared(WorldHandle world, int count) {
                this.world = world;
                this.count = count;
            }

            @Override
            public List<String> sourcePatterns() {
                requirePreparedAccess();
                return List.of("canonical:source");
            }

            @Override
            public List<DestinationPaletteEntry> destinationPalette() {
                requirePreparedAccess();
                return List.of(new DestinationPaletteEntry("canonical:destination", null));
            }

            @Override
            public List<List<DestinationPaletteEntry>> palettes() {
                requirePreparedAccess();
                return List.of(destinationPalette());
            }

            @Override
            public int blockCount() {
                requirePreparedAccess();
                return this.count + preparedBlockCountDelta;
            }

            @Override
            public void close() {
                this.closed = true;
                if (failClose) {
                    throw new IllegalStateException("close failed");
                }
            }

            private void requirePreparedAccess() {
                if (failPreparedAccess) {
                    throw new IllegalStateException("prepared metadata failed");
                }
            }
        }
    }

    private record FakeWorld(UUID id, String name) implements EditPlatform.WorldHandle {}

    private static final class FakeUndo implements EditPlatform.UndoToken {
        private final int id;
        private final long changedBlockCount;
        private boolean closed;

        private FakeUndo(int id, long changedBlockCount) {
            this.id = id;
            this.changedBlockCount = changedBlockCount;
        }

        private int id() {
            return this.id;
        }

        @Override
        public long changedBlockCount() {
            return this.changedBlockCount;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }
}
