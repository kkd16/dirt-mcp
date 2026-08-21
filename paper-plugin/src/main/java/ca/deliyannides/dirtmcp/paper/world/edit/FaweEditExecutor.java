package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.function.mask.BlockMask;
import com.sk89q.worldedit.function.operation.ChangeSetExecutor;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class FaweEditExecutor {
    private final int maxChangedBlocks;
    private final DirtLog log;

    FaweEditExecutor(int maxChangedBlocks, DirtLog log) {
        if (maxChangedBlocks < 1) {
            throw new IllegalArgumentException("Maximum changed blocks must be positive");
        }
        this.maxChangedBlocks = maxChangedBlocks;
        this.log = Objects.requireNonNull(log, "log");
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
                    undo = retainedUndo(session, edit.chunks());
                    changes = undo.changedBlockCount();
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

    EditPlatform.EditResult set(
            PaperEditPreparation.PreparedSet edit,
            boolean dryRun,
            EditPlatform.MutationAdmission admission)
            throws OperationException {
        Objects.requireNonNull(admission, "admission");
        var paperWorld = edit.paperWorld();
        var world = paperWorld.worldEditWorld();
        SetBlockGeometry geometry = edit.geometry();
        var palettes = edit.preparedPalettes();
        List<ChunkPosition> chunks = geometry.chunks();
        EditSession session = newEditSession(world, !dryRun);
        List<SetBlockGeometry.ResolvedPlacement> changedPlacements = new ArrayList<>();
        List<SetBlockGeometry.ResolvedRun> changedRuns = new ArrayList<>();
        long expectedChanges = 0;
        long changes;
        StoredUndo undo = null;
        try {
            try (session) {
                for (SetBlockGeometry.ResolvedPlacement placement : geometry.placements()) {
                    requireNotInterrupted();
                    BlockVector3 position = vector(placement.position());
                    Pattern pattern = palettes.get(placement.paletteIndex()).pattern();
                    if (!session.getBlock(position)
                            .equals(pattern.applyBlock(position).toBlockState())) {
                        changedPlacements.add(placement);
                        expectedChanges++;
                    }
                }
                for (SetBlockGeometry.ResolvedRun run : geometry.runs()) {
                    Pattern pattern = palettes.get(run.paletteIndex()).pattern();
                    boolean changed = false;
                    for (BlockVector3 position : selection(world, run.region())) {
                        requireNotInterrupted();
                        if (!session.getBlock(position)
                                .equals(pattern.applyBlock(position).toBlockState())) {
                            changed = true;
                            expectedChanges++;
                        }
                    }
                    if (changed) {
                        changedRuns.add(run);
                    }
                }
                enforceChangeLimit(expectedChanges);
                if (!dryRun && expectedChanges > 0) {
                    requireNotInterrupted();
                    admission.beforeMutation();
                    requireNotInterrupted();
                    for (SetBlockGeometry.ResolvedPlacement placement : changedPlacements) {
                        requireNotInterrupted();
                        session.setBlock(
                                vector(placement.position()),
                                palettes.get(placement.paletteIndex()).pattern());
                    }
                    for (SetBlockGeometry.ResolvedRun run : changedRuns) {
                        requireNotInterrupted();
                        session.setBlocks(
                                (com.sk89q.worldedit.regions.Region) selection(world, run.region()),
                                palettes.get(run.paletteIndex()).pattern());
                    }
                    requireNotInterrupted();
                }
            }
            changes = dryRun ? expectedChanges : session.getChangeSet().longSize();
            if (!dryRun && changes > 0) {
                undo = retainedUndo(session, chunks);
                changes = undo.changedBlockCount();
            }
        } catch (MaxChangedBlocksException exception) {
            OperationException failure = changeLimit(exception);
            rollbackAfterFailure(paperWorld, session, chunks, dryRun, failure);
            throw failure;
        } catch (OperationException exception) {
            rollbackAfterFailure(paperWorld, session, chunks, dryRun, exception);
            throw exception;
        } catch (RuntimeException exception) {
            rollbackAfterFailure(paperWorld, session, chunks, dryRun, exception);
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
        undo.finalizeForUse();
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
        StoredUndo recovery;
        try {
            recovery = retainedUndo(failedSession, chunks);
        } catch (RuntimeException finalizationFailure) {
            if (failure != finalizationFailure) {
                failure.addSuppressed(finalizationFailure);
            }
            recovery = StoredUndo.pending(failedSession.getChangeSet(), changes, chunks, this.log);
            throw new EditRecoveryException(
                    "World edit failed and its undo data could not be finalized",
                    new ErrorDetails.WorldUnavailable.OperationFailed(),
                    failure,
                    recovery);
        }
        boolean interrupted = Thread.interrupted();
        try {
            try (EditSession rollback = newEditSession(world.worldEditWorld(), false)) {
                rollback.setBlocks(recovery.changeSet(), ChangeSetExecutor.Type.UNDO);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                throw new EditRecoveryException(
                        "World edit failed and its automatic rollback also failed",
                        new ErrorDetails.WorldUnavailable.RollbackFailed(),
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

    static void requireNotInterrupted() throws OperationException {
        if (Thread.currentThread().isInterrupted()) {
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE,
                    "World editing was interrupted",
                    new ErrorDetails.WorldUnavailable.Interrupted());
        }
    }

    private OperationException changeLimit(Throwable cause) {
        String message = "Edit exceeds the maximum of " + this.maxChangedBlocks + " changed blocks";
        return cause == null
                ? new OperationException(
                        OperationFailure.CHANGE_LIMIT_EXCEEDED,
                        message,
                        new ErrorDetails.ChangeLimitExceeded(this.maxChangedBlocks))
                : new OperationException(
                        OperationFailure.CHANGE_LIMIT_EXCEEDED,
                        message,
                        new ErrorDetails.ChangeLimitExceeded(this.maxChangedBlocks),
                        cause);
    }

    private static CuboidRegion selection(com.sk89q.worldedit.world.World world, Cuboid region) {
        return new CuboidRegion(world, vector(region.min()), vector(region.max()));
    }

    private static BlockVector3 vector(BlockPosition position) {
        return BlockVector3.at(position.x(), position.y(), position.z());
    }

    private StoredUndo retainedUndo(EditSession session, List<ChunkPosition> chunks) {
        ChangeSet changeSet = Objects.requireNonNull(session.getChangeSet(), "changeSet");
        return new StoredUndo(changeSet, chunks, this.log);
    }

    static final class StoredUndo implements EditPlatform.UndoToken {
        private final AtomicReference<ChangeSet> changeSet;
        private final long changedBlockCount;
        private final List<ChunkPosition> chunks;
        private final DirtLog log;
        private boolean finalized;

        StoredUndo(ChangeSet changeSet, List<ChunkPosition> chunks, DirtLog log) {
            ChangeSet retained = Objects.requireNonNull(changeSet, "changeSet");
            finalizeChangeSet(retained);
            long changedBlockCount = retained.longSize();
            requirePositiveCount(changedBlockCount);
            this.changeSet = new AtomicReference<>(retained);
            this.changedBlockCount = changedBlockCount;
            this.chunks = List.copyOf(chunks);
            this.log = Objects.requireNonNull(log, "log");
            this.finalized = true;
        }

        private StoredUndo(
                ChangeSet changeSet,
                long changedBlockCount,
                List<ChunkPosition> chunks,
                DirtLog log) {
            requirePositiveCount(changedBlockCount);
            this.changeSet = new AtomicReference<>(Objects.requireNonNull(changeSet, "changeSet"));
            this.changedBlockCount = changedBlockCount;
            this.chunks = List.copyOf(chunks);
            this.log = Objects.requireNonNull(log, "log");
        }

        static StoredUndo pending(
                ChangeSet changeSet,
                long changedBlockCount,
                List<ChunkPosition> chunks,
                DirtLog log) {
            return new StoredUndo(changeSet, changedBlockCount, chunks, log);
        }

        synchronized void finalizeForUse() {
            if (this.finalized) {
                return;
            }
            ChangeSet retained = changeSet();
            finalizeChangeSet(retained);
            if (retained.longSize() != this.changedBlockCount) {
                throw new IllegalStateException(
                        "FAWE undo data changed while it was awaiting recovery");
            }
            this.finalized = true;
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
                    LogContext context =
                            LogContext.of("changed_block_count", this.changedBlockCount)
                                    .with("chunk_count", this.chunks.size());
                    try {
                        this.log.warning(
                                "edit",
                                "edit.undo_data_disposal_failed",
                                "Dirt MCP could not dispose retained FAWE undo data",
                                context,
                                failure);
                    } catch (RuntimeException loggingFailure) {
                        if (failure != loggingFailure) {
                            failure.addSuppressed(loggingFailure);
                        }
                    }
                }
            }
        }

        private static void finalizeChangeSet(ChangeSet changeSet) {
            try {
                changeSet.close();
            } catch (IOException exception) {
                throw new IllegalStateException("FAWE undo data could not be finalized", exception);
            }
        }

        private static void requirePositiveCount(long changedBlockCount) {
            if (changedBlockCount < 1) {
                throw new IllegalArgumentException("Retained undo change count must be positive");
            }
        }
    }
}
