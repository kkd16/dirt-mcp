package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEditsException;
import java.io.IOException;
import java.util.UUID;

final class FailureMapper {
    private FailureMapper() {}

    static void send(BridgeExchange exchange, OperationException exception) throws IOException {
        OperationFailure failure = exception.failure();
        int status =
                switch (failure) {
                    case INVALID_REQUEST -> 400;
                    case INTERNAL_ERROR -> 500;
                    case EDIT_NOT_FOUND, PLAYER_NOT_FOUND, WORLD_NOT_FOUND -> 404;
                    case EDIT_NOT_LATEST, PLAYER_UNAVAILABLE, WORLD_BUSY -> 409;
                    case CHANGE_LIMIT_EXCEEDED, REGION_TOO_LARGE, RESULT_TOO_LARGE -> 413;
                    case HISTORY_CAPACITY_EXCEEDED,
                            SERVER_UNAVAILABLE,
                            UNHEALTHY,
                            WORLD_UNAVAILABLE ->
                            503;
                };
        if (exception instanceof UndoEditsException batch) {
            UUID failedEditId = exception.editId().orElseThrow();
            if (failure == OperationFailure.INTERNAL_ERROR) {
                exchange.sendInternalUndoError(
                        status, exception.getMessage(), failedEditId, batch.undoneEdits());
            } else {
                exchange.sendUndoError(
                        status,
                        exception.getMessage(),
                        exception.details().orElseThrow(),
                        failedEditId,
                        batch.undoneEdits());
            }
            return;
        }
        if (failure == OperationFailure.INTERNAL_ERROR) {
            if (exception.editId().isPresent()) {
                exchange.sendInternalError(
                        status, exception.getMessage(), exception.editId().orElseThrow());
            } else {
                exchange.sendInternalError(status, exception.getMessage());
            }
            return;
        }
        var details = exception.details().orElseThrow();
        if (exception.editId().isPresent()) {
            exchange.sendError(
                    status, exception.getMessage(), details, exception.editId().orElseThrow());
        } else {
            exchange.sendError(status, exception.getMessage(), details);
        }
    }
}
