package ca.deliyannides.dirtmcp.paper.bridge;

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

public final class BridgeExchange {
    private final HttpExchange exchange;
    private final int maximumRequestBytes;
    private final RequestBodyReader bodyReader;
    private Integer status;
    private String world;

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

    public void ok(Object body) throws IOException {
        send(200, body);
    }

    void sendError(int status, String code, String message) throws IOException {
        send(status, new ErrorEnvelope(new ErrorDetail(code, message)));
    }

    void allow(String method) {
        this.exchange.getResponseHeaders().set("Allow", method);
    }

    void challenge() {
        this.exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"dirt-mcp\"");
    }

    void abort() {
        this.exchange.close();
    }

    Integer status() {
        return this.status;
    }

    String world() {
        return this.world;
    }

    private void send(int status, Object body) throws IOException {
        byte[] bytes = BridgeJson.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        this.status = status;
        this.exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        this.exchange.getResponseHeaders().set("Cache-Control", "no-store");
        this.exchange.sendResponseHeaders(status, bytes.length);

        try (this.exchange;
                var output = this.exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record ErrorEnvelope(ErrorDetail error) {}

    private record ErrorDetail(String code, String message) {}
}
