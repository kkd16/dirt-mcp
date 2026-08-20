package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ChunkTicketManagerTest {
    private static final ChunkPosition FIRST = new ChunkPosition(1, -2);
    private static final ChunkPosition SECOND = new ChunkPosition(3, 4);

    @Test
    void referenceCountsOwnedTickets() throws Exception {
        FakeMainThread mainThread = new FakeMainThread();
        FakeWorld world = new FakeWorld();
        ChunkTicketManager manager = new ChunkTicketManager(mainThread);

        ChunkTicketManager.Lease first = manager.acquire(world, List.of(FIRST), "Edit");
        ChunkTicketManager.Lease second = manager.acquire(world, List.of(FIRST), "Edit");
        assertEquals(1, world.added.size());

        first.close();
        assertTrue(world.removed.isEmpty());
        second.close();
        second.close();

        assertEquals(Set.of(FIRST), world.removed);
        assertEquals(2, mainThread.runs);
    }

    @Test
    void neverRemovesTicketItDidNotAdd() throws Exception {
        FakeWorld world = new FakeWorld();
        world.addResult = false;
        ChunkTicketManager manager = new ChunkTicketManager(new FakeMainThread());

        manager.acquire(world, List.of(FIRST), "Edit").close();

        assertEquals(Set.of(FIRST), world.added);
        assertTrue(world.removed.isEmpty());
    }

    @Test
    void checksAllChunksBeforeAddingTickets() {
        FakeWorld world = new FakeWorld();
        world.unloaded.add(SECOND);
        ChunkTicketManager manager = new ChunkTicketManager(new FakeMainThread());

        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () -> manager.acquire(world, List.of(FIRST, SECOND), "Sparse edit"));

        assertEquals(OperationFailure.WORLD_UNAVAILABLE, exception.failure());
        assertTrue(exception.getMessage().contains("3,4"));
        assertTrue(world.added.isEmpty());
    }

    @Test
    void rollsBackPartiallyAddedTickets() {
        FakeWorld world = new FakeWorld();
        world.failAdd = SECOND;
        ChunkTicketManager manager = new ChunkTicketManager(new FakeMainThread());

        assertThrows(
                IllegalStateException.class,
                () -> manager.acquire(world, List.of(FIRST, SECOND), "Edit"));

        assertEquals(Set.of(FIRST, SECOND), world.added);
        assertEquals(Set.of(FIRST), world.removed);
    }

    @Test
    void shutdownReleasesEveryOwnedTicketOnceAndRejectsNewLeases() throws Exception {
        FakeMainThread mainThread = new FakeMainThread();
        FakeWorld world = new FakeWorld();
        ChunkTicketManager manager = new ChunkTicketManager(mainThread);
        ChunkTicketManager.Lease lease = manager.acquire(world, List.of(FIRST, SECOND), "Edit");

        manager.close();
        manager.close();
        lease.close();

        assertEquals(Set.of(FIRST, SECOND), world.removed);
        assertEquals(1, mainThread.runs);
        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () -> manager.acquire(world, List.of(FIRST), "Edit"));
        assertEquals(OperationFailure.WORLD_UNAVAILABLE, exception.failure());
    }

    @Test
    void stoppingMakesWorkerLeaseCloseNonBlockingUntilMainThreadCleanup() throws Exception {
        FakeMainThread mainThread = new FakeMainThread();
        FakeWorld world = new FakeWorld();
        ChunkTicketManager manager = new ChunkTicketManager(mainThread);
        ChunkTicketManager.Lease lease = manager.acquire(world, List.of(FIRST), "Edit");

        manager.beginStopping();
        lease.close();

        assertEquals(0, mainThread.runs);
        assertTrue(world.removed.isEmpty());
        assertEquals(
                OperationFailure.WORLD_UNAVAILABLE,
                assertThrows(
                                OperationException.class,
                                () -> manager.acquire(world, List.of(SECOND), "Edit"))
                        .failure());

        manager.close();
        assertEquals(1, mainThread.runs);
        assertEquals(Set.of(FIRST), world.removed);
    }

    private static final class FakeMainThread implements MainThread {
        private int runs;

        @Override
        public <T> T call(CheckedSupplier<T> action) {
            this.runs++;
            try {
                return action.get();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public void close() {}
    }

    private static final class FakeWorld implements ChunkTicketManager.TicketWorld {
        private final UUID id = UUID.randomUUID();
        private final Set<ChunkPosition> unloaded = new HashSet<>();
        private final Set<ChunkPosition> added = new HashSet<>();
        private final Set<ChunkPosition> removed = new HashSet<>();
        private boolean addResult = true;
        private ChunkPosition failAdd;

        @Override
        public UUID id() {
            return this.id;
        }

        @Override
        public boolean isChunkLoaded(ChunkPosition chunk) {
            return !this.unloaded.contains(chunk);
        }

        @Override
        public boolean addTicket(ChunkPosition chunk) {
            assertFalse(this.removed.contains(chunk));
            this.added.add(chunk);
            if (chunk.equals(this.failAdd)) {
                throw new IllegalStateException("ticket failure");
            }
            return this.addResult;
        }

        @Override
        public void removeTicket(ChunkPosition chunk) {
            this.removed.add(chunk);
        }
    }
}
