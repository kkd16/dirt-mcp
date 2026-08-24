package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeOperation;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reports the operations admitted by the active Paper bridge configuration. */
public final class CapabilitiesEndpoint implements BridgeEndpoint {
    private final Result result;

    public CapabilitiesEndpoint(List<BridgeOperation> operations) {
        Objects.requireNonNull(operations, "operations");
        Set<BridgeOperation> enabled = Set.copyOf(operations);
        this.result =
                new Result(
                        Arrays.stream(BridgeOperation.values())
                                .filter(enabled::contains)
                                .map(BridgeOperation::operationId)
                                .toList());
    }

    @Override
    public String operationId() {
        return "getCapabilities";
    }

    @Override
    public boolean isEnabled(Set<BridgeOperation> ignoredOperations) {
        return true;
    }

    @Override
    public String method() {
        return "GET";
    }

    @Override
    public String path() {
        return "/v1/capabilities";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException {
        exchange.ok(this.result);
    }

    @Override
    public String internalErrorMessage() {
        return "Bridge capabilities could not be returned";
    }

    public record Result(List<String> operations) {
        public Result {
            operations = List.copyOf(operations);
        }
    }
}
