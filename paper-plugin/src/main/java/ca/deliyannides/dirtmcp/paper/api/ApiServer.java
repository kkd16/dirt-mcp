package ca.deliyannides.dirtmcp.paper.api;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionRequest;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.Serial;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ApiServer implements AutoCloseable {
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final int MAXIMUM_REQUEST_BYTES = 8_192;
    private static final Set<String> INSPECTION_FIELDS = Set.of("world", "min", "max");
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");
    private static final Gson GSON = new Gson();

    private final int port;
    private final String responseBody;
    private final BearerAuthentication authentication;
    private final RegionInspector regionInspector;
    private final Logger logger;

    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(
            int port,
            String pluginVersion,
            String minecraftVersion,
            String bearerToken,
            RegionInspector regionInspector,
            Logger logger) {
        this.port = port;
        this.logger = logger;
        this.authentication = new BearerAuthentication(bearerToken);
        this.regionInspector = regionInspector;
        this.responseBody = GSON.toJson(new HealthResponse(
                "ok",
                "dirt-mcp-paper",
                pluginVersion,
                minecraftVersion,
                new Capabilities(true, false)));
    }

    public void start() throws IOException {
        if (this.server != null) {
            throw new IllegalStateException("Dirt MCP bridge is already running");
        }

        HttpServer newServer = HttpServer.create(new InetSocketAddress(LOOPBACK_ADDRESS, this.port), 0);
        ExecutorService newExecutor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            newServer.createContext("/v1/health", this::handleHealth);
            newServer.createContext("/v1/inspect-region", this::handleInspectRegion);
            newServer.setExecutor(newExecutor);
            newServer.start();
        } catch (RuntimeException exception) {
            newServer.stop(0);
            newExecutor.close();
            throw exception;
        }

        this.executor = newExecutor;
        this.server = newServer;
    }

    int boundPort() {
        if (this.server == null) {
            throw new IllegalStateException("Dirt MCP bridge is not running");
        }

        return this.server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }

        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }

        this.logger.info("Dirt MCP bridge stopped.");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }

        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendError(exchange, 405, "method_not_allowed", "Method must be GET");
            return;
        }

        send(exchange, 200, this.responseBody);
    }

    private void handleInspectRegion(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            InspectionRequest request = parseInspectionRequest(exchange);
            send(exchange, 200, GSON.toJson(this.regionInspector.inspect(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (InspectionException exception) {
            sendInspectionError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected inspect-region failure", exception);
            sendError(exchange, 500, "internal_error", "The region could not be inspected");
        }
    }

    private boolean authenticate(HttpExchange exchange) throws IOException {
        if (this.authentication.accepts(exchange.getRequestHeaders().getFirst("Authorization"))) {
            return true;
        }
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"dirt-mcp\"");
        sendError(exchange, 401, "unauthorized", "A valid bearer token is required");
        return false;
    }

    private static InspectionRequest parseInspectionRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT).equals("application/json")) {
            throw new InvalidRequestException("Content-Type must be application/json");
        }

        byte[] body = exchange.getRequestBody().readNBytes(MAXIMUM_REQUEST_BYTES + 1);
        if (body.length > MAXIMUM_REQUEST_BYTES) {
            throw new InvalidRequestException("Request body is too large");
        }

        try {
            JsonElement document = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!document.isJsonObject()) {
                throw new InvalidRequestException("Request body must be a JSON object");
            }
            JsonObject object = document.getAsJsonObject();
            requireFields(object, INSPECTION_FIELDS, "Request");

            JsonElement worldElement = object.get("world");
            if (!(worldElement instanceof JsonPrimitive worldPrimitive)
                    || !worldPrimitive.isString()
                    || worldPrimitive.getAsString().isBlank()) {
                throw new InvalidRequestException("world must be a non-empty string");
            }

            return new InspectionRequest(
                    worldPrimitive.getAsString(),
                    parsePosition(object.get("min"), "min"),
                    parsePosition(object.get("max"), "max"));
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private static BlockPosition parsePosition(JsonElement element, String name)
            throws InvalidRequestException {
        if (element == null || !element.isJsonObject()) {
            throw new InvalidRequestException(name + " must be an object");
        }
        JsonObject object = element.getAsJsonObject();
        requireFields(object, POSITION_FIELDS, name);
        return new BlockPosition(
                parseInteger(object.get("x"), name + ".x"),
                parseInteger(object.get("y"), name + ".y"),
                parseInteger(object.get("z"), name + ".z"));
    }

    private static int parseInteger(JsonElement element, String name) throws InvalidRequestException {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new InvalidRequestException(name + " must be a signed 32-bit integer");
        }
        try {
            return primitive.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw new InvalidRequestException(name + " must be a signed 32-bit integer");
        }
    }

    private static void requireFields(JsonObject object, Set<String> expected, String name)
            throws InvalidRequestException {
        if (!object.keySet().equals(expected)) {
            throw new InvalidRequestException(name + " contains missing or unknown fields");
        }
    }

    private static void sendInspectionError(HttpExchange exchange, InspectionException exception)
            throws IOException {
        Failure failure = exception.failure();
        int status = switch (failure) {
            case INVALID_REQUEST -> 400;
            case WORLD_NOT_FOUND -> 404;
            case REGION_TOO_LARGE -> 413;
            case WORLD_UNAVAILABLE -> 503;
        };
        sendError(exchange, status, failure.name().toLowerCase(Locale.ROOT), exception.getMessage());
    }

    private static void sendError(HttpExchange exchange, int statusCode, String code, String message)
            throws IOException {
        send(exchange, statusCode, GSON.toJson(new ErrorEnvelope(new ErrorDetail(code, message))));
    }

    private static void send(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (exchange; var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record HealthResponse(
            String status,
            String service,
            String version,
            String minecraftVersion,
            Capabilities capabilities) {}

    private record Capabilities(boolean worldInspection, boolean worldEditing) {}

    private record ErrorEnvelope(ErrorDetail error) {}

    private record ErrorDetail(String code, String message) {}

    private static final class InvalidRequestException extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        private InvalidRequestException(String message) {
            super(message);
        }
    }
}
