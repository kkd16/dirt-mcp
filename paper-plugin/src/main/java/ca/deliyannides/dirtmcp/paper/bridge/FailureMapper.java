package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.io.IOException;
import java.util.Locale;

final class FailureMapper {
    private FailureMapper() {}

    static void send(BridgeExchange exchange, OperationException exception) throws IOException {
        OperationFailure failure = exception.failure();
        int status =
                switch (failure) {
                    case INVALID_REQUEST -> 400;
                    case WORLD_NOT_FOUND -> 404;
                    case NOTHING_TO_UNDO, WORLD_BUSY -> 409;
                    case CHANGE_LIMIT_EXCEEDED, REGION_TOO_LARGE, RESULT_TOO_LARGE -> 413;
                    case SERVER_UNAVAILABLE, UNHEALTHY, WORLD_UNAVAILABLE -> 503;
                };
        exchange.sendError(status, failure.name().toLowerCase(Locale.ROOT), exception.getMessage());
    }
}
