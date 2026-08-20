package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class UndoChunkLoaderTest {
    private static final ChunkPosition FIRST = new ChunkPosition(1, -2);
    private static final ChunkPosition SECOND = new ChunkPosition(3, 4);

    @Test
    void asynchronouslyLoadsExistingChunksBeforeTicketingThem() throws Exception {
        FakeMainThread mainThread = new FakeMainThread();
        FakeWorld world = new FakeWorld(mainThread);
        ChunkTicketManager tickets = new ChunkTicketManager(mainThread);
        UndoChunkLoader loader = new UndoChunkLoader(mainThread, tickets);

        ChunkTicketManager.Lease lease = loader.prepare(world, List.of(FIRST, SECOND));

        assertEquals(List.of(FIRST, SECOND), world.requested);
        assertEquals(Set.of(FIRST, SECOND), world.added);
        assertTrue(world.removed.isEmpty());

        lease.close();
        tickets.close();
        assertEquals(Set.of(FIRST, SECOND), world.removed);
        assertFalse(mainThread.inside);
    }

    @Test
    void refusesToGenerateAMissingChunkAndAddsNoTickets() {
        FakeMainThread mainThread = new FakeMainThread();
        FakeWorld world = new FakeWorld(mainThread);
        world.existing.remove(SECOND);
        ChunkTicketManager tickets = new ChunkTicketManager(mainThread);
        UndoChunkLoader loader = new UndoChunkLoader(mainThread, tickets);

        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () -> loader.prepare(world, List.of(FIRST, SECOND)));

        assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
        assertTrue(failure.getMessage().contains("3,4"));
        assertEquals(List.of(FIRST, SECOND), world.requested);
        assertTrue(world.added.isEmpty());
        tickets.close();
    }

    @Test
    void preservesTheAsyncLoadFailureAsTheCause() {
        FakeMainThread mainThread = new FakeMainThread();
        FakeWorld world = new FakeWorld(mainThread);
        IllegalStateException loadFailure = new IllegalStateException("load failed");
        world.loads.put(SECOND, CompletableFuture.failedFuture(loadFailure));
        ChunkTicketManager tickets = new ChunkTicketManager(mainThread);
        UndoChunkLoader loader = new UndoChunkLoader(mainThread, tickets);

        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () -> loader.prepare(world, List.of(FIRST, SECOND)));

        assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
        assertSame(loadFailure, failure.getCause());
        assertTrue(world.added.isEmpty());
        tickets.close();
    }

    @Test
    void preservesInterruptionWithoutAcquiringTickets() {
        FakeMainThread mainThread = new FakeMainThread();
        FakeWorld world = new FakeWorld(mainThread);
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        world.loads.put(FIRST, pending);
        ChunkTicketManager tickets = new ChunkTicketManager(mainThread);
        UndoChunkLoader loader = new UndoChunkLoader(mainThread, tickets);

        Thread.currentThread().interrupt();
        try {
            OperationException failure =
                    assertThrows(
                            OperationException.class, () -> loader.prepare(world, List.of(FIRST)));

            assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(pending.isCancelled());
            assertTrue(world.added.isEmpty());
        } finally {
            Thread.interrupted();
            tickets.close();
        }
    }

    @Test
    void rechecksWorldIdentityAfterLoadingAndBeforeTicketing() {
        FakeMainThread mainThread = new FakeMainThread();
        FakeWorld world = new FakeWorld(mainThread);
        world.unavailableAtCheck = 2;
        ChunkTicketManager tickets = new ChunkTicketManager(mainThread);
        UndoChunkLoader loader = new UndoChunkLoader(mainThread, tickets);

        OperationException failure =
                assertThrows(OperationException.class, () -> loader.prepare(world, List.of(FIRST)));

        assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
        assertTrue(failure.getMessage().contains("no longer available"));
        assertTrue(world.added.isEmpty());
        tickets.close();
    }

    @Test
    void mapsMainThreadHandoffFailureAndRejectsEmptyChunkLists() {
        FakeMainThread mainThread = new FakeMainThread();
        FakeWorld world = new FakeWorld(mainThread);
        ChunkTicketManager tickets = new ChunkTicketManager(mainThread);
        UndoChunkLoader loader = new UndoChunkLoader(mainThread, tickets);

        assertThrows(IllegalArgumentException.class, () -> loader.prepare(world, List.of()));

        mainThread.nextFailure = new PaperMainThreadException("stopping");
        OperationException failure =
                assertThrows(OperationException.class, () -> loader.prepare(world, List.of(FIRST)));
        assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
        assertTrue(failure.getMessage().contains("Paper's main thread"));
        tickets.close();
    }

    private static final class FakeMainThread implements MainThread {
        private boolean inside;
        private PaperMainThreadException nextFailure;

        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            if (this.nextFailure != null) {
                PaperMainThreadException failure = this.nextFailure;
                this.nextFailure = null;
                throw failure;
            }
            this.inside = true;
            try {
                return action.get();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new PaperMainThreadException("main-thread failure", exception);
            } finally {
                this.inside = false;
            }
        }

        @Override
        public void close() {}
    }

    private static final class FakeWorld implements UndoChunkLoader.UndoWorld {
        private final UUID id = UUID.randomUUID();
        private final FakeMainThread mainThread;
        private final Set<ChunkPosition> existing = new HashSet<>(List.of(FIRST, SECOND));
        private final Set<ChunkPosition> loaded = new HashSet<>();
        private final Set<ChunkPosition> added = new HashSet<>();
        private final Set<ChunkPosition> removed = new HashSet<>();
        private final List<ChunkPosition> requested = new ArrayList<>();
        private final Map<ChunkPosition, CompletableFuture<Boolean>> loads = new HashMap<>();
        private int availabilityChecks;
        private int unavailableAtCheck = Integer.MAX_VALUE;

        private FakeWorld(FakeMainThread mainThread) {
            this.mainThread = mainThread;
        }

        @Override
        public UUID id() {
            return this.id;
        }

        @Override
        public String name() {
            return "world";
        }

        @Override
        public boolean isAvailable() {
            assertOnMainThread();
            this.availabilityChecks++;
            return this.availabilityChecks < this.unavailableAtCheck;
        }

        @Override
        public CompletableFuture<Boolean> loadExistingChunk(ChunkPosition chunk) {
            assertOnMainThread();
            this.requested.add(chunk);
            CompletableFuture<Boolean> configured = this.loads.get(chunk);
            if (configured != null) {
                return configured;
            }
            boolean exists = this.existing.contains(chunk);
            if (exists) {
                this.loaded.add(chunk);
            }
            return CompletableFuture.completedFuture(exists);
        }

        @Override
        public boolean isChunkLoaded(ChunkPosition chunk) {
            assertOnMainThread();
            return this.loaded.contains(chunk);
        }

        @Override
        public boolean addTicket(ChunkPosition chunk) {
            assertOnMainThread();
            this.added.add(chunk);
            return true;
        }

        @Override
        public void removeTicket(ChunkPosition chunk) {
            assertOnMainThread();
            this.removed.add(chunk);
        }

        private void assertOnMainThread() {
            assertTrue(this.mainThread.inside);
        }
    }
}
