package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class ChunkTicketManager implements AutoCloseable {
    private final MainThread mainThread;
    private final Map<TicketKey, TicketState> tickets = new HashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    ChunkTicketManager(MainThread mainThread) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    Lease acquire(TicketWorld world, List<ChunkPosition> chunks, String operation)
            throws OperationException {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(chunks, "chunks");
        if (this.stopping.get()) {
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE, "World editing is stopping");
        }
        for (ChunkPosition chunk : chunks) {
            if (!world.isChunkLoaded(chunk)) {
                throw new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        operation
                                + " contains an unloaded chunk at "
                                + chunk.x()
                                + ","
                                + chunk.z());
            }
        }

        List<TicketKey> acquired = new ArrayList<>(chunks.size());
        try {
            synchronized (this.tickets) {
                if (this.stopping.get()) {
                    throw new OperationException(
                            OperationFailure.WORLD_UNAVAILABLE, "World editing is stopping");
                }
                for (ChunkPosition chunk : chunks) {
                    TicketKey key = new TicketKey(world.id(), chunk);
                    TicketState state = this.tickets.get(key);
                    if (state == null) {
                        boolean owned = world.addTicket(chunk);
                        state = new TicketState(world, owned);
                        this.tickets.put(key, state);
                    }
                    state.references++;
                    acquired.add(key);
                }
            }
        } catch (RuntimeException | OperationException exception) {
            try {
                releaseOnMainThread(acquired);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        return new Lease(List.copyOf(acquired));
    }

    void beginStopping() {
        this.stopping.set(true);
    }

    @Override
    public void close() {
        beginStopping();
        if (this.closed.get()) {
            return;
        }
        try {
            this.mainThread.run(this::releaseAllOnMainThread);
        } catch (PaperMainThreadException exception) {
            throw new IllegalStateException("Could not release plugin chunk tickets", exception);
        }
        synchronized (this.tickets) {
            if (!this.tickets.isEmpty()) {
                throw new IllegalStateException(
                        "Could not release plugin chunk tickets while edits are still active");
            }
            this.closed.set(true);
        }
    }

    private void release(List<TicketKey> keys) {
        if (keys.isEmpty()) {
            return;
        }
        if (this.stopping.get()) {
            releaseReferencesOnly(keys);
            return;
        }
        try {
            this.mainThread.run(() -> releaseOnMainThread(keys));
        } catch (PaperMainThreadException exception) {
            if (!this.stopping.get()) {
                throw new IllegalStateException(
                        "Could not release plugin chunk tickets", exception);
            }
            releaseReferencesOnly(keys);
        }
    }

    private void releaseOnMainThread(List<TicketKey> keys) {
        synchronized (this.tickets) {
            RuntimeException cleanupFailure = null;
            for (TicketKey key : keys) {
                TicketState state = this.tickets.get(key);
                if (state == null) {
                    continue;
                }
                state.references--;
                if (state.references == 0) {
                    try {
                        if (state.owned) {
                            state.world.removeTicket(key.chunk());
                        }
                        this.tickets.remove(key);
                    } catch (RuntimeException exception) {
                        cleanupFailure = combine(cleanupFailure, exception);
                    }
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    private void releaseAllOnMainThread() {
        synchronized (this.tickets) {
            RuntimeException cleanupFailure = null;
            var iterator = this.tickets.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<TicketKey, TicketState> entry = iterator.next();
                TicketState state = entry.getValue();
                if (state.references > 0) {
                    continue;
                }
                try {
                    if (state.owned) {
                        state.world.removeTicket(entry.getKey().chunk());
                    }
                    iterator.remove();
                } catch (RuntimeException exception) {
                    cleanupFailure = combine(cleanupFailure, exception);
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    private void releaseReferencesOnly(List<TicketKey> keys) {
        synchronized (this.tickets) {
            for (TicketKey key : keys) {
                TicketState state = this.tickets.get(key);
                if (state != null && state.references > 0) {
                    state.references--;
                }
            }
        }
    }

    private static RuntimeException combine(
            RuntimeException accumulated, RuntimeException failure) {
        if (accumulated == null) {
            return failure;
        }
        accumulated.addSuppressed(failure);
        return accumulated;
    }

    interface TicketWorld {
        UUID id();

        boolean isChunkLoaded(ChunkPosition chunk);

        boolean addTicket(ChunkPosition chunk);

        void removeTicket(ChunkPosition chunk);
    }

    final class Lease implements AutoCloseable {
        private final List<TicketKey> keys;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(List<TicketKey> keys) {
            this.keys = keys;
        }

        @Override
        public void close() {
            if (this.released.compareAndSet(false, true)) {
                release(this.keys);
            }
        }
    }

    private record TicketKey(UUID worldId, ChunkPosition chunk) {}

    private static final class TicketState {
        private final TicketWorld world;
        private final boolean owned;
        private int references;

        private TicketState(TicketWorld world, boolean owned) {
            this.world = world;
            this.owned = owned;
        }
    }
}
