package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
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

    /**
     * Prepares a validated set request. Resolved blocks are absolute and retain request expansion
     * order; bounds and touched chunks cover those same blocks.
     */
    PreparedSet prepareSet(
            WorldHandle world,
            SetBlocks.Request request,
            List<SetBlocks.ResolvedBlock> resolvedBlocks,
            BlockBounds bounds,
            List<ChunkPosition> touchedChunks)
            throws OperationException;

    EditResult replace(
            PreparedReplace prepared, Cuboid region, boolean dryRun, MutationAdmission admission)
            throws OperationException;

    EditResult fill(
            PreparedFill prepared, Cuboid region, boolean dryRun, MutationAdmission admission)
            throws OperationException;

    EditResult set(PreparedSet prepared, boolean dryRun, MutationAdmission admission)
            throws OperationException;

    void undo(WorldHandle world, UndoToken undo) throws OperationException;

    /**
     * Rolls back an edit while its prepared operation, including its chunk-ticket lease, remains
     * open. This path must not reacquire chunks or reject recovery solely because shutdown has
     * begun.
     */
    void rollbackPrepared(PreparedOperation prepared, UndoToken undo) throws OperationException;

    void beginStopping();

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
        List<List<DestinationPaletteEntry>> palettes();

        int blockCount();
    }

    interface UndoToken extends AutoCloseable {
        long changedBlockCount();

        /**
         * Releases the retained undo data. Implementations must be idempotent and must not throw.
         */
        @Override
        void close();
    }

    @FunctionalInterface
    interface MutationAdmission {
        void beforeMutation() throws OperationException;
    }

    record EditResult(long matchedBlockCount, long changedBlockCount, UndoToken undo) {
        public EditResult {
            if (matchedBlockCount < 0 || changedBlockCount < 0) {
                throw new IllegalArgumentException("Edit counts must be non-negative");
            }
        }
    }
}
