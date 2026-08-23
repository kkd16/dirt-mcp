package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
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

/**
 * Coordinates per-world operations with globally bounded retained history.
 *
 * <p>The {@code worlds} monitor guards both indexes, all history accounting, and mutable {@link
 * WorldState} fields other than lock ownership. A lease may acquire that monitor while it holds its
 * world lock, so code inside the monitor must never wait for a world lock. Undo resources are
 * detached and accounted for under the monitor, then closed only after the monitor is released.
 */
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
    private int pendingDisposals;

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
        synchronized (this.worlds) {
            if (this.closed.get()) {
                throw unavailable();
            }
            state = this.worlds.computeIfAbsent(worldId, ignored -> new WorldState());
            if (state.invalidated) {
                throw unavailable(worldName);
            }
            if (access == Access.MUTATION && recoveryRequired(state)) {
                throw recoveryRequired(worldName, state.history.getLast().record().editId());
            }
            state.reservations++;
        }
        if (!state.lock.tryLock()) {
            releaseReservation(worldId, state);
            throw busy(worldName);
        }
        boolean stopping;
        boolean invalidated;
        synchronized (this.worlds) {
            stopping = this.closed.get();
            invalidated = state.invalidated;
            if (!stopping && !invalidated && access == Access.UNDO) {
                state.undoActive = true;
            }
        }
        if (stopping || invalidated) {
            state.lock.unlock();
            releaseReservation(worldId, state);
            throw stopping ? unavailable() : unavailable(worldName);
        }
        return new Lease(worldId, worldName, state, access);
    }

    void invalidate(UUID worldId) {
        List<RetainedEdit> discarded = List.of();
        synchronized (this.worlds) {
            WorldState state = this.worlds.get(worldId);
            if (state != null) {
                state.invalidated = true;
                discarded = pruneIdle(worldId, state);
            }
        }
        closeDetached(discarded);
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            List<RetainedEdit> discarded = new ArrayList<>();
            synchronized (this.worlds) {
                var iterator = this.worlds.entrySet().iterator();
                while (iterator.hasNext()) {
                    WorldState state = iterator.next().getValue();
                    state.invalidated = true;
                    if (state.reservations == 0) {
                        discarded.addAll(detachHistory(state));
                        iterator.remove();
                    }
                }
            }
            closeDetached(discarded);
        }
    }

    boolean isQuiescent() {
        synchronized (this.worlds) {
            return this.pendingDisposals == 0
                    && this.worlds.values().stream().allMatch(state -> state.reservations == 0);
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

    int maxEntriesPerWorld() {
        return this.maxEntriesPerWorld;
    }

    private void releaseReservation(UUID worldId, WorldState state) {
        List<RetainedEdit> discarded;
        synchronized (this.worlds) {
            state.reservations--;
            discarded = pruneIdle(worldId, state);
        }
        closeDetached(discarded);
    }

    private List<RetainedEdit> pruneIdle(UUID worldId, WorldState state) {
        if (state.reservations != 0) {
            return List.of();
        }
        List<RetainedEdit> discarded = List.of();
        if (state.invalidated) {
            discarded = detachHistory(state);
        }
        if (state.history.isEmpty()) {
            this.worlds.remove(worldId, state);
        }
        return discarded;
    }

    private List<RetainedEdit> detachHistory(WorldState state) {
        List<RetainedEdit> detached = new ArrayList<>(state.history.size());
        detached.addAll(state.history);
        state.history.clear();
        for (RetainedEdit edit : detached) {
            if (this.retained.remove(edit.record().editId(), edit)) {
                this.retainedChangedBlocks -= edit.record().changedBlockCount();
            }
        }
        this.pendingDisposals += detached.size();
        return detached;
    }

    private List<RetainedEdit> reserveHistory(WorldState state, long maximumChangedBlocks)
            throws OperationException {
        if (maximumChangedBlocks < 1 || maximumChangedBlocks > this.maxRetainedChangedBlocks) {
            throw retainedChangedBlockCapacity();
        }

        Set<RetainedEdit> evictions = new LinkedHashSet<>();
        long evictedChangedBlocks = 0;
        while ((long) state.history.size() + state.historyReservations + 1 - evictions.size()
                > this.maxEntriesPerWorld) {
            RetainedEdit eviction = oldestEvictable(state.history, evictions);
            if (eviction == null) {
                throw new OperationException(
                        OperationFailure.HISTORY_CAPACITY_EXCEEDED,
                        "No bounded edit-history slot is available; the world was not changed",
                        new ErrorDetails.HistoryCapacityExceeded.EntriesPerWorld(
                                this.maxEntriesPerWorld));
            }
            evictions.add(eviction);
            evictedChangedBlocks += eviction.record().changedBlockCount();
        }

        while (true) {
            boolean entriesExceeded =
                    (long) this.retained.size() + this.reservedHistoryEntries + 1 - evictions.size()
                            > this.maxEntriesTotal;
            boolean changedBlocksExceeded =
                    this.retainedChangedBlocks
                                    + this.reservedHistoryChangedBlocks
                                    + maximumChangedBlocks
                                    - evictedChangedBlocks
                            > this.maxRetainedChangedBlocks;
            if (!entriesExceeded && !changedBlocksExceeded) {
                break;
            }
            RetainedEdit eviction = oldestEvictable(this.retained.values(), evictions);
            if (eviction == null) {
                ErrorDetails.HistoryCapacityExceeded details =
                        entriesExceeded
                                ? new ErrorDetails.HistoryCapacityExceeded.EntriesTotal(
                                        this.maxEntriesTotal)
                                : new ErrorDetails.HistoryCapacityExceeded.RetainedChangedBlocks(
                                        this.maxRetainedChangedBlocks);
                throw new OperationException(
                        OperationFailure.HISTORY_CAPACITY_EXCEEDED,
                        "No bounded edit-history slot is available; the world was not changed",
                        details);
            }
            evictions.add(eviction);
            evictedChangedBlocks += eviction.record().changedBlockCount();
        }

        for (RetainedEdit eviction : evictions) {
            detach(eviction);
        }
        state.historyReservations++;
        this.reservedHistoryEntries++;
        this.reservedHistoryChangedBlocks += maximumChangedBlocks;
        return List.copyOf(evictions);
    }

    private void retainReserved(WorldState state, RetainedEdit edit, long reservedChangedBlocks) {
        UUID editId = edit.record().editId();
        if (this.retained.containsKey(editId)) {
            throw new IllegalArgumentException("Duplicate edit ID: " + editId);
        }
        long changedBlocks = edit.record().changedBlockCount();
        if (reservedChangedBlocks < changedBlocks
                || state.historyReservations < 1
                || this.reservedHistoryEntries < 1
                || this.reservedHistoryChangedBlocks < reservedChangedBlocks) {
            throw new IllegalStateException("Edit exceeds its retained-history reservation");
        }

        long projectedWorldEntries = (long) state.history.size() + 1;
        long projectedWorldReservations = (long) state.historyReservations - 1;
        long projectedRetainedEntries = (long) this.retained.size() + 1;
        long projectedGlobalReservations = (long) this.reservedHistoryEntries - 1;
        long projectedReservedChangedBlocks =
                this.reservedHistoryChangedBlocks - reservedChangedBlocks;
        if (projectedWorldEntries > this.maxEntriesPerWorld
                || projectedWorldEntries + projectedWorldReservations > this.maxEntriesPerWorld
                || projectedRetainedEntries > this.maxEntriesTotal
                || projectedRetainedEntries + projectedGlobalReservations > this.maxEntriesTotal
                || this.retainedChangedBlocks > this.maxRetainedChangedBlocks - changedBlocks) {
            throw new IllegalStateException("Retained edit exceeds bounded history capacity");
        }
        long projectedRetainedChangedBlocks = this.retainedChangedBlocks + changedBlocks;
        if (projectedReservedChangedBlocks
                > this.maxRetainedChangedBlocks - projectedRetainedChangedBlocks) {
            throw new IllegalStateException("Retained edit exceeds bounded history capacity");
        }

        releaseHistoryReservation(state, reservedChangedBlocks);
        state.history.addLast(edit);
        this.retained.put(editId, edit);
        this.retainedChangedBlocks = projectedRetainedChangedBlocks;
    }

    private void releaseHistoryReservation(WorldState state, long changedBlocks) {
        state.historyReservations--;
        this.reservedHistoryEntries--;
        this.reservedHistoryChangedBlocks -= changedBlocks;
    }

    private boolean removeWithoutClosing(RetainedEdit edit) {
        boolean removed = false;
        WorldState owner = this.worlds.get(edit.record().worldId());
        if (owner != null) {
            removed = owner.history.removeLastOccurrence(edit);
        }
        if (this.retained.remove(edit.record().editId(), edit)) {
            this.retainedChangedBlocks -= edit.record().changedBlockCount();
            removed = true;
        }
        return removed;
    }

    private void detach(RetainedEdit edit) {
        removeWithoutClosing(edit);
        this.pendingDisposals++;
        WorldState owner = this.worlds.get(edit.record().worldId());
        if (owner != null
                && !owner.invalidated
                && owner.reservations == 0
                && owner.history.isEmpty()) {
            this.worlds.remove(edit.record().worldId(), owner);
        }
    }

    private void closeDetached(Iterable<RetainedEdit> detached) {
        for (RetainedEdit edit : detached) {
            try {
                edit.undo().close();
            } finally {
                synchronized (this.worlds) {
                    this.pendingDisposals--;
                }
            }
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
                "Another Dirt MCP world operation is running in world: " + worldName,
                new ErrorDetails.WorldBusy.OperationInProgress(worldName));
    }

    private OperationException retainedChangedBlockCapacity() {
        return new OperationException(
                OperationFailure.HISTORY_CAPACITY_EXCEEDED,
                "No bounded edit-history slot is available; the world was not changed",
                new ErrorDetails.HistoryCapacityExceeded.RetainedChangedBlocks(
                        this.maxRetainedChangedBlocks));
    }

    private static OperationException unavailable() {
        return new OperationException(
                OperationFailure.WORLD_UNAVAILABLE,
                "World editing is stopping",
                new ErrorDetails.WorldUnavailable.Stopping());
    }

    private static OperationException unavailable(String worldName) {
        return new OperationException(
                OperationFailure.WORLD_UNAVAILABLE,
                "World is unavailable: " + worldName,
                new ErrorDetails.WorldUnavailable.WorldUnloaded(worldName));
    }

    private static OperationException recoveryRequired(String worldName, UUID newestEditId) {
        return new OperationException(
                OperationFailure.WORLD_BUSY,
                "The newest retained edit requires recovery before editing world: " + worldName,
                new ErrorDetails.WorldBusy.RecoveryRequired(worldName, newestEditId));
    }

    final class Lease implements AutoCloseable {
        private final UUID worldId;
        private final String worldName;
        private final WorldState state;
        private final Access access;
        private long historyReservation;
        private boolean released;

        private Lease(UUID worldId, String worldName, WorldState state, Access access) {
            this.worldId = worldId;
            this.worldName = worldName;
            this.state = state;
            this.access = access;
        }

        void reserveHistory(long maximumChangedBlocks) throws OperationException {
            List<RetainedEdit> evictions;
            synchronized (worlds) {
                if (this.access != Access.MUTATION
                        || this.historyReservation != 0
                        || this.released) {
                    throw new IllegalStateException(
                            "History can only be reserved once by a current mutation lease");
                }
                if (closed.get()) {
                    throw unavailable();
                }
                if (this.state.invalidated) {
                    throw unavailable(this.worldName);
                }
                evictions = EditCoordinator.this.reserveHistory(this.state, maximumChangedBlocks);
                this.historyReservation = maximumChangedBlocks;
            }
            closeDetached(evictions);
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

        boolean rememberRecovery(RetainedEdit edit) {
            synchronized (worlds) {
                if (isCurrent(edit)) {
                    requireHistoryReservation();
                    retainReserved(this.state, edit.requireRecovery(), this.historyReservation);
                    this.historyReservation = 0;
                    return true;
                }
                return false;
            }
        }

        List<EditRecord> history() {
            synchronized (worlds) {
                if (this.state.invalidated) {
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

        List<RetainedEdit> requireUndoPrefix(List<UUID> editIds) throws OperationException {
            synchronized (worlds) {
                if (this.access != Access.UNDO || this.released) {
                    throw new IllegalStateException("An undo prefix requires a current undo lease");
                }
                if (this.state.invalidated) {
                    throw unavailable(this.worldName);
                }
                List<RetainedEdit> prefix = new ArrayList<>(editIds.size());
                var iterator = this.state.history.descendingIterator();
                for (UUID requested : editIds) {
                    RetainedEdit expected = iterator.hasNext() ? iterator.next() : null;
                    if (expected != null && expected.record().editId().equals(requested)) {
                        prefix.add(expected);
                        continue;
                    }
                    boolean retainedInWorld =
                            this.state.history.stream()
                                    .anyMatch(edit -> edit.record().editId().equals(requested));
                    if (retainedInWorld && expected != null) {
                        throw new OperationException(
                                OperationFailure.EDIT_NOT_LATEST,
                                "Edit is retained but does not match the next edit eligible for undo",
                                new ErrorDetails.EditNotLatest(
                                        this.worldName, requested, expected.record().editId()));
                    }
                    throw new OperationException(
                            OperationFailure.EDIT_NOT_FOUND,
                            "Edit is not retained for this world: " + requested,
                            new ErrorDetails.EditNotFound(this.worldName, requested));
                }
                return List.copyOf(prefix);
            }
        }

        void markRecoveryRequired(RetainedEdit expected) {
            synchronized (worlds) {
                if (this.state.invalidated || this.state.history.peekLast() != expected) {
                    return;
                }
                RetainedEdit replacement = expected.requireRecovery();
                this.state.history.removeLast();
                this.state.history.addLast(replacement);
                retained.replace(expected.record().editId(), expected, replacement);
            }
        }

        void consumeRestored(RetainedEdit restored) {
            boolean detached;
            synchronized (worlds) {
                detached = removeWithoutClosing(restored);
                if (detached) {
                    pendingDisposals++;
                }
            }
            if (detached) {
                closeDetached(List.of(restored));
            }
        }

        private boolean isCurrent(RetainedEdit edit) {
            return edit.record().worldId().equals(this.worldId)
                    && !this.state.invalidated
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
        private int reservations;
        private int historyReservations;
        private boolean invalidated;
        private boolean undoActive;
    }
}
