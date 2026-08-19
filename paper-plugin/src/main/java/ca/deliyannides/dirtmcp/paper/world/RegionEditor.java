package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Bounds;
import java.io.Serial;

public interface RegionEditor {
    ReplaceRegionBlocksResult replaceRegionBlocks(ReplaceRegionBlocksRequest request) throws EditException;

    FillRegionResult fillRegion(FillRegionRequest request) throws EditException;

    UndoLastDirtEditResult undoLastDirtEdit(UndoLastDirtEditRequest request) throws EditException;

    record ReplaceRegionBlocksRequest(
            String world,
            BlockPosition min,
            BlockPosition max,
            String sourceBlockState,
            String destinationBlockState,
            boolean dryRun) {}

    record ReplaceRegionBlocksResult(
            String world,
            Bounds bounds,
            String sourceBlockState,
            String destinationBlockState,
            boolean dryRun,
            long matchedBlockCount,
            long changedBlockCount) {}

    record FillRegionRequest(
            String world,
            BlockPosition min,
            BlockPosition max,
            String blockState,
            boolean dryRun) {}

    record FillRegionResult(
            String world,
            Bounds bounds,
            String blockState,
            boolean dryRun,
            long volume,
            long changedBlockCount) {}

    record UndoLastDirtEditRequest(String world) {}

    record UndoLastDirtEditResult(String world, long changedBlockCount) {}

    enum Failure {
        CHANGE_LIMIT_EXCEEDED,
        INVALID_REQUEST,
        NOTHING_TO_UNDO,
        REGION_TOO_LARGE,
        WORLD_BUSY,
        WORLD_NOT_FOUND,
        WORLD_UNAVAILABLE
    }

    final class EditException extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Failure failure;

        public EditException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public EditException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        public Failure failure() {
            return this.failure;
        }
    }
}
