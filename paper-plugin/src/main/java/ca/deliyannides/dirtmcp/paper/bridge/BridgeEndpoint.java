package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.io.IOException;
import java.util.Set;

public interface BridgeEndpoint {
    String operationId();

    default boolean isEnabled(Set<BridgeOperation> allowedOperations) {
        return allowedOperations.contains(BridgeOperation.parse(operationId()));
    }

    String method();

    String path();

    void handle(BridgeExchange exchange) throws IOException, OperationException;

    String internalErrorMessage();
}
