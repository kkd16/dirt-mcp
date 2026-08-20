package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.io.IOException;

public interface BridgeEndpoint {
    String operation();

    String method();

    String path();

    void handle(BridgeExchange exchange)
            throws IOException, InvalidRequestException, OperationException;

    String internalErrorMessage();
}
