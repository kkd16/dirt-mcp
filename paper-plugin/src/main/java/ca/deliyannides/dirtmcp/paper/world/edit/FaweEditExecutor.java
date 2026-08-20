package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.function.mask.BlockMask;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockState;
import java.util.ArrayList;
import java.util.List;

final class FaweEditExecutor {
    private final int maxChangedBlocks;

    FaweEditExecutor(int maxChangedBlocks) {
        if (maxChangedBlocks < 1) {
            throw new IllegalArgumentException("Maximum changed blocks must be positive");
        }
        this.maxChangedBlocks = maxChangedBlocks;
    }

    EditPlatform.EditResult replace(
            PaperEditPreparation.PreparedReplace edit, Cuboid region, boolean dryRun)
            throws OperationException {
        CuboidRegion selection = selection(edit.paperWorld().worldEditWorld(), region);
        EditSession session = newEditSession(edit.paperWorld().worldEditWorld(), !dryRun);
        long matches = 0;
        long expectedChanges = 0;
        long changes;
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
                if (!current.equals(edit.palette().pattern().applyBlock(position).toBlockState())) {
                    expectedChanges++;
                }
            }
            enforceChangeLimit(expectedChanges);
            changes = expectedChanges;
            if (!dryRun && expectedChanges > 0) {
                requireNotInterrupted();
                session.replaceBlocks(selection, sourceMask, edit.palette().pattern());
                changes = session.getChangeSet().longSize();
            }
        } catch (MaxChangedBlocksException exception) {
            throw changeLimit(exception);
        }
        EditPlatform.UndoToken undo =
                !dryRun && changes > 0 ? new StoredUndo(session, changes, edit.chunks()) : null;
        return new EditPlatform.EditResult(matches, changes, undo);
    }

    EditPlatform.EditResult fill(
            PaperEditPreparation.PreparedFill edit, Cuboid region, boolean dryRun)
            throws OperationException {
        CuboidRegion selection = selection(edit.paperWorld().worldEditWorld(), region);
        EditSession session = newEditSession(edit.paperWorld().worldEditWorld(), !dryRun);
        long expectedChanges = 0;
        long changes;
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
                session.setBlocks(
                        (com.sk89q.worldedit.regions.Region) selection, edit.palette().pattern());
                changes = session.getChangeSet().longSize();
            }
        } catch (MaxChangedBlocksException exception) {
            throw changeLimit(exception);
        }
        EditPlatform.UndoToken undo =
                !dryRun && changes > 0 ? new StoredUndo(session, changes, edit.chunks()) : null;
        return new EditPlatform.EditResult(0, changes, undo);
    }

    EditPlatform.EditResult set(PaperEditPreparation.PreparedSet edit, boolean dryRun)
            throws OperationException {
        EditSession session = newEditSession(edit.paperWorld().worldEditWorld(), !dryRun);
        List<PaperEditPreparation.PreparedBlockChange> pending = new ArrayList<>();
        long expectedChanges;
        try (session) {
            for (PaperEditPreparation.PreparedBlockChange change : edit.changes()) {
                requireNotInterrupted();
                if (!session.getBlock(change.position()).equals(change.blockState())) {
                    pending.add(change);
                }
            }
            expectedChanges = pending.size();
            enforceChangeLimit(expectedChanges);
            if (!dryRun) {
                requireNotInterrupted();
                for (PaperEditPreparation.PreparedBlockChange change : pending) {
                    session.setBlock(
                            change.position().x(),
                            change.position().y(),
                            change.position().z(),
                            change.blockState());
                }
            }
        } catch (MaxChangedBlocksException exception) {
            throw changeLimit(exception);
        }
        long changes = dryRun ? expectedChanges : session.getChangeSet().longSize();
        EditPlatform.UndoToken undo =
                !dryRun && changes > 0 ? new StoredUndo(session, changes, edit.chunks()) : null;
        return new EditPlatform.EditResult(0, changes, undo);
    }

    void undo(PaperEditPreparation.PaperWorld world, StoredUndo undo) throws OperationException {
        requireNotInterrupted();
        try (EditSession session = newEditSession(world.worldEditWorld(), false)) {
            undo.session().undo(session);
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

    record StoredUndo(EditSession session, long changedBlockCount, List<ChunkPosition> chunks)
            implements EditPlatform.UndoToken {}
}
