package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
final class EditCoordinatorTest {
    private static final UUID WORLD_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Test
    void invalidatingIdleWorldPrunesItsStateImmediately() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2);
        try (EditCoordinator.Lease ignored = coordinator.enter(WORLD_ID, "world")) {
            assertEquals(1, coordinator.trackedWorldCount());
        }
        assertEquals(1, coordinator.trackedWorldCount());

        coordinator.invalidate(WORLD_ID);

        assertEquals(0, coordinator.trackedWorldCount());
    }

    @Test
    void invalidatingActiveWorldPrunesAfterLeaseWithoutCreatingASecondLock()
            throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2);
        EditCoordinator.Lease active = coordinator.enter(WORLD_ID, "world");

        coordinator.invalidate(WORLD_ID);
        OperationException busy =
                assertThrows(OperationException.class, () -> coordinator.enter(WORLD_ID, "world"));

        assertEquals(OperationFailure.WORLD_BUSY, busy.failure());
        assertEquals(1, coordinator.trackedWorldCount());
        active.close();
        assertEquals(0, coordinator.trackedWorldCount());

        try (EditCoordinator.Lease replacement = coordinator.enter(WORLD_ID, "world")) {
            assertEquals(1, coordinator.trackedWorldCount());
        }
    }

    @Test
    void invalidationCannotRetainHistoryFromAnActiveGeneration() throws OperationException {
        EditCoordinator coordinator = new EditCoordinator(2);
        EditCoordinator.Lease active = coordinator.enter(WORLD_ID, "world");
        active.remember(() -> 4);

        coordinator.invalidate(WORLD_ID);

        assertNull(active.latestUndo());
        active.remember(() -> 5);
        assertNull(active.latestUndo());
        active.close();
        assertEquals(0, coordinator.trackedWorldCount());
    }
}
