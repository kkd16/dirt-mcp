package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperFaweEditPlatform implements EditPlatform {
    private final PaperEditPreparation preparation;
    private final FaweEditExecutor executor;

    PaperFaweEditPlatform(
            JavaPlugin plugin, MainThread mainThread, int maxChangedBlocks, DirtLog log) {
        this.preparation = new PaperEditPreparation(plugin, mainThread);
        this.executor = new FaweEditExecutor(maxChangedBlocks, log);
    }

    @Override
    public WorldHandle resolveWorld(String worldName) throws OperationException {
        return this.preparation.resolveWorld(worldName);
    }

    @Override
    public PreparedReplace prepareReplace(
            WorldHandle world, ReplaceRegionBlocks.Request request, Cuboid region)
            throws OperationException {
        return this.preparation.prepareReplace(requireWorld(world), request, region);
    }

    @Override
    public PreparedFill prepareFill(WorldHandle world, FillRegion.Request request, Cuboid region)
            throws OperationException {
        return this.preparation.prepareFill(requireWorld(world), request, region);
    }

    @Override
    public PreparedSet prepareSet(
            WorldHandle world,
            SetBlocks.Request request,
            List<SetBlocks.ResolvedBlock> resolvedBlocks,
            BlockBounds bounds,
            List<ChunkPosition> touchedChunks)
            throws OperationException {
        return this.preparation.prepareSet(
                requireWorld(world), request, resolvedBlocks, bounds, touchedChunks);
    }

    @Override
    public EditResult replace(
            PreparedReplace prepared, Cuboid region, boolean dryRun, MutationAdmission admission)
            throws OperationException {
        return this.executor.replace(requireReplace(prepared), region, dryRun, admission);
    }

    @Override
    public EditResult fill(
            PreparedFill prepared, Cuboid region, boolean dryRun, MutationAdmission admission)
            throws OperationException {
        return this.executor.fill(requireFill(prepared), region, dryRun, admission);
    }

    @Override
    public EditResult set(PreparedSet prepared, boolean dryRun, MutationAdmission admission)
            throws OperationException {
        return this.executor.set(requireSet(prepared), dryRun, admission);
    }

    @Override
    @SuppressWarnings("try")
    public void undo(WorldHandle world, UndoToken undo) throws OperationException {
        PaperEditPreparation.PaperWorld paperWorld = requireWorld(world);
        FaweEditExecutor.StoredUndo stored = requireUndo(undo);
        try (ChunkTicketManager.Lease ignored =
                this.preparation.prepareUndo(paperWorld, stored.chunks())) {
            this.executor.undo(paperWorld, stored);
        }
    }

    @Override
    public void rollbackPrepared(PreparedOperation prepared, UndoToken undo)
            throws OperationException {
        this.executor.rollback(requirePreparedWorld(prepared), requireUndo(undo));
    }

    @Override
    public void beginStopping() {
        this.preparation.beginStopping();
    }

    @Override
    public void close() {
        this.preparation.close();
    }

    private static PaperEditPreparation.PaperWorld requireWorld(WorldHandle world) {
        if (world instanceof PaperEditPreparation.PaperWorld paperWorld) {
            return paperWorld;
        }
        throw new IllegalArgumentException("World handle was not created by this edit platform");
    }

    private static PaperEditPreparation.PreparedReplace requireReplace(PreparedReplace prepared) {
        if (prepared instanceof PaperEditPreparation.PreparedReplace edit) {
            return edit;
        }
        throw new IllegalArgumentException("Prepared edit belongs to another platform");
    }

    private static PaperEditPreparation.PreparedFill requireFill(PreparedFill prepared) {
        if (prepared instanceof PaperEditPreparation.PreparedFill edit) {
            return edit;
        }
        throw new IllegalArgumentException("Prepared edit belongs to another platform");
    }

    private static PaperEditPreparation.PreparedSet requireSet(PreparedSet prepared) {
        if (prepared instanceof PaperEditPreparation.PreparedSet edit) {
            return edit;
        }
        throw new IllegalArgumentException("Prepared edit belongs to another platform");
    }

    private static FaweEditExecutor.StoredUndo requireUndo(UndoToken undo) {
        if (undo instanceof FaweEditExecutor.StoredUndo stored) {
            return stored;
        }
        throw new IllegalArgumentException("Undo token was not created by this edit platform");
    }

    private static PaperEditPreparation.PaperWorld requirePreparedWorld(
            PreparedOperation prepared) {
        return switch (prepared) {
            case PaperEditPreparation.PreparedReplace edit -> edit.paperWorld();
            case PaperEditPreparation.PreparedFill edit -> edit.paperWorld();
            case PaperEditPreparation.PreparedSet edit -> edit.paperWorld();
            default ->
                    throw new IllegalArgumentException(
                            "Prepared edit was not created by this edit platform");
        };
    }
}
