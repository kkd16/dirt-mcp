package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.status.PingServer;
import java.io.IOException;

public final class PingEndpoint implements BridgeEndpoint {
    private final PingServer operation;

    public PingEndpoint(PingServer operation) {
        this.operation = operation;
    }

    @Override
    public String operation() {
        return "ping_server";
    }

    @Override
    public String method() {
        return "GET";
    }

    @Override
    public String path() {
        return "/v1/ping";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        exchange.ok(this.operation.ping());
    }

    @Override
    public String internalErrorMessage() {
        return "The end-to-end health check failed";
    }
}
