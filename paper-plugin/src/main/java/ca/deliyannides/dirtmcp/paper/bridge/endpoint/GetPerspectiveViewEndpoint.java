package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView;
import java.io.IOException;
import java.util.Objects;

public final class GetPerspectiveViewEndpoint implements BridgeEndpoint {
    private final GetPerspectiveView operation;

    public GetPerspectiveViewEndpoint(GetPerspectiveView operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operation() {
        return "get_perspective_view";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/get-perspective-view";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        GetPerspectiveView.Result result =
                this.operation.getPerspectiveView(
                        GetPerspectiveViewRequestDecoder.decode(exchange));
        exchange.world(result.world());
        exchange.ok(result);
    }

    @Override
    public String internalErrorMessage() {
        return "The perspective view could not be returned";
    }
}
