package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

final class EditCoordinator implements AutoCloseable {
    private final Map<UUID, WorldState> worlds = new HashMap<>();
    private final int historyCapacity;
    private final AtomicBoolean closed = new AtomicBoolean();

    EditCoordinator(int historyCapacity) {
        if (historyCapacity < 0) {
            throw new IllegalArgumentException("Undo history capacity must be non-negative");
        }
        this.historyCapacity = historyCapacity;
    }

    Lease enter(UUID worldId, String worldName) throws OperationException {
        return enter(worldId, worldName, false);
    }

    Lease enterForUndo(UUID worldId, String worldName) throws OperationException {
        return enter(worldId, worldName, true);
    }

    private Lease enter(UUID worldId, String worldName, boolean allowRecovery)
            throws OperationException {
        WorldState state;
        long generation;
        synchronized (this.worlds) {
            if (this.closed.get()) {
                throw unavailable();
            }
            state = this.worlds.computeIfAbsent(worldId, ignored -> new WorldState());
            if (state.invalidated) {
                throw busy(worldName);
            }
            if (state.recovery != null && !allowRecovery) {
                throw recoveryRequired(worldName);
            }
            state.reservations++;
            generation = state.generation;
        }
        if (!state.lock.tryLock()) {
            releaseReservation(worldId, state);
            throw busy(worldName);
        }
        if (this.closed.get()) {
            state.lock.unlock();
            releaseReservation(worldId, state);
            throw unavailable();
        }
        return new Lease(worldId, state, generation);
    }

    void invalidate(UUID worldId) {
        synchronized (this.worlds) {
            WorldState state = this.worlds.get(worldId);
            if (state != null) {
                state.generation++;
                state.invalidated = true;
                state.history.clear();
                state.recovery = null;
                pruneInvalidated(worldId, state);
            }
        }
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            synchronized (this.worlds) {
                var iterator = this.worlds.entrySet().iterator();
                while (iterator.hasNext()) {
                    WorldState state = iterator.next().getValue();
                    state.generation++;
                    state.invalidated = true;
                    state.history.clear();
                    state.recovery = null;
                    if (state.reservations == 0) {
                        iterator.remove();
                    }
                }
            }
        }
    }

    boolean isQuiescent() {
        synchronized (this.worlds) {
            return this.worlds.values().stream().allMatch(state -> state.reservations == 0);
        }
    }

    int trackedWorldCount() {
        synchronized (this.worlds) {
            return this.worlds.size();
        }
    }

    private void releaseReservation(UUID worldId, WorldState state) {
        synchronized (this.worlds) {
            state.reservations--;
            pruneInvalidated(worldId, state);
        }
    }

    private void pruneInvalidated(UUID worldId, WorldState state) {
        if (state.invalidated && state.reservations == 0) {
            this.worlds.remove(worldId, state);
        }
    }

    private static OperationException busy(String worldName) {
        return new OperationException(
                OperationFailure.WORLD_BUSY,
                "Another Dirt MCP edit is running in world: " + worldName);
    }

    private static OperationException unavailable() {
        return new OperationException(
                OperationFailure.WORLD_UNAVAILABLE, "World editing is stopping");
    }

    private static OperationException recoveryRequired(String worldName) {
        return new OperationException(
                OperationFailure.WORLD_BUSY,
                "The previous failed edit must be undone before editing world: " + worldName);
    }

    final class Lease implements AutoCloseable {
        private final UUID worldId;
        private final WorldState state;
        private final long generation;
        private boolean released;

        private Lease(UUID worldId, WorldState state, long generation) {
            this.worldId = worldId;
            this.state = state;
            this.generation = generation;
        }

        EditPlatform.UndoToken latestUndo() {
            synchronized (worlds) {
                return this.generation == this.state.generation
                        ? (this.state.recovery != null
                                ? this.state.recovery
                                : this.state.history.peekLast())
                        : null;
            }
        }

        void remember(EditPlatform.UndoToken undo) {
            remember(undo, historyCapacity);
        }

        void rememberRecovery(EditPlatform.UndoToken undo) {
            synchronized (worlds) {
                if (undo != null && this.generation == this.state.generation && !closed.get()) {
                    this.state.recovery = undo;
                }
            }
        }

        private void remember(EditPlatform.UndoToken undo, int capacity) {
            synchronized (worlds) {
                if (undo == null
                        || capacity == 0
                        || this.generation != this.state.generation
                        || closed.get()) {
                    return;
                }
                this.state.history.addLast(undo);
                while (this.state.history.size() > capacity) {
                    this.state.history.pollFirst();
                }
            }
        }

        void removeLatest(EditPlatform.UndoToken undo) {
            synchronized (worlds) {
                if (this.generation == this.state.generation) {
                    if (this.state.recovery == undo) {
                        this.state.recovery = null;
                    } else {
                        this.state.history.removeLastOccurrence(undo);
                    }
                }
            }
        }

        @Override
        public void close() {
            if (!this.released) {
                this.released = true;
                this.state.lock.unlock();
                releaseReservation(this.worldId, this.state);
            }
        }
    }

    private static final class WorldState {
        private final ReentrantLock lock = new ReentrantLock();
        private final ArrayDeque<EditPlatform.UndoToken> history = new ArrayDeque<>();
        private EditPlatform.UndoToken recovery;
        private long generation;
        private int reservations;
        private boolean invalidated;
    }
}
