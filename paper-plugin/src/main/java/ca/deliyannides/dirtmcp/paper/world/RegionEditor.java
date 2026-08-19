package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Bounds;
import java.io.Serial;
import java.util.List;

public interface RegionEditor {
    ReplaceRegionBlocksResult replaceRegionBlocks(ReplaceRegionBlocksRequest request) throws EditException;

    FillRegionResult fillRegion(FillRegionRequest request) throws EditException;

    SetBlocksResult setBlocks(SetBlocksRequest request) throws EditException;

    UndoLastDirtEditResult undoLastDirtEdit(UndoLastDirtEditRequest request) throws EditException;

    record ReplaceRegionBlocksRequest(
            String world,
            BlockPosition min,
            BlockPosition max,
            List<String> sourceBlockStatePatterns,
            List<DestinationPaletteEntry> destinationPalette,
            int seed,
            boolean dryRun) {}

    record ReplaceRegionBlocksResult(
            String world,
            Bounds bounds,
            List<String> sourceBlockStatePatterns,
            List<DestinationPaletteEntry> destinationPalette,
            int seed,
            boolean dryRun,
            long matchedBlockCount,
            long changedBlockCount) {}

    record FillRegionRequest(
            String world,
            BlockPosition min,
            BlockPosition max,
            List<DestinationPaletteEntry> destinationPalette,
            int seed,
            boolean dryRun) {}

    record FillRegionResult(
            String world,
            Bounds bounds,
            List<DestinationPaletteEntry> destinationPalette,
            int seed,
            boolean dryRun,
            long volume,
            long changedBlockCount) {}

    record DestinationPaletteEntry(String blockState, Integer weight) {}

    record SetBlocksRequest(
            String world,
            List<BlockChange> changes,
            boolean dryRun) {}

    record BlockChange(BlockPosition position, String blockState) {}

    record SetBlocksResult(
            String world,
            boolean dryRun,
            long blockCount,
            long changedBlockCount,
            long unchangedBlockCount) {}

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
