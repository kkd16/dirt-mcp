package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.Serial;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class BridgeExchange {
    private static final String CALL_ID_HEADER = "X-Dirt-Call-Id";
    private static final int MAXIMUM_JSON_DEPTH = 64;

    private final HttpExchange exchange;
    private final int maximumRequestBytes;
    private final RequestBodyReader bodyReader;
    private final Map<String, Object> auditFields = new LinkedHashMap<>();
    private Integer status;
    private Long requestBytes;
    private Long responseBytes;
    private String errorCode;
    private UUID callId;
    private Throwable auditFailure;
    private AuditLevel auditLevel = AuditLevel.DEBUG;
    private boolean responseDeliveryRisk;
    private boolean suppressFailureDetails;

    BridgeExchange(HttpExchange exchange, int maximumRequestBytes, RequestBodyReader bodyReader) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.maximumRequestBytes = maximumRequestBytes;
        this.bodyReader = Objects.requireNonNull(bodyReader, "bodyReader");
    }

    public JsonObject readJsonObject() throws IOException {
        String contentType = this.exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !contentType
                        .split(";", 2)[0]
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .equals("application/json")) {
            throw bridgeRequest(
                    "Content-Type must be application/json", BridgeProblem.unsupportedMediaType());
        }
        validateContentLength();
        byte[] body =
                this.bodyReader.read(this.exchange.getRequestBody(), this.maximumRequestBytes);
        this.requestBytes = (long) body.length;
        if (body.length > this.maximumRequestBytes) {
            throw bridgeRequest(
                    "Request body exceeds the maximum of " + this.maximumRequestBytes + " bytes",
                    BridgeProblem.bodyTooLarge(this.maximumRequestBytes));
        }

        String json = decodeUtf8(body);
        validateStrictJson(json);
        try {
            JsonElement document = JsonParser.parseString(json);
            if (!document.isJsonObject()) {
                throw bridgeRequest(
                        "Request body must be a JSON object", BridgeProblem.invalidValue("body"));
            }
            return document.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw malformedJson();
        }
    }

    private static String decodeUtf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw malformedJson();
        }
    }

    private static void validateStrictJson(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            validateValue(reader, "", 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw malformedJson();
            }
        } catch (DuplicateMemberException exception) {
            throw bridgeRequest(
                    "Request body contains duplicate field " + exception.field(),
                    BridgeProblem.duplicateJsonMember(exception.field()));
        } catch (IOException | IllegalStateException exception) {
            throw malformedJson();
        }
    }

    private static void validateValue(JsonReader reader, String path, int depth)
            throws IOException {
        if (depth > MAXIMUM_JSON_DEPTH) {
            throw new IllegalStateException("JSON nesting is too deep");
        }
        switch (reader.peek()) {
            case BEGIN_ARRAY -> validateArray(reader, path, depth);
            case BEGIN_OBJECT -> validateObject(reader, path, depth);
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            case NUMBER, STRING -> reader.nextString();
            default -> throw new IllegalStateException("Expected a JSON value");
        }
    }

    private static void validateArray(JsonReader reader, String path, int depth)
            throws IOException {
        reader.beginArray();
        int index = 0;
        while (reader.hasNext()) {
            validateValue(reader, path + '[' + index + ']', depth + 1);
            index++;
        }
        reader.endArray();
    }

    private static void validateObject(JsonReader reader, String path, int depth)
            throws IOException {
        reader.beginObject();
        Set<String> fields = new HashSet<>();
        while (reader.hasNext()) {
            String field = reader.nextName();
            String fieldPath = path.isEmpty() ? field : path + '.' + field;
            if (!fields.add(field)) {
                throw new DuplicateMemberException(fieldPath);
            }
            validateValue(reader, fieldPath, depth + 1);
        }
        reader.endObject();
    }

    private static BridgeRequestException malformedJson() {
        return bridgeRequest(
                "Request body must contain strict JSON", BridgeProblem.malformedJson());
    }

    private void validateContentLength() {
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
                throw bridgeRequest(
                        "Request body exceeds the maximum of "
                                + this.maximumRequestBytes
                                + " bytes",
                        BridgeProblem.bodyTooLarge(this.maximumRequestBytes));
            }
        } catch (NumberFormatException exception) {
            throw bridgeRequest(
                    "Content-Length must be a non-negative integer",
                    BridgeProblem.invalidValue("Content-Length"));
        }
    }

    void parseCallId(List<String> values) {
        if (values == null || values.size() != 1) {
            throw bridgeRequest(
                    CALL_ID_HEADER + " must occur exactly once",
                    BridgeProblem.invalidValue(CALL_ID_HEADER));
        }
        try {
            this.callId = UuidV4.parseCanonical(values.getFirst(), CALL_ID_HEADER);
        } catch (IllegalArgumentException exception) {
            throw bridgeRequest(exception.getMessage(), BridgeProblem.invalidValue(CALL_ID_HEADER));
        }
    }

    public UUID callId() {
        return Objects.requireNonNull(this.callId, "Call ID has not been admitted");
    }

    public void auditField(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Audit field name must not be blank");
        }
        this.auditFields.put(key, Objects.requireNonNull(value, "value"));
    }

    public void auditCompletion(AuditLevel level, boolean deliveryRisk) {
        this.auditLevel = Objects.requireNonNull(level, "level");
        this.responseDeliveryRisk = deliveryRisk;
    }

    /**
     * Attaches an endpoint-owned failure to the detailed audit log without changing the response.
     */
    public void auditFailure(Throwable failure) {
        this.auditFailure = Objects.requireNonNull(failure, "failure");
    }

    public void suppressFailureDetails() {
        this.suppressFailureDetails = true;
    }

    public void ok(Object body) throws IOException {
        send(200, body);
    }

    void sendError(int status, String message, ErrorDetails details) throws IOException {
        sendErrorEnvelope(
                status,
                ErrorDetailsJson.code(details),
                message,
                ErrorDetailsJson.serialize(details),
                null);
    }

    void sendError(int status, String message, ErrorDetails details, UUID editId)
            throws IOException {
        sendErrorEnvelope(
                status,
                ErrorDetailsJson.code(details),
                message,
                ErrorDetailsJson.serialize(details),
                Objects.requireNonNull(editId, "editId"));
    }

    void sendProblem(int status, String message, BridgeProblem problem) throws IOException {
        JsonObject details = BridgeJson.GSON.toJsonTree(problem.details()).getAsJsonObject();
        sendErrorEnvelope(status, problem.code(), message, details, null);
    }

    void sendInternalError(int status, String message) throws IOException {
        sendErrorEnvelope(status, "internal_error", message, null, null);
    }

    void sendInternalError(int status, String message, UUID editId) throws IOException {
        sendErrorEnvelope(
                status, "internal_error", message, null, Objects.requireNonNull(editId, "editId"));
    }

    private void sendErrorEnvelope(
            int status, String code, String message, JsonObject details, UUID editId)
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
            detail.add("details", details);
        }
        if (checkedEditId != null) {
            detail.addProperty("editId", checkedEditId.toString());
            auditField("edit_id", checkedEditId);
        }
        JsonObject envelope = new JsonObject();
        envelope.add("error", detail);
        send(status, envelope);
    }

    private static BridgeRequestException bridgeRequest(String message, BridgeProblem problem) {
        return new BridgeRequestException(message, problem);
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

    Long requestBytes() {
        return this.requestBytes;
    }

    Long responseBytes() {
        return this.responseBytes;
    }

    String errorCode() {
        return this.errorCode;
    }

    UUID admittedCallId() {
        return this.callId;
    }

    Map<String, Object> auditFields() {
        return Map.copyOf(this.auditFields);
    }

    AuditLevel auditLevel() {
        return this.auditLevel;
    }

    boolean responseDeliveryRisk() {
        return this.responseDeliveryRisk;
    }

    Throwable auditFailure() {
        return this.auditFailure;
    }

    boolean suppressesFailureDetails() {
        return this.suppressFailureDetails;
    }

    private void send(int status, Object body) throws IOException {
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

    public enum AuditLevel {
        DEBUG,
        INFO,
        WARNING
    }

    private static final class DuplicateMemberException extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        private final String field;

        private DuplicateMemberException(String field) {
            this.field = field;
        }

        private String field() {
            return this.field;
        }
    }
}
