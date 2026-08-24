package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.CountRegionBlockStates;
import java.io.IOException;
import java.util.Objects;

public final class CountRegionBlockStatesEndpoint implements BridgeEndpoint {
    private final CountRegionBlockStates operation;

    public CountRegionBlockStatesEndpoint(CountRegionBlockStates operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operationId() {
        return "countRegionBlockStates";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/count-region-block-states";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        CountRegionBlockStates.Request request =
                CountRegionBlockStatesRequestDecoder.decode(exchange);
        exchange.auditField("world", request.world());
        CountRegionBlockStates.Result result = this.operation.countRegionBlockStates(request);
        exchange.auditField("result_count", result.blockStateCounts().size());
        exchange.ok(result);
    }

    @Override
    public String internalErrorMessage() {
        return "The region's block states could not be counted";
    }
}
