package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
final class EditCoordinatorTest {
    private static final UUID WORLD_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID OTHER_WORLD_ID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID THIRD_WORLD_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CALL_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

    @Test
    void invalidatingIdleWorldDisposesHistoryAndPrunesState() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(3, 4, 10);
        TestUndo first = new TestUndo(2);
        TestUndo second = new TestUndo(3);
        TestUndo other = new TestUndo(4);
        remember(coordinator, edit(WORLD_ID, "world", first, EditStatus.COMMITTED));
        remember(coordinator, edit(WORLD_ID, "world", second, EditStatus.COMMITTED));
        RetainedEdit otherEdit = edit(OTHER_WORLD_ID, "other", other, EditStatus.COMMITTED);
        remember(coordinator, otherEdit);

        coordinator.invalidate(WORLD_ID);

        assertTrue(first.closed);
        assertTrue(second.closed);
        assertFalse(other.closed);
        assertEquals(1, coordinator.trackedWorldCount());
        assertEquals(1, coordinator.retainedEditCount());
        assertEquals(4, coordinator.retainedChangedBlockCount());
        try (EditCoordinator.Lease history = coordinator.enterHistory(OTHER_WORLD_ID, "other")) {
            assertEquals(java.util.List.of(otherEdit.record()), history.history());
        }
        coordinator.close();
        assertTrue(other.closed);
    }

    @Test
    void invalidationWaitsForAnActiveLeaseBeforeDisposal() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 4, 10);
        TestUndo first = new TestUndo(1);
        EditCoordinator.Lease active = coordinator.enterMutation(WORLD_ID, "world");
        active.reserveHistory(first.changedBlockCount());
        assertTrue(active.remember(edit(WORLD_ID, "world", first, EditStatus.COMMITTED)));

        coordinator.invalidate(WORLD_ID);

        assertFalse(first.closed);
        OperationException unavailable =
                assertThrows(
                        OperationException.class,
                        () -> coordinator.enterMutation(WORLD_ID, "world"));
        assertEquals(OperationFailure.WORLD_UNAVAILABLE, unavailable.failure());
        assertEquals(
                new ErrorDetails.WorldUnavailable.WorldUnloaded("world"),
                unavailable.details().orElseThrow());
        active.close();
        assertTrue(first.closed);
        assertEquals(0, coordinator.trackedWorldCount());
    }

    @Test
    void perWorldGlobalAndChangedBlockLimitsEvictOldestCommittedEntries()
            throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 2, 2);
        TestUndo first = new TestUndo(1);
        TestUndo second = new TestUndo(1);
        TestUndo third = new TestUndo(2);
        RetainedEdit firstEdit = edit(WORLD_ID, "world", first, EditStatus.COMMITTED);
        RetainedEdit secondEdit = edit(OTHER_WORLD_ID, "other", second, EditStatus.COMMITTED);
        RetainedEdit thirdEdit = edit(WORLD_ID, "world", third, EditStatus.COMMITTED);

        remember(coordinator, firstEdit);
        remember(coordinator, secondEdit);
        remember(coordinator, thirdEdit);

        assertTrue(first.closed);
        assertTrue(second.closed);
        assertFalse(third.closed);
        assertEquals(1, coordinator.trackedWorldCount());
        assertEquals(1, coordinator.retainedEditCount());
        assertEquals(2, coordinator.retainedChangedBlockCount());
        try (EditCoordinator.Lease history = coordinator.enterHistory(WORLD_ID, "world")) {
            assertEquals(java.util.List.of(thirdEdit.record()), history.history());
        }
    }

    @Test
    void globalAdmissionProtectsOnlyTheEditBeingUndone() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 2, 2);
        TestUndo olderUndo = new TestUndo(1);
        TestUndo newestUndo = new TestUndo(1);
        RetainedEdit older = edit(WORLD_ID, "world", olderUndo, EditStatus.COMMITTED);
        RetainedEdit newest = edit(WORLD_ID, "world", newestUndo, EditStatus.COMMITTED);
        remember(coordinator, older);
        remember(coordinator, newest);

        try (EditCoordinator.Lease undo = coordinator.enterUndo(WORLD_ID, "world");
                EditCoordinator.Lease other = coordinator.enterMutation(OTHER_WORLD_ID, "other")) {
            other.reserveHistory(1);

            assertTrue(olderUndo.closed);
            assertFalse(newestUndo.closed);
            assertEquals(java.util.List.of(newest.record()), undo.history());
        }

        coordinator.close();
        assertTrue(newestUndo.closed);
    }

    @Test
    void evictionDisposesOutsideMonitorWhileKeepingReplacementCapacityReserved() throws Exception {
        EditCoordinator coordinator = new EditCoordinator(1, 1, 1);
        BlockingUndo evictedUndo = new BlockingUndo(1);
        RetainedEdit evicted = edit(WORLD_ID, "world", evictedUndo, EditStatus.COMMITTED);
        remember(coordinator, evicted);
        TestUndo replacementUndo = new TestUndo(1);
        RetainedEdit replacement =
                edit(OTHER_WORLD_ID, "other", replacementUndo, EditStatus.COMMITTED);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var replacementTask =
                    executor.submit(
                            () -> {
                                remember(coordinator, replacement);
                                return null;
                            });
            try {
                assertTrue(evictedUndo.closeStarted.await(2, TimeUnit.SECONDS));

                var otherWorldAdmission =
                        executor.submit(
                                () -> {
                                    try (EditCoordinator.Lease lease =
                                            coordinator.enterMutation(THIRD_WORLD_ID, "third")) {
                                        try {
                                            lease.reserveHistory(1);
                                            return null;
                                        } catch (OperationException failure) {
                                            return failure.failure();
                                        }
                                    }
                                });
                assertEquals(
                        OperationFailure.HISTORY_CAPACITY_EXCEEDED,
                        otherWorldAdmission.get(2, TimeUnit.SECONDS));
                assertFalse(replacementTask.isDone());
                assertEquals(1, coordinator.trackedWorldCount());
                assertEquals(0, coordinator.retainedEditCount());
                assertEquals(0, coordinator.retainedChangedBlockCount());
            } finally {
                evictedUndo.allowClose.countDown();
            }
            replacementTask.get(2, TimeUnit.SECONDS);
        }

        assertTrue(evictedUndo.closed);
        assertFalse(replacementUndo.closed);
        assertEquals(1, coordinator.trackedWorldCount());
        assertEquals(1, coordinator.retainedEditCount());
        assertEquals(1, coordinator.retainedChangedBlockCount());
        coordinator.close();
        assertTrue(replacementUndo.closed);
    }

    @Test
    void invalidationDisposesOutsideMonitorAndCompletesBeforeReturning() throws Exception {
        EditCoordinator coordinator = new EditCoordinator(2, 4, 10);
        BlockingUndo undo = new BlockingUndo(1);
        remember(coordinator, edit(WORLD_ID, "world", undo, EditStatus.COMMITTED));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var invalidation =
                    executor.submit(
                            () -> {
                                coordinator.invalidate(WORLD_ID);
                                return null;
                            });
            try {
                assertTrue(undo.closeStarted.await(2, TimeUnit.SECONDS));
                var otherWorldHistory =
                        executor.submit(
                                () -> {
                                    try (EditCoordinator.Lease lease =
                                            coordinator.enterHistory(OTHER_WORLD_ID, "other")) {
                                        return lease.history();
                                    }
                                });

                assertTrue(otherWorldHistory.get(2, TimeUnit.SECONDS).isEmpty());
                assertFalse(invalidation.isDone());
                assertFalse(coordinator.isQuiescent());
                assertEquals(0, coordinator.trackedWorldCount());
                assertEquals(0, coordinator.retainedEditCount());
            } finally {
                undo.allowClose.countDown();
            }
            invalidation.get(2, TimeUnit.SECONDS);
        }

        assertTrue(undo.closed);
        assertTrue(coordinator.isQuiescent());
    }

    @Test
    void shutdownDisposesOutsideMonitorAndCompletesBeforeReturning() throws Exception {
        EditCoordinator coordinator = new EditCoordinator(2, 4, 10);
        BlockingUndo undo = new BlockingUndo(1);
        remember(coordinator, edit(WORLD_ID, "world", undo, EditStatus.COMMITTED));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var shutdown =
                    executor.submit(
                            () -> {
                                coordinator.close();
                                return null;
                            });
            try {
                assertTrue(undo.closeStarted.await(2, TimeUnit.SECONDS));
                var rejectedCoordination =
                        executor.submit(
                                () -> {
                                    try (EditCoordinator.Lease ignored =
                                            coordinator.enterHistory(OTHER_WORLD_ID, "other")) {
                                        return null;
                                    } catch (OperationException failure) {
                                        return failure.failure();
                                    }
                                });

                assertEquals(
                        OperationFailure.WORLD_UNAVAILABLE,
                        rejectedCoordination.get(2, TimeUnit.SECONDS));
                assertFalse(shutdown.isDone());
                assertFalse(coordinator.isQuiescent());
                assertEquals(0, coordinator.trackedWorldCount());
                assertEquals(0, coordinator.retainedEditCount());
            } finally {
                undo.allowClose.countDown();
            }
            shutdown.get(2, TimeUnit.SECONDS);
        }

        assertTrue(undo.closed);
        assertTrue(coordinator.isQuiescent());
    }

    @Test
    void successfulUndoDisposesOutsideMonitorAndCompletesBeforeReturning() throws Exception {
        EditCoordinator coordinator = new EditCoordinator(2, 4, 10);
        BlockingUndo undo = new BlockingUndo(1);
        RetainedEdit retained = edit(WORLD_ID, "world", undo, EditStatus.COMMITTED);
        remember(coordinator, retained);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var removal =
                    executor.submit(
                            () -> {
                                try (EditCoordinator.Lease lease =
                                        coordinator.enterUndo(WORLD_ID, "world")) {
                                    lease.removeLatest(retained);
                                }
                                return null;
                            });
            try {
                assertTrue(undo.closeStarted.await(2, TimeUnit.SECONDS));
                var otherWorldHistory =
                        executor.submit(
                                () -> {
                                    try (EditCoordinator.Lease lease =
                                            coordinator.enterHistory(OTHER_WORLD_ID, "other")) {
                                        return lease.history();
                                    }
                                });

                assertTrue(otherWorldHistory.get(2, TimeUnit.SECONDS).isEmpty());
                assertFalse(removal.isDone());
                assertEquals(0, coordinator.retainedEditCount());
            } finally {
                undo.allowClose.countDown();
            }
            removal.get(2, TimeUnit.SECONDS);
        }

        assertTrue(undo.closed);
        assertEquals(0, coordinator.trackedWorldCount());
    }

    @Test
    void emptyIdleWorldStatesArePruned() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 4, 10);

        try (EditCoordinator.Lease history = coordinator.enterHistory(WORLD_ID, "world")) {
            assertEquals(1, coordinator.trackedWorldCount());
            assertTrue(history.history().isEmpty());
        }
        assertEquals(0, coordinator.trackedWorldCount());

        try (EditCoordinator.Lease mutation = coordinator.enterMutation(WORLD_ID, "world")) {
            mutation.reserveHistory(2);
            assertEquals(1, coordinator.trackedWorldCount());
        }
        assertEquals(0, coordinator.trackedWorldCount());
        assertEquals(0, coordinator.retainedEditCount());
        assertEquals(0, coordinator.retainedChangedBlockCount());
    }

    @Test
    void recoveryIsVisiblePinnedAndBlocksNewMutations() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(1, 1, 2);
        TestUndo undo = new TestUndo(2);
        RetainedEdit recovery = edit(WORLD_ID, "world", undo, EditStatus.RECOVERY_REQUIRED);
        try (EditCoordinator.Lease lease = coordinator.enterMutation(WORLD_ID, "world")) {
            lease.reserveHistory(undo.changedBlockCount());
            assertTrue(lease.rememberRecovery(recovery));
        }

        assertEquals(1, coordinator.retainedEditCount());
        assertEquals(2, coordinator.retainedChangedBlockCount());
        assertEquals(
                OperationFailure.WORLD_BUSY,
                assertThrows(
                                OperationException.class,
                                () -> coordinator.enterMutation(WORLD_ID, "world"))
                        .failure());
        try (EditCoordinator.Lease history = coordinator.enterHistory(WORLD_ID, "world")) {
            assertEquals(java.util.List.of(recovery.record()), history.history());
        }
        try (EditCoordinator.Lease lease = coordinator.enterUndo(WORLD_ID, "world")) {
            lease.removeLatest(lease.latest());
        }
        assertTrue(undo.closed);
    }

    @Test
    void failedUndoCanMarkTheNewestEntryForRecovery() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 2, 10);
        RetainedEdit committed = edit(WORLD_ID, "world", new TestUndo(1), EditStatus.COMMITTED);
        remember(coordinator, committed);

        try (EditCoordinator.Lease lease = coordinator.enterUndo(WORLD_ID, "world")) {
            lease.markRecoveryRequired(committed);
            assertEquals(EditStatus.RECOVERY_REQUIRED, lease.latest().record().status());
        }
    }

    @Test
    void globalEvictionCannotCloseAnEntryHeldByAnotherWorldUndo() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(1, 1, 10);
        TestUndo protectedUndo = new TestUndo(1);
        RetainedEdit protectedEdit = edit(WORLD_ID, "world", protectedUndo, EditStatus.COMMITTED);
        remember(coordinator, protectedEdit);

        TestUndo incomingUndo = new TestUndo(1);
        RetainedEdit incoming = edit(OTHER_WORLD_ID, "other", incomingUndo, EditStatus.COMMITTED);
        try (EditCoordinator.Lease undo = coordinator.enterUndo(WORLD_ID, "world")) {
            assertEquals(protectedEdit, undo.latest());
            try (EditCoordinator.Lease mutation =
                    coordinator.enterMutation(OTHER_WORLD_ID, "other")) {
                assertEquals(
                        OperationFailure.HISTORY_CAPACITY_EXCEEDED,
                        assertThrows(
                                        OperationException.class,
                                        () ->
                                                mutation.reserveHistory(
                                                        incomingUndo.changedBlockCount()))
                                .failure());
            }
            assertFalse(protectedUndo.closed);
            assertFalse(incomingUndo.closed);
        }
        incomingUndo.close();
    }

    @Test
    void concurrentWorldsCannotOvercommitTheChangedBlockBudget() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 2, 3);
        try (EditCoordinator.Lease first = coordinator.enterMutation(WORLD_ID, "world");
                EditCoordinator.Lease second = coordinator.enterMutation(OTHER_WORLD_ID, "other")) {
            first.reserveHistory(2);

            assertEquals(
                    OperationFailure.HISTORY_CAPACITY_EXCEEDED,
                    assertThrows(OperationException.class, () -> second.reserveHistory(2))
                            .failure());
        }

        try (EditCoordinator.Lease retry = coordinator.enterMutation(OTHER_WORLD_ID, "other")) {
            retry.reserveHistory(2);
        }
        assertEquals(0, coordinator.retainedEditCount());
        assertEquals(0, coordinator.retainedChangedBlockCount());
    }

    @Test
    void closeRejectsNewWorkAndDefersActiveCleanup() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 4, 10);
        TestUndo undo = new TestUndo(1);
        EditCoordinator.Lease active = coordinator.enterMutation(WORLD_ID, "world");
        active.reserveHistory(undo.changedBlockCount());
        active.remember(edit(WORLD_ID, "world", undo, EditStatus.COMMITTED));

        coordinator.close();

        assertFalse(coordinator.isQuiescent());
        assertFalse(undo.closed);
        OperationException stopping =
                assertThrows(
                        OperationException.class,
                        () -> coordinator.enterHistory(WORLD_ID, "world"));
        assertEquals(OperationFailure.WORLD_UNAVAILABLE, stopping.failure());
        assertEquals(
                new ErrorDetails.WorldUnavailable.Stopping(), stopping.details().orElseThrow());
        active.close();
        assertTrue(coordinator.isQuiescent());
        assertTrue(undo.closed);
        assertEquals(0, coordinator.trackedWorldCount());
    }

    @Test
    void activeLeasesDistinguishWorldInvalidationFromShutdownBeforeReservation()
            throws OperationException {
        EditCoordinator invalidatedCoordinator = new EditCoordinator(2, 4, 10);
        EditCoordinator.Lease invalidated = invalidatedCoordinator.enterMutation(WORLD_ID, "world");
        invalidatedCoordinator.invalidate(WORLD_ID);

        OperationException unavailable =
                assertThrows(OperationException.class, () -> invalidated.reserveHistory(1));
        assertEquals(
                new ErrorDetails.WorldUnavailable.WorldUnloaded("world"),
                unavailable.details().orElseThrow());
        invalidated.close();

        EditCoordinator stoppingCoordinator = new EditCoordinator(2, 4, 10);
        EditCoordinator.Lease stopping = stoppingCoordinator.enterMutation(WORLD_ID, "world");
        stoppingCoordinator.close();

        OperationException shutdown =
                assertThrows(OperationException.class, () -> stopping.reserveHistory(1));
        assertEquals(
                new ErrorDetails.WorldUnavailable.Stopping(), shutdown.details().orElseThrow());
        stopping.close();
    }

    @Test
    void invalidatedLeaseCannotExposeOrRetainUndoHistory() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 4, 10);
        TestUndo undo = new TestUndo(2);
        RetainedEdit recovery = edit(WORLD_ID, "world", undo, EditStatus.RECOVERY_REQUIRED);
        EditCoordinator.Lease stale = coordinator.enterMutation(WORLD_ID, "world");
        stale.reserveHistory(undo.changedBlockCount());

        coordinator.invalidate(WORLD_ID);

        assertNull(stale.latest());
        assertFalse(stale.contains(recovery.record().editId()));
        assertTrue(stale.history().isEmpty());
        stale.markRecoveryRequired(recovery);
        stale.removeLatest(recovery);
        assertFalse(stale.rememberRecovery(recovery));
        assertFalse(undo.closed);
        stale.close();
        stale.close();
        undo.close();
        assertTrue(undo.closed);
        assertEquals(0, coordinator.trackedWorldCount());
        assertEquals(0, coordinator.retainedEditCount());
    }

    @Test
    void historyReservationsArePositiveBoundedAndSingleUse() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 4, 10);
        try (EditCoordinator.Lease mutation = coordinator.enterMutation(WORLD_ID, "world")) {
            assertEquals(
                    OperationFailure.HISTORY_CAPACITY_EXCEEDED,
                    assertThrows(OperationException.class, () -> mutation.reserveHistory(0))
                            .failure());
            assertEquals(
                    OperationFailure.HISTORY_CAPACITY_EXCEEDED,
                    assertThrows(OperationException.class, () -> mutation.reserveHistory(11))
                            .failure());
            mutation.reserveHistory(1);
            assertThrows(IllegalStateException.class, () -> mutation.reserveHistory(1));
        }

        try (EditCoordinator.Lease history = coordinator.enterHistory(WORLD_ID, "world")) {
            assertThrows(IllegalStateException.class, () -> history.reserveHistory(1));
        }

        EditCoordinator.Lease released = coordinator.enterMutation(WORLD_ID, "world");
        released.close();
        assertThrows(IllegalStateException.class, () -> released.reserveHistory(1));
    }

    @Test
    void retentionPrevalidationLeavesCandidateUnownedAndHistoryUnchanged()
            throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 4, 10);
        TestUndo existingUndo = new TestUndo(1);
        RetainedEdit existing = edit(WORLD_ID, "world", existingUndo, EditStatus.COMMITTED);
        remember(coordinator, existing);

        TestUndo candidateUndo = new TestUndo(2);
        RetainedEdit candidate = edit(WORLD_ID, "world", candidateUndo, EditStatus.COMMITTED);
        try (EditCoordinator.Lease lease = coordinator.enterMutation(WORLD_ID, "world")) {
            lease.reserveHistory(1);

            assertThrows(IllegalStateException.class, () -> lease.remember(candidate));
            assertEquals(java.util.List.of(existing.record()), lease.history());
            assertEquals(1, coordinator.retainedEditCount());
            assertEquals(1, coordinator.retainedChangedBlockCount());
            assertFalse(candidateUndo.closed);
        }

        coordinator.close();
        assertTrue(existingUndo.closed);
        assertFalse(candidateUndo.closed);
        candidateUndo.close();
    }

    private static void remember(EditCoordinator coordinator, RetainedEdit edit)
            throws OperationException {
        try (EditCoordinator.Lease lease =
                coordinator.enterMutation(edit.record().worldId(), edit.record().world())) {
            lease.reserveHistory(edit.record().changedBlockCount());
            assertTrue(lease.remember(edit));
        }
    }

    private static RetainedEdit edit(
            UUID worldId, String world, EditPlatform.UndoToken undo, EditStatus status) {
        BlockPosition position = new BlockPosition(1, 2, 3);
        return new RetainedEdit(
                new EditRecord(
                        UUID.randomUUID(),
                        CALL_ID,
                        EditOperation.SET_BLOCKS,
                        world,
                        worldId,
                        new BlockBounds(position, position),
                        undo.changedBlockCount(),
                        Instant.parse("2026-08-19T12:00:00Z"),
                        status),
                undo);
    }

    private static final class TestUndo implements EditPlatform.UndoToken {
        private final long changedBlockCount;
        private boolean closed;

        private TestUndo(long changedBlockCount) {
            this.changedBlockCount = changedBlockCount;
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

    private static final class BlockingUndo implements EditPlatform.UndoToken {
        private final long changedBlockCount;
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);
        private volatile boolean closed;

        private BlockingUndo(long changedBlockCount) {
            this.changedBlockCount = changedBlockCount;
        }

        @Override
        public long changedBlockCount() {
            return this.changedBlockCount;
        }

        @Override
        public void close() {
            this.closeStarted.countDown();
            try {
                this.allowClose.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while closing test undo", failure);
            }
            this.closed = true;
        }
    }
}
