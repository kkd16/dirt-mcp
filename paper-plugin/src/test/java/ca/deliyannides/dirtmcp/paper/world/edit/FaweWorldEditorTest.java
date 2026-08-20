package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

final class FaweWorldEditorTest {
    private static final UUID WORLD_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_WORLD_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void replaceNormalizesBoundsAndUsesCanonicalPreparedValues() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextMatches = 9;
        platform.nextChanges = 4;
        FaweWorldEditor editor = editor(platform, 10);

        ReplaceRegionBlocks.Result result =
                editor.replaceRegionBlocks(
                        new ReplaceRegionBlocks.Request(
                                "world",
                                position(5, 4, 3),
                                position(1, 2, 0),
                                List.of("minecraft:stone"),
                                palette(),
                                17,
                                false));

        assertEquals(position(1, 2, 0), result.bounds().min());
        assertEquals(position(5, 4, 3), result.bounds().max());
        assertEquals(List.of("canonical:source"), result.sourceBlockStatePatterns());
        assertEquals(
                List.of(new DestinationPaletteEntry("canonical:destination", null)),
                result.destinationPalette());
        assertEquals(9, result.matchedBlockCount());
        assertEquals(4, result.changedBlockCount());
        assertTrue(platform.lastPrepared.closed);
        assertTrue(platform.lastPrepared.executedBeforeClose);
    }

    @Test
    void fillReturnsVolumeAndSetReturnsUnchangedCount() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 2;
        FaweWorldEditor editor = editor(platform, 10);

        FillRegion.Result fill = editor.fillRegion(fillRequest("world", false));
        SetBlocks.Result set =
                editor.setBlocks(
                        new SetBlocks.Request(
                                "world",
                                List.of(change(0, 0, 0), change(1, 0, 0), change(2, 0, 0)),
                                false));

        assertEquals(8, fill.volume());
        assertEquals(2, fill.changedBlockCount());
        assertEquals(3, set.blockCount());
        assertEquals(2, set.changedBlockCount());
        assertEquals(1, set.unchangedBlockCount());
    }

    @Test
    void rejectsRegionTouchingTooManyChunksBeforeResolvingWorld() {
        FakePlatform platform = new FakePlatform();
        FaweWorldEditor editor = new FaweWorldEditor(platform, 1_000, 2, 3);

        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () ->
                                editor.fillRegion(
                                        new FillRegion.Request(
                                                "world",
                                                position(0, 0, 0),
                                                position(32, 0, 0),
                                                palette(),
                                                0,
                                                false)));

        assertEquals(OperationFailure.REGION_TOO_LARGE, exception.failure());
        assertEquals(0, platform.resolveCalls);
        assertEquals(0, platform.prepareCalls);
    }

    @Test
    void rejectsSparseChunkLimitBeforeWorldAndStatePreparation() {
        FakePlatform platform = new FakePlatform();
        FaweWorldEditor editor = new FaweWorldEditor(platform, 100, 1, 3);

        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () ->
                                editor.setBlocks(
                                        new SetBlocks.Request(
                                                "world",
                                                List.of(change(0, 0, 0), change(16, 0, 0)),
                                                false)));

        assertEquals(OperationFailure.REGION_TOO_LARGE, exception.failure());
        assertEquals(0, platform.resolveCalls);
        assertEquals(0, platform.prepareCalls);
    }

    @Test
    void validatesRequestsWithoutLeakingRuntimeExceptions() {
        FaweWorldEditor editor = editor(new FakePlatform(), 3);

        assertFailure(OperationFailure.INVALID_REQUEST, () -> editor.fillRegion(null));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        editor.fillRegion(
                                new FillRegion.Request(
                                        "world", null, position(0, 0, 0), palette(), 0, false)));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () -> editor.setBlocks(new SetBlocks.Request("world", List.of(), false)));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () -> editor.undoLastEdit(new UndoLastEdit.Request(" ")));
        FaweWorldEditor smallEditor = new FaweWorldEditor(new FakePlatform(), 3, 10, 3);
        assertFailure(
                OperationFailure.REGION_TOO_LARGE,
                () ->
                        smallEditor.setBlocks(
                                new SetBlocks.Request(
                                        "world",
                                        List.of(
                                                change(0, 0, 0),
                                                change(1, 0, 0),
                                                change(2, 0, 0),
                                                change(3, 0, 0)),
                                        false)));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        editor.replaceRegionBlocks(
                                new ReplaceRegionBlocks.Request(
                                        "world",
                                        position(0, 0, 0),
                                        position(0, 0, 0),
                                        java.util.Collections.nCopies(65, "minecraft:stone"),
                                        palette(),
                                        0,
                                        false)));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        editor.fillRegion(
                                new FillRegion.Request(
                                        "world",
                                        position(0, 0, 0),
                                        position(0, 0, 0),
                                        List.of(new DestinationPaletteEntry("minecraft:stone", 40)),
                                        0,
                                        false)));
        assertFailure(
                OperationFailure.INVALID_REQUEST,
                () ->
                        editor.fillRegion(
                                new FillRegion.Request(
                                        "world",
                                        position(0, 0, 0),
                                        position(0, 0, 0),
                                        List.of(
                                                new DestinationPaletteEntry("minecraft:stone", 50),
                                                new DestinationPaletteEntry(
                                                        "minecraft:dirt", null)),
                                        0,
                                        false)));
    }

    @Test
    void closesPreparedResourcesWhenExecutionFails() {
        FakePlatform platform = new FakePlatform();
        platform.failExecution = true;
        FaweWorldEditor editor = editor(platform, 3);

        assertThrows(
                OperationException.class, () -> editor.fillRegion(fillRequest("world", false)));

        assertNotNull(platform.lastPrepared);
        assertTrue(platform.lastPrepared.closed);
    }

    @Test
    void retainsCommittedHistoryWhenPreparedResourceCleanupFails() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.failClose = true;
        FaweWorldEditor editor = editor(platform, 3);

        assertThrows(
                OperationException.class, () -> editor.fillRegion(fillRequest("world", false)));
        platform.failClose = false;

        UndoLastEdit.Result result = editor.undoLastEdit(new UndoLastEdit.Request("world"));
        assertEquals(1, result.changedBlockCount());
    }

    @Test
    void retainsRecoveryHistoryWhenAutomaticRollbackFails() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 3;
        platform.failWithRecovery = true;
        FaweWorldEditor editor = editor(platform, 0);

        assertThrows(
                EditRecoveryException.class, () -> editor.fillRegion(fillRequest("world", false)));
        platform.failWithRecovery = false;

        assertFailure(
                OperationFailure.WORLD_BUSY, () -> editor.fillRegion(fillRequest("world", false)));

        UndoLastEdit.Result result = editor.undoLastEdit(new UndoLastEdit.Request("world"));
        assertEquals(3, result.changedBlockCount());
        assertEquals(3, editor.fillRegion(fillRequest("world", false)).changedBlockCount());
    }

    @Test
    void dryRunsAndNoOpsNeverEnterUndoHistory() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.returnUndoForDryRun = true;
        FaweWorldEditor editor = editor(platform, 3);

        editor.fillRegion(fillRequest("world", true));
        assertFailure(
                OperationFailure.NOTHING_TO_UNDO,
                () -> editor.undoLastEdit(new UndoLastEdit.Request("world")));

        platform.nextChanges = 0;
        editor.fillRegion(fillRequest("world", false));
        assertFailure(
                OperationFailure.NOTHING_TO_UNDO,
                () -> editor.undoLastEdit(new UndoLastEdit.Request("world")));
    }

    @Test
    void zeroCapacityStoresNothingAndBoundedHistoryEvictsOldest() throws Exception {
        FakePlatform zeroPlatform = new FakePlatform();
        zeroPlatform.nextChanges = 1;
        FaweWorldEditor zero = new FaweWorldEditor(zeroPlatform, 100, 10, 0);
        zero.fillRegion(fillRequest("world", false));
        assertFailure(
                OperationFailure.NOTHING_TO_UNDO,
                () -> zero.undoLastEdit(new UndoLastEdit.Request("world")));

        FakePlatform boundedPlatform = new FakePlatform();
        boundedPlatform.nextChanges = 1;
        FaweWorldEditor bounded = new FaweWorldEditor(boundedPlatform, 100, 10, 2);
        bounded.fillRegion(fillRequest("world", false));
        bounded.fillRegion(fillRequest("world", false));
        bounded.fillRegion(fillRequest("world", false));

        bounded.undoLastEdit(new UndoLastEdit.Request("world"));
        bounded.undoLastEdit(new UndoLastEdit.Request("world"));

        assertEquals(List.of(3, 2), boundedPlatform.undoneIds);
        assertFailure(
                OperationFailure.NOTHING_TO_UNDO,
                () -> bounded.undoLastEdit(new UndoLastEdit.Request("world")));
    }

    @Test
    void failedUndoRemainsNewestAndSuccessfulUndoRemovesIt() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 7;
        FaweWorldEditor editor = editor(platform, 3);
        editor.fillRegion(fillRequest("world", false));
        platform.failUndo = true;

        assertThrows(
                OperationException.class,
                () -> editor.undoLastEdit(new UndoLastEdit.Request("world")));
        platform.failUndo = false;
        UndoLastEdit.Result result = editor.undoLastEdit(new UndoLastEdit.Request("world"));

        assertEquals(7, result.changedBlockCount());
        assertEquals(List.of(1), platform.undoneIds);
        assertFailure(
                OperationFailure.NOTHING_TO_UNDO,
                () -> editor.undoLastEdit(new UndoLastEdit.Request("world")));
    }

    @Test
    void worldInvalidationClearsHistory() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        FaweWorldEditor editor = editor(platform, 3);
        editor.fillRegion(fillRequest("world", false));

        editor.invalidateWorld(WORLD_ID);

        assertFailure(
                OperationFailure.NOTHING_TO_UNDO,
                () -> editor.undoLastEdit(new UndoLastEdit.Request("world")));
    }

    @Test
    void invalidationDuringAnEditPreventsStaleHistory() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.blockWorld = WORLD_ID;
        FaweWorldEditor editor = editor(platform, 3);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var edit = executor.submit(() -> editor.fillRegion(fillRequest("world", false)));
            platform.entered.await();
            editor.invalidateWorld(WORLD_ID);
            platform.release.countDown();
            edit.get();
        }

        assertFailure(
                OperationFailure.NOTHING_TO_UNDO,
                () -> editor.undoLastEdit(new UndoLastEdit.Request("world")));
    }

    @Test
    void serializesSameWorldWithoutBlockingDifferentWorlds() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.blockWorld = WORLD_ID;
        FaweWorldEditor editor = editor(platform, 3);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> editor.fillRegion(fillRequest("world", false)));
            platform.entered.await();

            assertFailure(
                    OperationFailure.WORLD_BUSY,
                    () -> editor.fillRegion(fillRequest("world", false)));
            FillRegion.Result other = editor.fillRegion(fillRequest("other", false));
            assertEquals("other", other.world());

            platform.release.countDown();
            first.get();
        }
    }

    @Test
    void closeRejectsNewCoordinationAndClosesPlatform() {
        FakePlatform platform = new FakePlatform();
        FaweWorldEditor editor = editor(platform, 3);

        editor.close();
        editor.close();

        assertTrue(platform.closed);
        assertFailure(
                OperationFailure.WORLD_UNAVAILABLE,
                () -> editor.fillRegion(fillRequest("world", false)));
    }

    @Test
    void shutdownDoesNotClosePlatformUntilActiveEditQuiesces() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.nextChanges = 1;
        platform.blockWorld = WORLD_ID;
        FaweWorldEditor editor = editor(platform, 3);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var edit = executor.submit(() -> editor.fillRegion(fillRequest("world", false)));
            platform.entered.await();

            assertFalse(editor.closeIfQuiescent());
            assertFalse(platform.closed);

            platform.release.countDown();
            edit.get();
        }

        assertTrue(editor.closeIfQuiescent());
        assertTrue(platform.closed);
    }

    @Test
    void requestListsAreDefensiveCopies() {
        List<BlockChange> changes = new ArrayList<>();
        changes.add(change(0, 0, 0));
        SetBlocks.Request request = new SetBlocks.Request("world", changes, false);

        changes.clear();

        assertEquals(1, request.changes().size());
        assertThrows(UnsupportedOperationException.class, () -> request.changes().clear());
    }

    private static FaweWorldEditor editor(FakePlatform platform, int history) {
        return new FaweWorldEditor(platform, 100, 10, history);
    }

    private static FillRegion.Request fillRequest(String world, boolean dryRun) {
        return new FillRegion.Request(
                world, position(0, 0, 0), position(1, 1, 1), palette(), 13, dryRun);
    }

    private static List<DestinationPaletteEntry> palette() {
        return List.of(new DestinationPaletteEntry("minecraft:stone", null));
    }

    private static BlockChange change(int x, int y, int z) {
        return new BlockChange(position(x, y, z), "minecraft:stone");
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
        private boolean returnUndoForDryRun;
        private boolean closed;
        private FakePrepared lastPrepared;

        private FakePlatform() {
            this.worlds.put("world", new FakeWorld(WORLD_ID, "world"));
            this.worlds.put("other", new FakeWorld(OTHER_WORLD_ID, "other"));
        }

        @Override
        public WorldHandle resolveWorld(String worldName) throws OperationException {
            this.resolveCalls++;
            FakeWorld world = this.worlds.get(worldName);
            if (world == null) {
                throw new OperationException(
                        OperationFailure.WORLD_NOT_FOUND, "World is not loaded: " + worldName);
            }
            return world;
        }

        @Override
        public PreparedReplace prepareReplace(
                WorldHandle world, ReplaceRegionBlocks.Request request, Cuboid region) {
            return prepared(world, request.destinationPalette(), region.volume());
        }

        @Override
        public PreparedFill prepareFill(
                WorldHandle world, FillRegion.Request request, Cuboid region) {
            return prepared(world, request.destinationPalette(), region.volume());
        }

        @Override
        public PreparedSet prepareSet(
                WorldHandle world, SetBlocks.Request request, List<ChunkPosition> touchedChunks) {
            return prepared(world, List.of(), request.changes().size());
        }

        private FakePrepared prepared(
                WorldHandle world, List<DestinationPaletteEntry> palette, long count) {
            this.prepareCalls++;
            this.lastPrepared = new FakePrepared(world, palette, Math.toIntExact(count));
            return this.lastPrepared;
        }

        @Override
        public EditResult replace(PreparedReplace prepared, Cuboid region, boolean dryRun)
                throws OperationException {
            return execute((FakePrepared) prepared, dryRun);
        }

        @Override
        public EditResult fill(PreparedFill prepared, Cuboid region, boolean dryRun)
                throws OperationException {
            return execute((FakePrepared) prepared, dryRun);
        }

        @Override
        public EditResult set(PreparedSet prepared, boolean dryRun) throws OperationException {
            return execute((FakePrepared) prepared, dryRun);
        }

        private EditResult execute(FakePrepared prepared, boolean dryRun)
                throws OperationException {
            prepared.executedBeforeClose = !prepared.closed;
            if (this.failExecution) {
                throw new OperationException(OperationFailure.WORLD_UNAVAILABLE, "edit failed");
            }
            if (this.failWithRecovery) {
                throw new EditRecoveryException(
                        "rollback failed",
                        new IllegalStateException("edit failed"),
                        new FakeUndo(++this.nextUndoId, this.nextChanges));
            }
            if (prepared.world.id().equals(this.blockWorld)) {
                this.entered.countDown();
                try {
                    this.release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new OperationException(
                            OperationFailure.WORLD_UNAVAILABLE, "interrupted", exception);
                }
            }
            FakeUndo undo =
                    this.nextChanges > 0 && (!dryRun || this.returnUndoForDryRun)
                            ? new FakeUndo(++this.nextUndoId, this.nextChanges)
                            : null;
            return new EditResult(this.nextMatches, this.nextChanges, undo);
        }

        @Override
        public void undo(WorldHandle world, UndoToken undo) throws OperationException {
            if (this.failUndo) {
                throw new OperationException(OperationFailure.WORLD_UNAVAILABLE, "undo failed");
            }
            this.undoneIds.add(((FakeUndo) undo).id());
        }

        @Override
        public void close() {
            this.closed = true;
        }

        private final class FakePrepared implements PreparedReplace, PreparedFill, PreparedSet {
            private final WorldHandle world;
            private final List<DestinationPaletteEntry> palette;
            private final int count;
            private boolean executedBeforeClose;
            private boolean closed;

            private FakePrepared(
                    WorldHandle world, List<DestinationPaletteEntry> palette, int count) {
                this.world = world;
                this.palette = palette;
                this.count = count;
            }

            @Override
            public List<String> sourcePatterns() {
                return List.of("canonical:source");
            }

            @Override
            public List<DestinationPaletteEntry> destinationPalette() {
                return List.of(new DestinationPaletteEntry("canonical:destination", null));
            }

            @Override
            public int blockCount() {
                return this.count;
            }

            @Override
            public void close() throws OperationException {
                this.closed = true;
                if (failClose) {
                    throw new OperationException(
                            OperationFailure.WORLD_UNAVAILABLE, "close failed");
                }
            }
        }
    }

    private record FakeWorld(UUID id, String name) implements EditPlatform.WorldHandle {}

    private record FakeUndo(int id, long changedBlockCount) implements EditPlatform.UndoToken {}
}
