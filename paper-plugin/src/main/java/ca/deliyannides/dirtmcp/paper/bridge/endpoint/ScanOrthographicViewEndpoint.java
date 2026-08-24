package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView;
import java.io.IOException;
import java.util.Objects;

public final class ScanOrthographicViewEndpoint implements BridgeEndpoint {
    private final ScanOrthographicView operation;

    public ScanOrthographicViewEndpoint(ScanOrthographicView operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operationId() {
        return "scanOrthographicView";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/scan-orthographic-view";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        ScanOrthographicView.Request request = ScanOrthographicViewRequestDecoder.decode(exchange);
        exchange.auditField("world", request.world());
        var result = this.operation.scanOrthographicView(request);
        exchange.auditField("result_count", result.blockCount());
        exchange.ok(result);
    }

    @Override
    public String internalErrorMessage() {
        return "The orthographic view could not be scanned";
    }
}
