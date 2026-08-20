package ca.deliyannides.dirtmcp.paper.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class BridgeExchange {
    private final HttpExchange exchange;
    private final int maximumRequestBytes;
    private Integer status;
    private String world;

    BridgeExchange(HttpExchange exchange, int maximumRequestBytes) {
        this.exchange = exchange;
        this.maximumRequestBytes = maximumRequestBytes;
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
        byte[] body = this.exchange.getRequestBody().readNBytes(this.maximumRequestBytes + 1);
        if (body.length > this.maximumRequestBytes) {
            throw new InvalidRequestException(
                    "Request body exceeds the maximum of " + this.maximumRequestBytes + " bytes");
        }
        try {
            JsonElement document = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!document.isJsonObject()) {
                throw new InvalidRequestException("Request body must be a JSON object");
            }
            return document.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
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
