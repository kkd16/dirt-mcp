package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext;
import java.io.IOException;
import java.util.Objects;

public final class GetPlayerContextEndpoint implements BridgeEndpoint {
    private final GetPlayerContext operation;

    public GetPlayerContextEndpoint(GetPlayerContext operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operationId() {
        return "getPlayerContext";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/get-player-context";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        GetPlayerContext.Result result =
                this.operation.getPlayerContext(GetPlayerContextRequestDecoder.decode(exchange));
        exchange.auditField("world", result.world());
        exchange.ok(result);
    }

    @Override
    public String internalErrorMessage() {
        return "The player context could not be returned";
    }
}
