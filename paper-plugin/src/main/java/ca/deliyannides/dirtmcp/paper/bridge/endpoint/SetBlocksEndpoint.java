package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOutcome;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import java.io.IOException;
import java.util.Objects;

public final class SetBlocksEndpoint implements BridgeEndpoint {
    private final SetBlocks operation;

    public SetBlocksEndpoint(SetBlocks operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operationId() {
        return "setBlocks";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/set-blocks";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        SetBlocks.Request request = SetBlocksRequestDecoder.decode(exchange);
        exchange.auditField("world", request.world());
        SetBlocks.Result result = this.operation.setBlocks(request, exchange.callId());
        exchange.auditField("outcome", result.outcome().wireName());
        exchange.auditField("changed_block_count", result.changedBlockCount());
        if (result.edit() != null) {
            exchange.auditField("edit_id", result.edit().editId());
        }
        exchange.auditCompletion(
                result.outcome() == EditOutcome.COMMITTED
                        ? BridgeExchange.AuditLevel.INFO
                        : BridgeExchange.AuditLevel.DEBUG,
                result.outcome() == EditOutcome.COMMITTED);
        exchange.ok(result);
    }

    @Override
    public String internalErrorMessage() {
        return "The blocks could not be set";
    }
}
