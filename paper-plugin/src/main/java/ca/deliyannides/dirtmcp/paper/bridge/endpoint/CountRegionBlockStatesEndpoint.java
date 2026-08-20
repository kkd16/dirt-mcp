package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
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
    public String operation() {
        return "count_region_block_states";
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
    public void handle(BridgeExchange exchange)
            throws IOException, InvalidRequestException, OperationException {
        CountRegionBlockStates.Request request =
                CountRegionBlockStatesRequestDecoder.decode(exchange);
        exchange.world(request.world());
        exchange.ok(this.operation.countRegionBlockStates(request));
    }

    @Override
    public String internalErrorMessage() {
        return "The region's block states could not be counted";
    }
}
