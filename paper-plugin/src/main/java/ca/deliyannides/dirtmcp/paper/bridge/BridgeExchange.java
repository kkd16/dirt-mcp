package ca.deliyannides.dirtmcp.paper.bridge;

import static ca.deliyannides.dirtmcp.paper.bridge.RequestJson.invalid;

import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import ca.deliyannides.dirtmcp.paper.world.edit.EditRecord;
import ca.deliyannides.dirtmcp.paper.world.edit.GetEditHistory;
import ca.deliyannides.dirtmcp.paper.world.edit.ReplaceRegionBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdits;
import ca.deliyannides.dirtmcp.paper.world.inspection.CountRegionBlockStates;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetBlocks;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class BridgeExchange {
    private final HttpExchange exchange;
    private final int maximumRequestBytes;
    private final RequestBodyReader bodyReader;
    private Integer status;
    private String world;
    private Long requestBytes;
    private Long responseBytes;
    private String errorCode;
    private UUID editId;
    private String outcome;
    private Long changedBlockCount;
    private Long resultCount;
    private String bounds;

    BridgeExchange(HttpExchange exchange, int maximumRequestBytes, RequestBodyReader bodyReader) {
        this.exchange = exchange;
        this.maximumRequestBytes = maximumRequestBytes;
        this.bodyReader = bodyReader;
    }

    public JsonObject readJsonObject() throws IOException, OperationException {
        String contentType = this.exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !contentType
                        .split(";", 2)[0]
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .equals("application/json")) {
            throw invalid(
                    "Content-Type must be application/json",
                    new ErrorDetails.InvalidRequest.UnsupportedMediaType("application/json"));
        }
        validateContentLength();
        byte[] body =
                this.bodyReader.read(this.exchange.getRequestBody(), this.maximumRequestBytes);
        this.requestBytes = (long) body.length;
        if (body.length > this.maximumRequestBytes) {
            throw invalid(
                    "Request body exceeds the maximum of " + this.maximumRequestBytes + " bytes",
                    new ErrorDetails.InvalidRequest.BodyTooLarge(this.maximumRequestBytes));
        }
        try {
            String json =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(body))
                            .toString();
            JsonElement document = JsonParser.parseString(json);
            if (!document.isJsonObject()) {
                throw invalid(
                        "Request body must be a JSON object",
                        new ErrorDetails.InvalidRequest.InvalidValue("body"));
            }
            return document.getAsJsonObject();
        } catch (CharacterCodingException | JsonParseException exception) {
            throw invalid(
                    "Request body must contain valid JSON values",
                    new ErrorDetails.InvalidRequest.MalformedJson());
        }
    }

    private void validateContentLength() throws OperationException {
        String contentLength = this.exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength == null) {
            return;
        }
        try {
            long declared = Long.parseLong(contentLength);
            if (declared < 0) {
                throw new NumberFormatException("negative content length");
            }
            this.requestBytes = declared;
            if (declared > this.maximumRequestBytes) {
                throw invalid(
                        "Request body exceeds the maximum of "
                                + this.maximumRequestBytes
                                + " bytes",
                        new ErrorDetails.InvalidRequest.BodyTooLarge(this.maximumRequestBytes));
            }
        } catch (NumberFormatException exception) {
            throw invalid(
                    "Content-Length must be a non-negative integer",
                    new ErrorDetails.InvalidRequest.InvalidValue("Content-Length"));
        }
    }

    public void world(String world) {
        this.world = world;
    }

    public void bounds(BlockPosition first, BlockPosition second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        BlockPosition minimum =
                new BlockPosition(
                        Math.min(first.x(), second.x()),
                        Math.min(first.y(), second.y()),
                        Math.min(first.z(), second.z()));
        BlockPosition maximum =
                new BlockPosition(
                        Math.max(first.x(), second.x()),
                        Math.max(first.y(), second.y()),
                        Math.max(first.z(), second.z()));
        this.bounds = bounds(new BlockBounds(minimum, maximum));
    }

    public UUID requiredCallId() throws OperationException {
        String value = this.exchange.getRequestHeaders().getFirst("X-Dirt-Call-Id");
        try {
            return UuidV4.parseCanonical(value, "X-Dirt-Call-Id");
        } catch (IllegalArgumentException exception) {
            throw invalid(
                    exception.getMessage(),
                    new ErrorDetails.InvalidRequest.InvalidValue("X-Dirt-Call-Id"));
        }
    }

    public void ok(Object body) throws IOException {
        send(200, body);
    }

    void sendError(int status, String message, ErrorDetails details) throws IOException {
        sendErrorEnvelope(status, ErrorDetailsJson.code(details), message, details, null, null);
    }

    void sendError(int status, String message, ErrorDetails details, UUID editId)
            throws IOException {
        sendErrorEnvelope(
                status,
                ErrorDetailsJson.code(details),
                message,
                details,
                Objects.requireNonNull(editId, "editId"),
                null);
    }

    void sendUndoError(
            int status,
            String message,
            ErrorDetails details,
            UUID editId,
            List<EditRecord> undoneEdits)
            throws IOException {
        sendErrorEnvelope(
                status,
                ErrorDetailsJson.code(details),
                message,
                details,
                Objects.requireNonNull(editId, "editId"),
                Objects.requireNonNull(undoneEdits, "undoneEdits"));
    }

    void sendInternalError(int status, String message) throws IOException {
        sendErrorEnvelope(status, "internal_error", message, null, null, null);
    }

    void sendInternalError(int status, String message, UUID editId) throws IOException {
        sendErrorEnvelope(
                status,
                "internal_error",
                message,
                null,
                Objects.requireNonNull(editId, "editId"),
                null);
    }

    void sendInternalUndoError(
            int status, String message, UUID editId, List<EditRecord> undoneEdits)
            throws IOException {
        sendErrorEnvelope(
                status,
                "internal_error",
                message,
                null,
                Objects.requireNonNull(editId, "editId"),
                Objects.requireNonNull(undoneEdits, "undoneEdits"));
    }

    private void sendErrorEnvelope(
            int status,
            String code,
            String message,
            ErrorDetails details,
            UUID editId,
            List<EditRecord> undoneEdits)
            throws IOException {
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("Error message must not be empty");
        }
        UUID checkedEditId = editId == null ? null : UuidV4.require(editId, "editId");
        this.errorCode = code;
        JsonObject detail = new JsonObject();
        detail.addProperty("code", code);
        detail.addProperty("message", message);
        if (details != null) {
            detail.add("details", ErrorDetailsJson.serialize(details));
        }
        if (checkedEditId != null) {
            this.editId = checkedEditId;
            detail.addProperty("editId", checkedEditId.toString());
        }
        JsonObject envelope = new JsonObject();
        envelope.add("error", detail);
        if (undoneEdits != null) {
            envelope.add("undoneEdits", BridgeJson.GSON.toJsonTree(undoneEdits));
            this.resultCount = (long) undoneEdits.size();
            this.changedBlockCount = changedBlockCount(undoneEdits);
            this.outcome = "partial_failure";
        }
        send(status, envelope);
    }

    void allow(String method) {
        this.exchange.getResponseHeaders().set("Allow", method);
    }

    void challenge() {
        this.exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"dirt-mcp\"");
    }

    void abort(String errorCode) {
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.exchange.close();
    }

    Integer status() {
        return this.status;
    }

    String world() {
        return this.world;
    }

    Long requestBytes() {
        return this.requestBytes;
    }

    Long responseBytes() {
        return this.responseBytes;
    }

    String errorCode() {
        return this.errorCode;
    }

    UUID editId() {
        return this.editId;
    }

    String outcome() {
        return this.outcome;
    }

    Long changedBlockCount() {
        return this.changedBlockCount;
    }

    Long resultCount() {
        return this.resultCount;
    }

    String bounds() {
        return this.bounds;
    }

    private void send(int status, Object body) throws IOException {
        captureResultMetadata(body);
        byte[] bytes = BridgeJson.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        this.status = status;
        this.responseBytes = (long) bytes.length;
        this.exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        this.exchange.getResponseHeaders().set("Cache-Control", "no-store");
        this.exchange.sendResponseHeaders(status, bytes.length);

        try (var output = this.exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void captureResultMetadata(Object body) {
        switch (body) {
            case ReplaceRegionBlocks.Result result ->
                    captureEdit(
                            result.bounds(),
                            result.outcome().wireName(),
                            result.changedBlockCount(),
                            result.edit() == null ? null : result.edit().editId(),
                            result.matchedBlockCount());
            case SetBlocks.Result result ->
                    captureEdit(
                            result.bounds(),
                            result.outcome().wireName(),
                            result.changedBlockCount(),
                            result.edit() == null ? null : result.edit().editId(),
                            null);
            case UndoEdits.Result result -> {
                this.outcome = "undone";
                this.changedBlockCount = changedBlockCount(result.edits());
                this.resultCount = (long) result.edits().size();
            }
            case GetEditHistory.Result result -> this.resultCount = (long) result.edits().size();
            case CountRegionBlockStates.Result result -> {
                this.bounds = bounds(result.bounds());
                this.resultCount = (long) result.blockStateCounts().size();
            }
            case GetBlocks.Result result -> this.resultCount = result.blockCount();
            case ScanOrthographicView.Result result -> {
                this.bounds = bounds(result.bounds());
                this.resultCount = result.visibleBlockCount();
            }
            case GetPlayerContext.Result ignored -> {
                // Player context does not expose a result collection.
            }
            case GetPerspectiveView.Result result -> this.resultCount = (long) result.hits().size();
            case RunMinecraftCommands.Result result -> {
                this.resultCount = (long) result.results().size();
                this.outcome =
                        result.results().getLast().outcome()
                                        == RunMinecraftCommands.Outcome.DISPATCHED
                                ? "dispatched"
                                : "partial_failure";
            }
            default -> {
                // Ping, status, and error envelopes do not add result metadata.
            }
        }
    }

    private void captureEdit(
            BlockBounds editBounds,
            String editOutcome,
            long changedCount,
            UUID retainedEditId,
            Long count) {
        UUID checkedEditId =
                retainedEditId == null ? null : UuidV4.require(retainedEditId, "editId");
        this.bounds = bounds(editBounds);
        this.outcome = editOutcome;
        this.changedBlockCount = changedCount;
        this.editId = checkedEditId;
        this.resultCount = count;
    }

    private static String bounds(BlockBounds value) {
        if (value == null) {
            return null;
        }
        return value.min().x()
                + ","
                + value.min().y()
                + ","
                + value.min().z()
                + ".."
                + value.max().x()
                + ","
                + value.max().y()
                + ","
                + value.max().z();
    }

    private static long changedBlockCount(List<EditRecord> edits) {
        long total = 0;
        for (EditRecord edit : edits) {
            total = Math.addExact(total, edit.changedBlockCount());
        }
        return total;
    }
}
