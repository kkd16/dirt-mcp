package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

final class EditCoordinator implements AutoCloseable {
    private final Map<UUID, WorldState> worlds = new HashMap<>();
    private final LinkedHashMap<UUID, RetainedEdit> retained = new LinkedHashMap<>();
    private final int maxEntriesPerWorld;
    private final int maxEntriesTotal;
    private final long maxRetainedChangedBlocks;
    private final AtomicBoolean closed = new AtomicBoolean();
    private long retainedChangedBlocks;
    private int reservedHistoryEntries;
    private long reservedHistoryChangedBlocks;

    EditCoordinator(int maxEntriesPerWorld, int maxEntriesTotal, long maxRetainedChangedBlocks) {
        if (maxEntriesPerWorld < 1
                || maxEntriesTotal < maxEntriesPerWorld
                || maxRetainedChangedBlocks < 1) {
            throw new IllegalArgumentException("Edit history limits are invalid");
        }
        this.maxEntriesPerWorld = maxEntriesPerWorld;
        this.maxEntriesTotal = maxEntriesTotal;
        this.maxRetainedChangedBlocks = maxRetainedChangedBlocks;
    }

    Lease enterMutation(UUID worldId, String worldName) throws OperationException {
        return enter(worldId, worldName, Access.MUTATION);
    }

    Lease enterHistory(UUID worldId, String worldName) throws OperationException {
        return enter(worldId, worldName, Access.HISTORY);
    }

    Lease enterUndo(UUID worldId, String worldName) throws OperationException {
        return enter(worldId, worldName, Access.UNDO);
    }

    private Lease enter(UUID worldId, String worldName, Access access) throws OperationException {
        WorldState state;
        long generation;
        synchronized (this.worlds) {
            if (this.closed.get()) {
                throw unavailable();
            }
            state = this.worlds.computeIfAbsent(worldId, ignored -> new WorldState());
            if (state.invalidated) {
                throw unavailable(worldName);
            }
            if (access == Access.MUTATION && recoveryRequired(state)) {
                throw recoveryRequired(worldName);
            }
            state.reservations++;
            generation = state.generation;
        }
        if (!state.lock.tryLock()) {
            releaseReservation(worldId, state);
            throw busy(worldName);
        }
        synchronized (this.worlds) {
            if (this.closed.get() || state.invalidated || generation != state.generation) {
                state.lock.unlock();
                releaseReservation(worldId, state);
                throw unavailable(worldName);
            }
            if (access == Access.UNDO) {
                state.undoActive = true;
            }
        }
        return new Lease(worldId, state, generation, access);
    }

