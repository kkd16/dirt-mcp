package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.List;
import java.util.UUID;

interface EditPlatform extends AutoCloseable {
    WorldHandle resolveWorld(String worldName) throws OperationException;

    PreparedReplace prepareReplace(
            WorldHandle world, ReplaceRegionBlocks.Request request, Cuboid region)
            throws OperationException;

    PreparedFill prepareFill(WorldHandle world, FillRegion.Request request, Cuboid region)
            throws OperationException;

    PreparedSet prepareSet(
            WorldHandle world, SetBlocks.Request request, List<ChunkPosition> touchedChunks)
            throws OperationException;

    EditResult replace(PreparedReplace prepared, Cuboid region, boolean dryRun)
            throws OperationException;

    EditResult fill(PreparedFill prepared, Cuboid region, boolean dryRun) throws OperationException;

    EditResult set(PreparedSet prepared, boolean dryRun) throws OperationException;

    void undo(WorldHandle world, UndoToken undo) throws OperationException;

    default void beginStopping() {}

    @Override
    void close();

    interface WorldHandle {
        UUID id();

        String name();
    }

    @FunctionalInterface
    interface PreparedOperation extends AutoCloseable {
        @Override
        void close() throws OperationException;
    }

    interface PreparedReplace extends PreparedOperation {
        List<String> sourcePatterns();

        List<DestinationPaletteEntry> destinationPalette();
    }

    interface PreparedFill extends PreparedOperation {
        List<DestinationPaletteEntry> destinationPalette();
    }

    interface PreparedSet extends PreparedOperation {
        int blockCount();
    }

    @FunctionalInterface
    interface UndoToken {
        long changedBlockCount();
    }

    record EditResult(long matchedBlockCount, long changedBlockCount, UndoToken undo) {
        public EditResult {
            if (matchedBlockCount < 0 || changedBlockCount < 0) {
                throw new IllegalArgumentException("Edit counts must be non-negative");
            }
        }
    }
}
