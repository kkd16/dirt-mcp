package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.GetEditHistory;
import java.io.IOException;
import java.util.Objects;

public final class GetEditHistoryEndpoint implements BridgeEndpoint {
    private final GetEditHistory operation;

    public GetEditHistoryEndpoint(GetEditHistory operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operationId() {
        return "getEditHistory";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/get-edit-history";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        GetEditHistory.Request request = GetEditHistoryRequestDecoder.decode(exchange);
        exchange.auditField("world", request.world());
        GetEditHistory.Result result = this.operation.getEditHistory(request);
        exchange.auditField("result_count", result.edits().size());
        exchange.ok(result);
    }

    @Override
    public String internalErrorMessage() {
        return "Edit history could not be read";
    }
}