    void invalidate(UUID worldId) {
        synchronized (this.worlds) {
            WorldState state = this.worlds.get(worldId);
            if (state != null) {
                state.generation++;
                state.invalidated = true;
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
                    if (state.reservations == 0) {
                        discardHistory(state);
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

    int retainedEditCount() {
        synchronized (this.worlds) {
            return this.retained.size();
        }
    }

    long retainedChangedBlockCount() {
        synchronized (this.worlds) {
            return this.retainedChangedBlocks;
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
            discardHistory(state);
            this.worlds.remove(worldId, state);
        }
    }

    private void discardHistory(WorldState state) {
        while (!state.history.isEmpty()) {
            evict(state.history.peekFirst(), true);
        }
    }

    private void reserveHistory(WorldState state, long maximumChangedBlocks)
            throws OperationException {
        if (maximumChangedBlocks < 1 || maximumChangedBlocks > this.maxRetainedChangedBlocks) {
            throw historyCapacity();
        }

        Set<RetainedEdit> evictions = new LinkedHashSet<>();
        long evictedChangedBlocks = 0;
        while ((long) state.history.size() + state.historyReservations + 1 - evictions.size()
                > this.maxEntriesPerWorld) {
            RetainedEdit eviction = oldestEvictable(state.history, evictions);
            if (eviction == null) {
                throw historyCapacity();
            }
            evictions.add(eviction);
            evictedChangedBlocks += eviction.record().changedBlockCount();
        }

        while ((long) this.retained.size() + this.reservedHistoryEntries + 1 - evictions.size()
                        > this.maxEntriesTotal
                || this.retainedChangedBlocks
                                + this.reservedHistoryChangedBlocks
                                + maximumChangedBlocks
                                - evictedChangedBlocks
                        > this.maxRetainedChangedBlocks) {
            RetainedEdit eviction = oldestEvictable(this.retained.values(), evictions);
            if (eviction == null) {
                throw historyCapacity();
            }
            evictions.add(eviction);
            evictedChangedBlocks += eviction.record().changedBlockCount();
        }

        for (RetainedEdit eviction : evictions) {
            evict(eviction, true);
        }
        state.historyReservations++;
        this.reservedHistoryEntries++;
        this.reservedHistoryChangedBlocks += maximumChangedBlocks;
    }

    private void retainReserved(WorldState state, RetainedEdit edit, long reservedChangedBlocks) {
        UUID editId = edit.record().editId();
        if (this.retained.containsKey(editId)) {
            throw new IllegalArgumentException("Duplicate edit ID: " + editId);
        }
        if (reservedChangedBlocks < edit.record().changedBlockCount()
                || state.historyReservations < 1
                || this.reservedHistoryEntries < 1
                || this.reservedHistoryChangedBlocks < reservedChangedBlocks) {
            throw new IllegalStateException("Edit exceeds its retained-history reservation");
        }
        releaseHistoryReservation(state, reservedChangedBlocks);
        state.history.addLast(edit);
        this.retained.put(editId, edit);
        this.retainedChangedBlocks += edit.record().changedBlockCount();
        if (state.history.size() > this.maxEntriesPerWorld
                || this.retained.size() > this.maxEntriesTotal
                || this.retainedChangedBlocks > this.maxRetainedChangedBlocks) {
            throw new IllegalStateException("Retained edit exceeded its bounded reservation");
        }
    }

    private void releaseHistoryReservation(WorldState state, long changedBlocks) {
        state.historyReservations--;
        this.reservedHistoryEntries--;
        this.reservedHistoryChangedBlocks -= changedBlocks;
    }

    private void removeWithoutClosing(RetainedEdit edit) {
        WorldState owner = this.worlds.get(edit.record().worldId());
        if (owner != null) {
            owner.history.removeLastOccurrence(edit);
        }
        if (this.retained.remove(edit.record().editId(), edit)) {
            this.retainedChangedBlocks -= edit.record().changedBlockCount();
        }
    }

    private void evict(RetainedEdit edit, boolean closeToken) {
        removeWithoutClosing(edit);
        if (closeToken) {
            edit.undo().close();
        }
    }

    private RetainedEdit oldestEvictable(
            Iterable<RetainedEdit> candidates, Set<RetainedEdit> excluded) {
        for (RetainedEdit candidate : candidates) {
            WorldState owner = this.worlds.get(candidate.record().worldId());
            if (!excluded.contains(candidate)
                    && candidate.record().status() == EditStatus.COMMITTED
                    && (owner == null || !owner.undoActive)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean recoveryRequired(WorldState state) {
        RetainedEdit latest = state.history.peekLast();
        return latest != null && latest.record().status() == EditStatus.RECOVERY_REQUIRED;
    }

    private static OperationException busy(String worldName) {
        return new OperationException(
                OperationFailure.WORLD_BUSY,
                "Another Dirt MCP world operation is running in world: " + worldName);
    }

    private static OperationException historyCapacity() {
        return new OperationException(
                OperationFailure.HISTORY_CAPACITY_EXCEEDED,
                "No bounded edit-history slot is available; the world was not changed");
    }

    private static OperationException unavailable() {
        return new OperationException(
                OperationFailure.WORLD_UNAVAILABLE, "World editing is stopping");
    }

    private static OperationException unavailable(String worldName) {
        return new OperationException(
                OperationFailure.WORLD_UNAVAILABLE, "World is unavailable: " + worldName);
    }

    private static OperationException recoveryRequired(String worldName) {
        return new OperationException(
                OperationFailure.WORLD_BUSY,
                "The newest retained edit requires recovery before editing world: " + worldName);
    }

    final class Lease implements AutoCloseable {
        private final UUID worldId;
        private final WorldState state;
        private final long generation;
        private final Access access;
        private long historyReservation;
        private boolean released;

        private Lease(UUID worldId, WorldState state, long generation, Access access) {
            this.worldId = worldId;
            this.state = state;
            this.generation = generation;
            this.access = access;
        }

        void reserveHistory(long maximumChangedBlocks) throws OperationException {
            synchronized (worlds) {
                if (this.access != Access.MUTATION
                        || this.historyReservation != 0
                        || this.released) {
                    throw new IllegalStateException(
                            "History can only be reserved once by a current mutation lease");
                }
                if (this.generation != this.state.generation
                        || this.state.invalidated
                        || closed.get()) {
                    throw unavailable();
                }
                EditCoordinator.this.reserveHistory(this.state, maximumChangedBlocks);
                this.historyReservation = maximumChangedBlocks;
            }
        }

        boolean remember(RetainedEdit edit) {
            synchronized (worlds) {
                if (!isCurrent(edit)) {
                    return false;
                }
                requireHistoryReservation();
                retainReserved(this.state, edit, this.historyReservation);
                this.historyReservation = 0;
                return true;
            }
        }

        void rememberRecovery(RetainedEdit edit) {
            synchronized (worlds) {
                if (isCurrent(edit)) {
                    requireHistoryReservation();
                    retainReserved(this.state, edit.requireRecovery(), this.historyReservation);
                    this.historyReservation = 0;
                } else {
                    edit.undo().close();
                }
            }
        }

        RetainedEdit latest() {
            synchronized (worlds) {
                return this.generation == this.state.generation
                        ? this.state.history.peekLast()
                        : null;
            }
        }

        boolean contains(UUID editId) {
            synchronized (worlds) {
                if (this.generation != this.state.generation) {
                    return false;
                }
                return this.state.history.stream()
                        .anyMatch(edit -> edit.record().editId().equals(editId));
            }
        }

        List<EditRecord> history() {
            synchronized (worlds) {
                if (this.generation != this.state.generation) {
                    return List.of();
                }
                List<EditRecord> snapshot = new ArrayList<>(this.state.history.size());
                var iterator = this.state.history.descendingIterator();
                while (iterator.hasNext()) {
                    snapshot.add(iterator.next().record());
                }
                return List.copyOf(snapshot);
            }
        }

        void markRecoveryRequired(RetainedEdit expected) {
            synchronized (worlds) {
                if (this.generation != this.state.generation
                        || this.state.history.peekLast() != expected) {
                    return;
                }
                RetainedEdit replacement = expected.requireRecovery();
                this.state.history.removeLast();
                this.state.history.addLast(replacement);
                retained.replace(expected.record().editId(), expected, replacement);
            }
        }

        void removeLatest(RetainedEdit expected) {
            synchronized (worlds) {
                if (this.generation == this.state.generation
                        && this.state.history.peekLast() == expected) {
                    evict(expected, true);
                }
            }
        }

        private boolean isCurrent(RetainedEdit edit) {
            return edit.record().worldId().equals(this.worldId)
                    && this.generation == this.state.generation
                    && !closed.get();
        }

        private void requireHistoryReservation() {
            if (this.historyReservation < 1) {
                throw new IllegalStateException(
                        "A live edit must reserve bounded history before mutation");
            }
        }

        @Override
        public void close() {
            if (!this.released) {
                this.released = true;
                synchronized (worlds) {
                    if (this.historyReservation != 0) {
                        releaseHistoryReservation(this.state, this.historyReservation);
                        this.historyReservation = 0;
                    }
                    if (this.access == Access.UNDO) {
                        this.state.undoActive = false;
                    }
                }
                this.state.lock.unlock();
                releaseReservation(this.worldId, this.state);
            }
        }
    }

    private enum Access {
        MUTATION,
        HISTORY,
        UNDO
    }

    private static final class WorldState {
        private final ReentrantLock lock = new ReentrantLock();
        private final ArrayDeque<RetainedEdit> history = new ArrayDeque<>();
        private long generation;
        private int reservations;
        private int historyReservations;
        private boolean invalidated;
        private boolean undoActive;
    }
}
