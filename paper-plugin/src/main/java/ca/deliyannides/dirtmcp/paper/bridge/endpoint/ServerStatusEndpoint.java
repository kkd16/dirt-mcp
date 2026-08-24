package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus;
import java.io.IOException;
import java.util.Objects;

public final class ServerStatusEndpoint implements BridgeEndpoint {
    private final GetServerStatus operation;

    public ServerStatusEndpoint(GetServerStatus operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operationId() {
        return "getServerStatus";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/server-status";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        exchange.ok(this.operation.getStatus(ServerStatusRequestDecoder.decode(exchange)));
    }

    @Override
    public String internalErrorMessage() {
        return "Server status could not be returned";
    }
}
