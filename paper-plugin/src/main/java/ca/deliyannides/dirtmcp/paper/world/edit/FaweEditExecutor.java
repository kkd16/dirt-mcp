package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.function.mask.BlockMask;
import com.sk89q.worldedit.function.operation.ChangeSetExecutor;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

final class FaweEditExecutor {
    private final int maxChangedBlocks;
    private final Logger logger;

    FaweEditExecutor(int maxChangedBlocks, Logger logger) {
        if (maxChangedBlocks < 1) {
            throw new IllegalArgumentException("Maximum changed blocks must be positive");
        }
        this.maxChangedBlocks = maxChangedBlocks;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    EditPlatform.EditResult replace(
            PaperEditPreparation.PreparedReplace edit,
            Cuboid region,
            boolean dryRun,
            EditPlatform.MutationAdmission admission)
            throws OperationException {
        Objects.requireNonNull(admission, "admission");
        CuboidRegion selection = selection(edit.paperWorld().worldEditWorld(), region);
        EditSession session = newEditSession(edit.paperWorld().worldEditWorld(), !dryRun);
        long matches = 0;
        long expectedChanges = 0;
        long changes;
        StoredUndo undo = null;
        try {
            try (session) {
                BlockMask sourceMask = new BlockMask(session);
                sourceMask.add(edit.sources().blockStates().toArray(BlockState[]::new));
                for (BlockVector3 position : selection) {
                    requireNotInterrupted();
                    BlockState current = session.getBlock(position);
                    if (!sourceMask.test(current)) {
                        continue;
                    }
                    matches++;
                    if (!current.equals(
                            edit.palette().pattern().applyBlock(position).toBlockState())) {
                        expectedChanges++;
                    }
                }
                enforceChangeLimit(expectedChanges);
                changes = expectedChanges;
                if (!dryRun && expectedChanges > 0) {
                    requireNotInterrupted();
                    admission.beforeMutation();
                    requireNotInterrupted();
                    session.replaceBlocks(selection, sourceMask, edit.palette().pattern());
                    requireNotInterrupted();
                }
            }
            if (!dryRun && expectedChanges > 0) {
                changes = session.getChangeSet().longSize();
                if (changes > 0) {
                    undo = retainedUndo(session, changes, edit.chunks());
                }
            }
        } catch (MaxChangedBlocksException exception) {
            OperationException failure = changeLimit(exception);
            rollbackAfterFailure(edit.paperWorld(), session, edit.chunks(), dryRun, failure);
            throw failure;
        } catch (OperationException exception) {
            rollbackAfterFailure(edit.paperWorld(), session, edit.chunks(), dryRun, exception);
            throw exception;
        } catch (RuntimeException exception) {
            rollbackAfterFailure(edit.paperWorld(), session, edit.chunks(), dryRun, exception);
            throw exception;
        }
        return new EditPlatform.EditResult(matches, changes, undo);
    }

    EditPlatform.EditResult fill(
            PaperEditPreparation.PreparedFill edit,
            Cuboid region,
            boolean dryRun,
            EditPlatform.MutationAdmission admission)
            throws OperationException {
        Objects.requireNonNull(admission, "admission");
        CuboidRegion selection = selection(edit.paperWorld().worldEditWorld(), region);
        EditSession session = newEditSession(edit.paperWorld().worldEditWorld(), !dryRun);
        long expectedChanges = 0;
        long changes;
        StoredUndo undo = null;
        try {
            try (session) {
                for (BlockVector3 position : selection) {
                    requireNotInterrupted();
                    if (!session.getBlock(position)
                            .equals(edit.palette().pattern().applyBlock(position).toBlockState())) {
                        expectedChanges++;
                    }
                }
                enforceChangeLimit(expectedChanges);
                changes = expectedChanges;
                if (!dryRun && expectedChanges > 0) {
                    requireNotInterrupted();
                    admission.beforeMutation();
                    requireNotInterrupted();
                    session.setBlocks(
                            (com.sk89q.worldedit.regions.Region) selection,
                            edit.palette().pattern());
                    requireNotInterrupted();
                }
            }
            if (!dryRun && expectedChanges > 0) {
                changes = session.getChangeSet().longSize();
                if (changes > 0) {
                    undo = retainedUndo(session, changes, edit.chunks());
                }
            }
        } catch (MaxChangedBlocksException exception) {
            OperationException failure = changeLimit(exception);
            rollbackAfterFailure(edit.paperWorld(), session, edit.chunks(), dryRun, failure);
            throw failure;
        } catch (OperationException exception) {
            rollbackAfterFailure(edit.paperWorld(), session, edit.chunks(), dryRun, exception);
            throw exception;
        } catch (RuntimeException exception) {
            rollbackAfterFailure(edit.paperWorld(), session, edit.chunks(), dryRun, exception);
            throw exception;
        }
        return new EditPlatform.EditResult(0, changes, undo);
    }

    EditPlatform.EditResult set(
            PaperEditPreparation.PreparedSet edit,
            boolean dryRun,
            EditPlatform.MutationAdmission admission)
            throws OperationException {
        Objects.requireNonNull(admission, "admission");
        EditSession session = newEditSession(edit.paperWorld().worldEditWorld(), !dryRun);
        List<SetBlockChange> pending = new ArrayList<>();
        long expectedChanges;
        long changes;
        StoredUndo undo = null;
        try {
            try (session) {
                for (PaperEditPreparation.PreparedBlockChange change : edit.changes()) {
                    requireNotInterrupted();
                    BlockState blockState =
                            change.pattern().applyBlock(change.position()).toBlockState();
                    if (!session.getBlock(change.position()).equals(blockState)) {
                        pending.add(new SetBlockChange(change.position(), blockState));
                    }
                }
                expectedChanges = pending.size();
                enforceChangeLimit(expectedChanges);
                if (!dryRun && expectedChanges > 0) {
                    requireNotInterrupted();
                    admission.beforeMutation();
                    requireNotInterrupted();
                    for (SetBlockChange change : pending) {
                        requireNotInterrupted();
                        session.setBlock(
                                change.position().x(),
                                change.position().y(),
                                change.position().z(),
                                change.blockState());
                    }
                    requireNotInterrupted();
                }
            }
            changes = dryRun ? expectedChanges : session.getChangeSet().longSize();
            if (!dryRun && changes > 0) {
                undo = retainedUndo(session, changes, edit.chunks());
            }
        } catch (MaxChangedBlocksException exception) {
            OperationException failure = changeLimit(exception);
            rollbackAfterFailure(edit.paperWorld(), session, edit.chunks(), dryRun, failure);
            throw failure;
        } catch (OperationException exception) {
            rollbackAfterFailure(edit.paperWorld(), session, edit.chunks(), dryRun, exception);
            throw exception;
        } catch (RuntimeException exception) {
            rollbackAfterFailure(edit.paperWorld(), session, edit.chunks(), dryRun, exception);
            throw exception;
        }
        return new EditPlatform.EditResult(0, changes, undo);
    }

    void undo(PaperEditPreparation.PaperWorld world, StoredUndo undo) throws OperationException {
        requireNotInterrupted();
        applyUndo(world, undo);
    }

    void rollback(PaperEditPreparation.PaperWorld world, StoredUndo undo)
            throws OperationException {
        preserveInterruption(() -> applyUndo(world, undo));
    }

    static void preserveInterruption(Runnable recovery) {
        Objects.requireNonNull(recovery, "recovery");
        boolean interrupted = Thread.interrupted();
        try {
            recovery.run();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void applyUndo(PaperEditPreparation.PaperWorld world, StoredUndo undo) {
        try (EditSession session = newEditSession(world.worldEditWorld(), false)) {
            session.setBlocks(undo.changeSet(), ChangeSetExecutor.Type.UNDO);
        }
    }

    private void rollbackAfterFailure(
            PaperEditPreparation.PaperWorld world,
            EditSession failedSession,
            List<ChunkPosition> chunks,
            boolean dryRun,
            Throwable failure)
            throws EditRecoveryException {
        long changes = dryRun ? 0 : failedSession.getChangeSet().longSize();
        if (changes == 0) {
            return;
        }
        StoredUndo recovery = retainedUndo(failedSession, changes, chunks);
        boolean interrupted = Thread.interrupted();
        try {
            try (EditSession rollback = newEditSession(world.worldEditWorld(), false)) {
                rollback.setBlocks(recovery.changeSet(), ChangeSetExecutor.Type.UNDO);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                throw new EditRecoveryException(
                        "World edit failed and its automatic rollback also failed",
                        failure,
                        recovery);
            }
            recovery.close();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private EditSession newEditSession(
            com.sk89q.worldedit.world.World world, boolean recordHistory) {
        var builder =
                WorldEdit.getInstance()
                        .newEditSessionBuilder()
                        .world(world)
                        .maxBlocks(this.maxChangedBlocks)
                        .allowedRegionsEverywhere()
                        .setSideEffectSet(SideEffectSet.api().without(SideEffect.NEIGHBORS));
        if (recordHistory) {
            return builder.fastMode(false).combineStages(true).changeSet(false, null).build();
        }
        return builder.fastMode(true).changeSetNull().build();
    }

    private void enforceChangeLimit(long changes) throws OperationException {
        if (changes > this.maxChangedBlocks) {
            throw changeLimit(null);
        }
    }

    private static void requireNotInterrupted() throws OperationException {
        if (Thread.currentThread().isInterrupted()) {
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE, "World editing was interrupted");
        }
    }

    private OperationException changeLimit(Throwable cause) {
        String message = "Edit exceeds the maximum of " + this.maxChangedBlocks + " changed blocks";
        return cause == null
                ? new OperationException(OperationFailure.CHANGE_LIMIT_EXCEEDED, message)
                : new OperationException(OperationFailure.CHANGE_LIMIT_EXCEEDED, message, cause);
    }

    private static CuboidRegion selection(com.sk89q.worldedit.world.World world, Cuboid region) {
        return new CuboidRegion(
                world,
                BlockVector3.at(region.min().x(), region.min().y(), region.min().z()),
                BlockVector3.at(region.max().x(), region.max().y(), region.max().z()));
    }

    private StoredUndo retainedUndo(
            EditSession session, long changedBlockCount, List<ChunkPosition> chunks) {
        ChangeSet changeSet = Objects.requireNonNull(session.getChangeSet(), "changeSet");
        if (changeSet.longSize() != changedBlockCount) {
            throw new IllegalStateException(
                    "FAWE undo data does not match the reported change count");
        }
        return new StoredUndo(changeSet, changedBlockCount, chunks, this.logger);
    }

    static final class StoredUndo implements EditPlatform.UndoToken {
        private final AtomicReference<ChangeSet> changeSet;
        private final long changedBlockCount;
        private final List<ChunkPosition> chunks;
        private final Logger logger;

        StoredUndo(
                ChangeSet changeSet,
                long changedBlockCount,
                List<ChunkPosition> chunks,
                Logger logger) {
            if (changedBlockCount < 1) {
                throw new IllegalArgumentException("Retained undo change count must be positive");
            }
            this.changeSet = new AtomicReference<>(Objects.requireNonNull(changeSet, "changeSet"));
            this.changedBlockCount = changedBlockCount;
            this.chunks = List.copyOf(chunks);
            this.logger = Objects.requireNonNull(logger, "logger");
        }

        ChangeSet changeSet() {
            ChangeSet retained = this.changeSet.get();
            if (retained == null) {
                throw new IllegalStateException("Undo data has already been released");
            }
            return retained;
        }

        @Override
        public long changedBlockCount() {
            return this.changedBlockCount;
        }

        List<ChunkPosition> chunks() {
            return this.chunks;
        }

        @Override
        public void close() {
            ChangeSet retained = this.changeSet.getAndSet(null);
            if (retained != null) {
                try {
                    retained.delete();
                } catch (RuntimeException failure) {
                    this.logger.log(
                            Level.WARNING, "Could not dispose retained FAWE undo data", failure);
                }
            }
        }
    }

    private record SetBlockChange(BlockVector3 position, BlockState blockState) {}
}
