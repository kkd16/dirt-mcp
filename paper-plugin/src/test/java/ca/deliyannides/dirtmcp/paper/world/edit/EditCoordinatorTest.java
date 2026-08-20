package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
final class EditCoordinatorTest {
    private static final UUID WORLD_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID OTHER_WORLD_ID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID CALL_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

    @Test
    void invalidatingIdleWorldDisposesHistoryAndPrunesState() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2, 4, 10);
        TestUndo undo = new TestUndo(2);
        try (EditCoordinator.Lease lease = coordinator.enterMutation(WORLD_ID, "world")) {
            lease.reserveHistory(undo.changedBlockCount());
            assertTrue(lease.remember(edit(WORLD_ID, "world", undo, EditStatus.COMMITTED)));
        }

        coordinator.invalidate(WORLD_ID);

        assertTrue(undo.closed);
        assertEquals(0, coordinator.trackedWorldCount());
        assertEquals(0, coordinator.retainedEditCount());
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
        assertEquals(
                OperationFailure.WORLD_UNAVAILABLE,
                assertThrows(
                                OperationException.class,
                                () -> coordinator.enterMutation(WORLD_ID, "world"))
                        .failure());
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
        assertEquals(1, coordinator.retainedEditCount());
        assertEquals(2, coordinator.retainedChangedBlockCount());
        try (EditCoordinator.Lease history = coordinator.enterHistory(WORLD_ID, "world")) {
            assertEquals(java.util.List.of(thirdEdit.record()), history.history());
        }
    }

    @Test
    void recoveryIsVisiblePinnedAndBlocksNewMutations() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(1, 1, 2);
        TestUndo undo = new TestUndo(2);
        RetainedEdit recovery = edit(WORLD_ID, "world", undo, EditStatus.RECOVERY_REQUIRED);
        try (EditCoordinator.Lease lease = coordinator.enterMutation(WORLD_ID, "world")) {
            lease.reserveHistory(undo.changedBlockCount());
            lease.rememberRecovery(recovery);
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
        assertEquals(
                OperationFailure.WORLD_UNAVAILABLE,
                assertThrows(
                                OperationException.class,
                                () -> coordinator.enterHistory(WORLD_ID, "world"))
                        .failure());
        active.close();
        assertTrue(coordinator.isQuiescent());
        assertTrue(undo.closed);
        assertEquals(0, coordinator.trackedWorldCount());
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
        stale.rememberRecovery(recovery);
        assertTrue(undo.closed);
        stale.close();
        stale.close();
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

    private static void remember(EditCoordinator coordinator, RetainedEdit edit)
            throws OperationException {
        try (EditCoordinator.Lease lease =
                coordinator.enterMutation(edit.record().worldId(), edit.record().world())) {
            lease.reserveHistory(edit.record().changedBlockCount());
            assertTrue(lease.remember(edit));
        }
    }

    private static RetainedEdit edit(UUID worldId, String world, TestUndo undo, EditStatus status) {
        BlockPosition position = new BlockPosition(1, 2, 3);
        return new RetainedEdit(
                new EditRecord(
                        UUID.randomUUID(),
                        CALL_ID,
                        EditOperation.FILL_REGION,
                        world,
                        worldId,
                        new BlockBounds(position, position),
                        undo.changedBlockCount(),
                        "2026-08-19T12:00:00Z",
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
}
