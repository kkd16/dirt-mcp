package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Bounds;
import java.io.Serial;

public interface RegionEditor {
    ReplaceResult replace(ReplaceRequest request) throws EditException;

    UndoResult undo(UndoRequest request) throws EditException;

    record ReplaceRequest(
            String world,
            BlockPosition min,
            BlockPosition max,
            String source,
            String destination,
            boolean dryRun) {}

    record ReplaceResult(
            String world,
            Bounds bounds,
            String source,
            String destination,
            boolean dryRun,
            long matchedBlocks,
            long changedBlocks) {}

    record UndoRequest(String world) {}

    record UndoResult(String world, long changedBlocks) {}

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
