package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import ca.deliyannides.dirtmcp.paper.world.edit.FillRegion;
import ca.deliyannides.dirtmcp.paper.world.edit.GetEditHistory;
import ca.deliyannides.dirtmcp.paper.world.edit.ReplaceRegionBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdit;
import ca.deliyannides.dirtmcp.paper.world.inspection.CountRegionBlockStates;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
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

    public JsonObject readJsonObject() throws IOException, InvalidRequestException {
        String contentType = this.exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !contentType
                        .split(";", 2)[0]
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .equals("application/json")) {
            throw new InvalidRequestException("Content-Type must be application/json");
        }
        validateContentLength();
        byte[] body =
                this.bodyReader.read(this.exchange.getRequestBody(), this.maximumRequestBytes);
        this.requestBytes = (long) body.length;
        if (body.length > this.maximumRequestBytes) {
            throw new InvalidRequestException(
                    "Request body exceeds the maximum of " + this.maximumRequestBytes + " bytes");
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
                throw new InvalidRequestException("Request body must be a JSON object");
            }
            return document.getAsJsonObject();
        } catch (CharacterCodingException | JsonParseException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private void validateContentLength() throws InvalidRequestException {
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
                throw new InvalidRequestException(
                        "Request body exceeds the maximum of "
                                + this.maximumRequestBytes
                                + " bytes");
            }
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException("Content-Length must be a non-negative integer");
        }
    }

    public void world(String world) {
        this.world = world;
    }

    public UUID requiredCallId() throws InvalidRequestException {
        String value = this.exchange.getRequestHeaders().getFirst("X-Dirt-Call-Id");
        try {
            return UuidV4.parseCanonical(value, "X-Dirt-Call-Id");
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException(exception.getMessage());
        }
    }

    public void ok(Object body) throws IOException {
        send(200, body);
    }

    void sendError(int status, String code, String message) throws IOException {
        this.errorCode = code;
        JsonObject detail = new JsonObject();
        detail.addProperty("code", code);
        detail.addProperty("message", message);
        JsonObject envelope = new JsonObject();
        envelope.add("error", detail);
        send(status, envelope);
    }

    void sendError(int status, String code, String message, UUID editId) throws IOException {
        this.errorCode = code;
        this.editId = Objects.requireNonNull(editId, "editId");
        JsonObject detail = new JsonObject();
        detail.addProperty("code", code);
        detail.addProperty("message", message);
        detail.addProperty("editId", editId.toString());
        JsonObject envelope = new JsonObject();
        envelope.add("error", detail);
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
            case FillRegion.Result result ->
                    captureEdit(
                            result.bounds(),
                            result.outcome().wireName(),
                            result.changedBlockCount(),
                            result.edit() == null ? null : result.edit().editId(),
                            null);
            case SetBlocks.Result result ->
                    captureEdit(
                            result.bounds(),
                            result.outcome().wireName(),
                            result.changedBlockCount(),
                            result.edit() == null ? null : result.edit().editId(),
                            null);
            case UndoEdit.Result result -> {
                this.bounds = bounds(result.edit().bounds());
                this.outcome = "undone";
                this.changedBlockCount = result.edit().changedBlockCount();
                this.editId = result.edit().editId();
            }
            case GetEditHistory.Result result -> this.resultCount = (long) result.edits().size();
            case CountRegionBlockStates.Result result -> {
                this.bounds = bounds(result.bounds());
                this.resultCount = (long) result.blockStateCounts().size();
            }
            case GetRegionBlocks.Result result -> {
                this.bounds = bounds(result.bounds());
                this.resultCount = result.matchedBlockCount();
            }
            case ScanOrthographicView.Result result -> {
                this.bounds = bounds(result.bounds());
                this.resultCount = result.visibleBlockCount();
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
        this.bounds = bounds(editBounds);
        this.outcome = editOutcome;
        this.changedBlockCount = changedCount;
        this.editId = retainedEditId;
        this.resultCount = count;
    }

    private static String bounds(BlockBounds value) {
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
}
