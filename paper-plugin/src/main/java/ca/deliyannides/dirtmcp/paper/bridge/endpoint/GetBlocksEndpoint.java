package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.ExactBlockStructure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetBlocks;
import java.io.IOException;
import java.util.Objects;

public final class GetBlocksEndpoint implements BridgeEndpoint {
    private final GetBlocks operation;

    public GetBlocksEndpoint(GetBlocks operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operationId() {
        return "getBlocks";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/get-blocks";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        GetBlocks.Request request = GetBlocksRequestDecoder.decode(exchange);
        exchange.auditField("world", request.world());
        ExactBlockStructure result = this.operation.getBlocks(request);
        exchange.auditField("result_count", result.blockCount());
        exchange.ok(result);
    }

    @Override
    public String internalErrorMessage() {
        return "The blocks could not be returned";
    }
}
