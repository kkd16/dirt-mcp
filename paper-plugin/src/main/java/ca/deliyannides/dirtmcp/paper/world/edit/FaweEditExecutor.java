package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.function.mask.BlockMask;
import com.sk89q.worldedit.function.operation.ChangeSetExecutor;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BaseBlock;
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
            int maxChangedBlocks,
            EditPlatform.MutationAdmission admission)
            throws OperationException {
        Objects.requireNonNull(admission, "admission");
        int checkedMaxChangedBlocks = checkedLimit(maxChangedBlocks);
        CuboidRegion selection = selection(edit.paperWorld().worldEditWorld(), region);
        EditSession session =
                newEditSession(
                        edit.paperWorld().worldEditWorld(), !dryRun, checkedMaxChangedBlocks);
        long matches = 0;
        long expectedChanges = 0;
        long changes;
        StoredUndo undo = null;
        List<PendingBlock> changedBlocks = dryRun ? List.of() : new ArrayList<>();
        try {
            try (session) {
                BlockMask sourceMask = new BlockMask(session);
                sourceMask.add(edit.sources().blockStates().toArray(BlockState[]::new));
                Pattern destination = edit.palette().pattern();
                for (BlockVector3 position : selection) {
                    requireNotInterrupted();
                    BlockState current = session.getBlock(position);
                    if (!sourceMask.test(current)) {
                        continue;
                    }
                    matches++;
                    BaseBlock replacement = destination.applyBlock(position);
                    if (!current.equals(replacement.toBlockState())) {
                        expectedChanges++;
                        enforceChangeLimit(expectedChanges, checkedMaxChangedBlocks);
                        if (!dryRun) {
                            changedBlocks.add(PendingBlock.snapshot(position, replacement));
                        }
                    }
                }
                if (!dryRun && expectedChanges > 0) {
                    requireNotInterrupted();
                    admission.beforeMutation();
                    requireNotInterrupted();
                    applyChanges(session, changedBlocks);
                }
            }
            if (dryRun) {
                changes = expectedChanges;
            } else {
                undo = finalizedUndoOrNull(session, edit.chunks());
                changes = undo == null ? 0 : undo.changedBlockCount();
            }
        } catch (OperationException | RuntimeException exception) {
            rollbackAfterFailure(edit.paperWorld(), session, edit.chunks(), dryRun, exception);
            throw exception;
        }
        return new EditPlatform.EditResult(matches, changes, undo);
    }

    EditPlatform.EditResult set(
            PaperEditPreparation.PreparedSet edit,
            boolean dryRun,
            int maxChangedBlocks,
            EditPlatform.MutationAdmission admission)
            throws OperationException {
        Objects.requireNonNull(admission, "admission");
        int checkedMaxChangedBlocks = checkedLimit(maxChangedBlocks);
        var paperWorld = edit.paperWorld();
        var world = paperWorld.worldEditWorld();
        SetBlockGeometry geometry = edit.geometry();
        var palettes = edit.preparedPalettes();
        List<ChunkPosition> chunks = geometry.chunks();
        EditSession session = newEditSession(world, !dryRun, checkedMaxChangedBlocks);
        List<PendingBlock> changedBlocks = dryRun ? List.of() : new ArrayList<>();
        long expectedChanges = 0;
        long changes;
        StoredUndo undo = null;
        try {
            try (session) {
                for (SetBlockGeometry.ResolvedPlacement placement : geometry.placements()) {
                    requireNotInterrupted();
                    BlockVector3 position = vector(placement.position());
                    Pattern pattern = palettes.get(placement.paletteIndex()).pattern();
                    BaseBlock replacement = pattern.applyBlock(position);
                    if (!session.getBlock(position).equals(replacement.toBlockState())) {
                        expectedChanges++;
                        enforceChangeLimit(expectedChanges, checkedMaxChangedBlocks);
                        if (!dryRun) {
                            changedBlocks.add(PendingBlock.snapshot(position, replacement));
                        }
                    }
                }
                for (SetBlockGeometry.ResolvedRun run : geometry.runs()) {
                    Pattern pattern = palettes.get(run.paletteIndex()).pattern();
                    for (BlockVector3 position : selection(world, run.region())) {
                        requireNotInterrupted();
                        BaseBlock replacement = pattern.applyBlock(position);
                        if (!session.getBlock(position).equals(replacement.toBlockState())) {
                            expectedChanges++;
                            enforceChangeLimit(expectedChanges, checkedMaxChangedBlocks);
                            if (!dryRun) {
                                changedBlocks.add(PendingBlock.snapshot(position, replacement));
                            }
                        }
                    }
                }
                if (!dryRun && expectedChanges > 0) {
                    requireNotInterrupted();
                    admission.beforeMutation();
                    requireNotInterrupted();
                    applyChanges(session, changedBlocks);
                }
            }
            if (dryRun) {
                changes = expectedChanges;
            } else {
                undo = finalizedUndoOrNull(session, chunks);
                changes = undo == null ? 0 : undo.changedBlockCount();
            }
        } catch (OperationException | RuntimeException exception) {
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
        try (EditSession session =
                newEditSession(world.worldEditWorld(), false, this.maxChangedBlocks)) {
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
        if (dryRun) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            StoredUndo recovery;
            try {
                recovery = finalizedUndoOrNull(failedSession, chunks);
                if (recovery == null) {
                    return;
                }
            } catch (RuntimeException finalizationFailure) {
                if (failure != finalizationFailure) {
                    failure.addSuppressed(finalizationFailure);
                }
                long changes = failedSession.getChangeSet().longSize();
                if (changes == 0) {
                    return;
                }
                recovery =
                        StoredUndo.pending(failedSession.getChangeSet(), changes, chunks, this.log);
                throw new EditRecoveryException(
                        "World edit failed and its undo data could not be finalized",
                        new ErrorDetails.WorldUnavailable.OperationFailed(),
                        failure,
                        recovery);
            }
            try (EditSession rollback =
                    newEditSession(world.worldEditWorld(), false, this.maxChangedBlocks)) {
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
            com.sk89q.worldedit.world.World world, boolean recordHistory, int maxChangedBlocks) {
        var builder =
                WorldEdit.getInstance()
                        .newEditSessionBuilder()
                        .world(world)
                        .maxBlocks(maxChangedBlocks)
                        .allowedRegionsEverywhere()
                        .setSideEffectSet(SideEffectSet.api().without(SideEffect.NEIGHBORS));
        if (recordHistory) {
            // Rollback data must be recorded synchronously with each world change.
            return builder.fastMode(false).combineStages(false).changeSet(false, null).build();
        }
        return builder.fastMode(true).changeSetNull().build();
    }

    private void enforceChangeLimit(long changes, int maxChangedBlocks) throws OperationException {
        if (changes > maxChangedBlocks) {
            throw changeLimit(maxChangedBlocks);
        }
    }

    private int checkedLimit(int requested) {
        if (requested < 1 || requested > this.maxChangedBlocks) {
            throw new IllegalArgumentException("Effective change limit is invalid");
        }
        return requested;
    }

    static void requireNotInterrupted() throws OperationException {
        if (Thread.currentThread().isInterrupted()) {
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE,
                    "World editing was interrupted",
                    new ErrorDetails.WorldUnavailable.Interrupted());
        }
    }

    private static OperationException changeLimit(int maximum) {
        return new OperationException(
                OperationFailure.CHANGE_LIMIT_EXCEEDED,
                "Edit exceeds the maximum of " + maximum + " changed blocks",
                new ErrorDetails.ChangeLimitExceeded(maximum));
    }

    private static CuboidRegion selection(com.sk89q.worldedit.world.World world, Cuboid region) {
        return new CuboidRegion(world, vector(region.min()), vector(region.max()));
    }

    private static BlockVector3 vector(BlockPosition position) {
        return BlockVector3.at(position.x(), position.y(), position.z());
    }

    private static void applyChanges(EditSession session, List<PendingBlock> changes)
            throws OperationException {
        for (PendingBlock change : changes) {
            requireNotInterrupted();
            session.setBlock(change.x(), change.y(), change.z(), change.block());
        }
        requireNotInterrupted();
    }

    private record PendingBlock(int x, int y, int z, BaseBlock block) {
        private static PendingBlock snapshot(BlockVector3 position, BaseBlock block) {
            return new PendingBlock(position.x(), position.y(), position.z(), block);
        }
    }

    private StoredUndo finalizedUndoOrNull(EditSession session, List<ChunkPosition> chunks) {
        ChangeSet changeSet = Objects.requireNonNull(session.getChangeSet(), "changeSet");
        return StoredUndo.finalizedOrNull(changeSet, chunks, this.log);
    }

    static final class StoredUndo implements EditPlatform.UndoToken {
        private final AtomicReference<ChangeSet> changeSet;
        private final long changedBlockCount;
        private final List<ChunkPosition> chunks;
        private final DirtLog log;
        private boolean finalized;

        private StoredUndo(
                ChangeSet changeSet,
                long changedBlockCount,
                List<ChunkPosition> chunks,
                DirtLog log,
                boolean finalized) {
            requirePositiveCount(changedBlockCount);
            this.changeSet = new AtomicReference<>(Objects.requireNonNull(changeSet, "changeSet"));
            this.changedBlockCount = changedBlockCount;
            this.chunks = List.copyOf(chunks);
            this.log = Objects.requireNonNull(log, "log");
            this.finalized = finalized;
        }

        static StoredUndo finalizedOrNull(
                ChangeSet changeSet, List<ChunkPosition> chunks, DirtLog log) {
            ChangeSet retained = Objects.requireNonNull(changeSet, "changeSet");
            Objects.requireNonNull(chunks, "chunks");
            DirtLog retainedLog = Objects.requireNonNull(log, "log");
            finalizeChangeSet(retained);
            long changedBlockCount = retained.longSize();
            if (changedBlockCount == 0) {
                disposeChangeSet(retained, changedBlockCount, chunks, retainedLog);
                return null;
            }
            return new StoredUndo(retained, changedBlockCount, chunks, retainedLog, true);
        }

        static StoredUndo pending(
                ChangeSet changeSet,
                long changedBlockCount,
                List<ChunkPosition> chunks,
                DirtLog log) {
            return new StoredUndo(changeSet, changedBlockCount, chunks, log, false);
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
                disposeChangeSet(retained, this.changedBlockCount, this.chunks, this.log);
            }
        }

        private static void disposeChangeSet(
                ChangeSet changeSet,
                long changedBlockCount,
                List<ChunkPosition> chunks,
                DirtLog log) {
            try {
                changeSet.delete();
            } catch (RuntimeException failure) {
                LogContext context =
                        LogContext.of("changed_block_count", changedBlockCount)
                                .with("chunk_count", chunks.size());
                try {
                    log.warning(
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
