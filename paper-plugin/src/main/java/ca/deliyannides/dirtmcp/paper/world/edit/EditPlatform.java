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

    PreparedSet prepareSet(WorldHandle world, SetBlocks.Request request, SetBlockGeometry geometry)
            throws OperationException;

    EditResult replace(
            PreparedReplace prepared,
            Cuboid region,
            boolean dryRun,
            int maxChangedBlocks,
            MutationAdmission admission)
            throws OperationException;

    EditResult set(
            PreparedSet prepared, boolean dryRun, int maxChangedBlocks, MutationAdmission admission)
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
        void close();
    }

    interface PreparedReplace extends PreparedOperation {
        List<String> sourcePatterns();

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
