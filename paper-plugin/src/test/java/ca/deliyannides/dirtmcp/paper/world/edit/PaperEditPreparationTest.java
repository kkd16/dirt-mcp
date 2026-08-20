package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import com.sk89q.worldedit.function.pattern.Pattern;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PaperEditPreparationTest {
    private static final ChunkPosition CHUNK = new ChunkPosition(0, 0);

    @Test
    void buildsLargeSetChangeListOffMainAndReleasesTicketsWhenConstructionFails() throws Exception {
        int placementCount = 10_000;
        TrackingMainThread mainThread = new TrackingMainThread();
        FakeTicketWorld world = new FakeTicketWorld(mainThread);
        ChunkTicketManager tickets = new ChunkTicketManager(mainThread);
        List<SetBlocks.Placement> placements = new ArrayList<>(placementCount);
        for (int index = 0; index < placementCount; index++) {
            placements.add(new SetBlocks.Placement(0, index, 0, 0));
        }
        SetBlocks.Request request =
                new SetBlocks.Request(
                        "world",
                        new BlockPosition(0, 64, 0),
                        List.of(List.of(new DestinationPaletteEntry("minecraft:stone", null))),
                        placements,
                        0,
                        false);
        AtomicInteger positionsRead = new AtomicInteger();
        List<BlockPosition> resolvedPositions =
                new AbstractList<>() {
                    @Override
                    public BlockPosition get(int index) {
                        assertFalse(mainThread.inCall);
                        positionsRead.incrementAndGet();
                        if (index == placementCount - 1) {
                            throw new IllegalStateException("simulated construction failure");
                        }
                        return new BlockPosition(index, 64, 0);
                    }

                    @Override
                    public int size() {
                        return placementCount;
                    }
                };
        Pattern pattern = position -> null;
        PaperEditPreparation.PreparedSetResources resources =
                mainThread.call(
                        () ->
                                new PaperEditPreparation.PreparedSetResources(
                                        List.of(
                                                new PaperEditPreparation.PreparedPalette(
                                                        request.palettes().getFirst(), pattern)),
                                        List.of(CHUNK),
                                        tickets.acquire(world, List.of(CHUNK), "Set-blocks edit")));

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                PaperEditPreparation.finishPreparedSet(
                                        null, request, resolvedPositions, resources));

        assertEquals("simulated construction failure", failure.getMessage());
        assertEquals(placementCount, positionsRead.get());
        assertEquals(1, world.ticketsAdded);
        assertEquals(1, world.ticketsRemoved);
        assertFalse(mainThread.inCall);
        tickets.close();
    }

    @Test
    void interruptionAfterMainThreadPreparationSkipsConstructionAndReleasesTickets()
            throws Exception {
        TrackingMainThread mainThread = new TrackingMainThread();
        FakeTicketWorld world = new FakeTicketWorld(mainThread);
        ChunkTicketManager tickets = new ChunkTicketManager(mainThread);
        SetBlocks.Request request = request(List.of(new SetBlocks.Placement(0, 0, 0, 0)));
        Pattern pattern = position -> null;
        PaperEditPreparation.PreparedSetResources resources =
                mainThread.call(
                        () ->
                                new PaperEditPreparation.PreparedSetResources(
                                        List.of(
                                                new PaperEditPreparation.PreparedPalette(
                                                        request.palettes().getFirst(), pattern)),
                                        List.of(CHUNK),
                                        tickets.acquire(world, List.of(CHUNK), "Set-blocks edit")));
        AtomicInteger positionsRead = new AtomicInteger();
        List<BlockPosition> resolvedPositions =
                new AbstractList<>() {
                    @Override
                    public BlockPosition get(int index) {
                        positionsRead.incrementAndGet();
                        return new BlockPosition(0, 64, 0);
                    }

                    @Override
                    public int size() {
                        return 1;
                    }
                };

        Thread.currentThread().interrupt();
        try {
            OperationException failure =
                    assertThrows(
                            OperationException.class,
                            () ->
                                    PaperEditPreparation.finishPreparedSet(
                                            null, request, resolvedPositions, resources));

            assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
            assertEquals(0, positionsRead.get());
            assertEquals(1, world.ticketsRemoved);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            tickets.close();
        }
    }

    private static SetBlocks.Request request(List<SetBlocks.Placement> placements) {
        return new SetBlocks.Request(
                "world",
                new BlockPosition(0, 64, 0),
                List.of(List.of(new DestinationPaletteEntry("minecraft:stone", null))),
                placements,
                0,
                false);
    }

    private static final class TrackingMainThread implements MainThread {
        private boolean inCall;

        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            assertFalse(this.inCall);
            assertFalse(Thread.currentThread().isInterrupted());
            this.inCall = true;
            try {
                return action.get();
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new PaperMainThreadException("Main-thread action failed", failure);
            } finally {
                this.inCall = false;
            }
        }

        @Override
        public void close() {}
    }

    private static final class FakeTicketWorld implements ChunkTicketManager.TicketWorld {
        private final UUID id = UUID.randomUUID();
        private final TrackingMainThread mainThread;
        private int ticketsAdded;
        private int ticketsRemoved;

        private FakeTicketWorld(TrackingMainThread mainThread) {
            this.mainThread = mainThread;
        }

        @Override
        public UUID id() {
            return this.id;
        }

        @Override
        public boolean isChunkLoaded(ChunkPosition chunk) {
            assertTrue(this.mainThread.inCall);
            return true;
        }

        @Override
        public boolean addTicket(ChunkPosition chunk) {
            assertTrue(this.mainThread.inCall);
            this.ticketsAdded++;
            return true;
        }

        @Override
        public void removeTicket(ChunkPosition chunk) {
            assertTrue(this.mainThread.inCall);
            this.ticketsRemoved++;
        }
    }
}
