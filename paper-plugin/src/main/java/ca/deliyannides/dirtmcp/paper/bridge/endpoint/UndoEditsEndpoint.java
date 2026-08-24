package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.ErrorDetailsJson;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.edit.EditRecord;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdits;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class UndoEditsEndpoint implements BridgeEndpoint {
    private final UndoEdits operation;

    public UndoEditsEndpoint(UndoEdits operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operationId() {
        return "undoEdits";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/undo-edits";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        UndoEdits.Request request = UndoEditsRequestDecoder.decode(exchange);
        exchange.auditField("world", request.world());
        UndoEdits.Result result = this.operation.undoEdits(request, exchange.callId());
        switch (result) {
            case UndoEdits.Completed completed -> {
                captureAudit(exchange, "completed", completed.undoneEdits(), false);
                exchange.ok(
                        new CompletedResponse(
                                "completed",
                                completed.world(),
                                completed.undoneEdits(),
                                completed.undoCallId(),
                                completed.undoneAt()));
            }
            case UndoEdits.Partial partial -> {
                exchange.auditFailure(partial.failure());
                captureAudit(exchange, "partial", partial.undoneEdits(), true);
                exchange.auditField("edit_id", partial.failure().editId().orElseThrow());
                exchange.ok(
                        new PartialResponse(
                                "partial",
                                partial.world(),
                                partial.undoCallId(),
                                partial.undoneEdits(),
                                failure(partial.failure())));
            }
        }
    }

    @Override
    public String internalErrorMessage() {
        return "The edits could not be undone";
    }

    private static void captureAudit(
            BridgeExchange exchange,
            String outcome,
            List<EditRecord> undoneEdits,
            boolean partial) {
        exchange.auditField("outcome", outcome);
        exchange.auditField("result_count", undoneEdits.size());
        long changedBlockCount = 0;
        for (EditRecord edit : undoneEdits) {
            changedBlockCount = Math.addExact(changedBlockCount, edit.changedBlockCount());
        }
        exchange.auditField("changed_block_count", changedBlockCount);
        exchange.auditCompletion(
                partial ? BridgeExchange.AuditLevel.WARNING : BridgeExchange.AuditLevel.INFO, true);
    }

    private static Object failure(OperationException failure) {
        UUID editId = failure.editId().orElseThrow();
        if (failure.failure() == OperationFailure.INTERNAL_ERROR) {
            return new InternalFailure("internal_error", failure.getMessage(), editId);
        }
        JsonObject details = ErrorDetailsJson.serialize(failure.details().orElseThrow());
        return new DetailedFailure(
                ErrorDetailsJson.code(failure.details().orElseThrow()),
                failure.getMessage(),
                details,
                editId);
    }

    private record CompletedResponse(
            String outcome,
            String world,
            List<EditRecord> undoneEdits,
            UUID undoCallId,
            Instant undoneAt) {}

    private record PartialResponse(
            String outcome,
            String world,
            UUID undoCallId,
            List<EditRecord> undoneEdits,
            Object failure) {}

    private record InternalFailure(String code, String message, UUID editId) {}

    private record DetailedFailure(String code, String message, JsonObject details, UUID editId) {}
}
