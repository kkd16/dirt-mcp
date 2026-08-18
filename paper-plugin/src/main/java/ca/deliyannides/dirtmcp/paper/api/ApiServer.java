package ca.deliyannides.dirtmcp.paper.api;

import ca.deliyannides.dirtmcp.paper.PluginSettings;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.EditException;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ExactInspectionMode;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ExactInspectionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewDirection;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewRequest;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.Serial;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class ApiServer implements AutoCloseable {
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final String CALL_ID_HEADER = "X-Dirt-Call-Id";
    private static final String STATUS_ATTRIBUTE = ApiServer.class.getName() + ".status";
    private static final String WORLD_ATTRIBUTE = ApiServer.class.getName() + ".world";
    private static final Pattern CALL_ID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Set<String> INSPECTION_FIELDS = Set.of("world", "min", "max");
    private static final Set<String> EXACT_INSPECTION_REQUIRED_FIELDS = Set.of("world", "min", "max");
    private static final Set<String> EXACT_INSPECTION_FIELDS =
            Set.of("world", "min", "max", "include", "exclude", "includeAir", "maxResults", "mode");
    private static final Set<String> VIEW_REQUIRED_FIELDS = Set.of(
            "world",
            "origin",
            "direction",
            "horizontalRadius",
            "verticalRadius",
            "maxDistance");
    private static final Set<String> VIEW_FIELDS = Set.of(
            "world",
            "origin",
            "direction",
            "horizontalRadius",
            "verticalRadius",
            "maxDistance",
            "maxResults");
    private static final Set<String> REPLACEMENT_REQUIRED_FIELDS =
            Set.of("world", "min", "max", "source", "destination");
    private static final Set<String> REPLACEMENT_FIELDS =
            Set.of("world", "min", "max", "source", "destination", "dryRun");
    private static final Set<String> FILL_REQUIRED_FIELDS = Set.of("world", "min", "max", "destination");
    private static final Set<String> FILL_FIELDS = Set.of("world", "min", "max", "destination", "dryRun");
    private static final Set<String> UNDO_FIELDS = Set.of("world");
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");
    private static final Gson GSON = new Gson();

    private final PluginSettings settings;
    private final String responseBody;
    private final BearerAuthentication authentication;
    private final RegionInspector regionInspector;
    private final RegionEditor regionEditor;
    private final Logger logger;

    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(
            PluginSettings settings,
            String pluginVersion,
            String minecraftVersion,
            String bearerToken,
            RegionInspector regionInspector,
            RegionEditor regionEditor,
            Logger logger) {
        this.settings = settings;
        this.logger = logger;
        this.authentication = new BearerAuthentication(
                bearerToken, settings.bridge().minimumTokenBytes());
        this.regionInspector = regionInspector;
        this.regionEditor = regionEditor;
        this.responseBody = GSON.toJson(new HealthResponse(
                "ok",
                "dirt-mcp-paper",
                pluginVersion,
                minecraftVersion,
                settings));
    }

    public void start() throws IOException {
        if (this.server != null) {
            throw new IllegalStateException("Dirt MCP bridge is already running");
        }

        HttpServer newServer = HttpServer.create(
                new InetSocketAddress(LOOPBACK_ADDRESS, this.settings.bridge().port()),
                this.settings.bridge().backlog());
        ExecutorService newExecutor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            newServer.createContext(
                    "/v1/health", exchange -> handleAudited("health", exchange, this::handleHealth));
            newServer.createContext(
                    "/v1/inspect-region",
                    exchange -> handleAudited("inspect_region", exchange, this::handleInspectRegion));
            newServer.createContext(
                    "/v1/inspect-blocks",
                    exchange -> handleAudited("inspect_blocks", exchange, this::handleInspectBlocks));
            newServer.createContext(
                    "/v1/inspect-view",
                    exchange -> handleAudited("inspect_view", exchange, this::handleInspectView));
            newServer.createContext(
                    "/v1/replace-blocks",
                    exchange -> handleAudited("replace_blocks", exchange, this::handleReplaceBlocks));
            newServer.createContext(
                    "/v1/fill-region",
                    exchange -> handleAudited("fill_region", exchange, this::handleFillRegion));
            newServer.createContext(
                    "/v1/undo-last-edit",
                    exchange -> handleAudited("undo_last_edit", exchange, this::handleUndoLastEdit));
            newServer.setExecutor(newExecutor);
            newServer.start();
        } catch (RuntimeException exception) {
            newServer.stop(this.settings.bridge().shutdownDelaySeconds());
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
            this.server.stop(this.settings.bridge().shutdownDelaySeconds());
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

    private void handleAudited(String operation, HttpExchange exchange, HttpHandler handler)
            throws IOException {
        long started = System.nanoTime();
        try {
            handler.handle(exchange);
        } finally {
            Object status = exchange.getAttribute(STATUS_ATTRIBUTE);
            Object world = exchange.getAttribute(WORLD_ATTRIBUTE);
            String callId = exchange.getRequestHeaders().getFirst(CALL_ID_HEADER);
            StringBuilder message = new StringBuilder("Dirt MCP bridge_call operation=")
                    .append(operation)
                    .append(" method=")
                    .append(exchange.getRequestMethod())
                    .append(" status=")
                    .append(status instanceof Integer ? status : "aborted");
            if (world instanceof String worldName) {
                message.append(" world=").append(GSON.toJson(worldName));
            }
            if (callId != null && CALL_ID_PATTERN.matcher(callId).matches()) {
                message.append(" call=").append(callId);
            }
            message.append(" duration_ms=")
                    .append(Math.max(0, (System.nanoTime() - started) / 1_000_000));
            this.logger.info(message.toString());
        }
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
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
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

    private void handleInspectBlocks(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            ExactInspectionRequest request = parseExactInspectionRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionInspector.inspectBlocks(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (InspectionException exception) {
            sendInspectionError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected inspect-blocks failure", exception);
            sendError(exchange, 500, "internal_error", "The blocks could not be inspected");
        }
    }

    private void handleInspectView(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            ViewRequest request = parseViewRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionInspector.inspectView(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (InspectionException exception) {
            sendInspectionError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected inspect-view failure", exception);
            sendError(exchange, 500, "internal_error", "The view could not be inspected");
        }
    }

    private void handleReplaceBlocks(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            ReplaceRequest request = parseReplaceRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionEditor.replace(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (EditException exception) {
            sendEditError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected replace-blocks failure", exception);
            sendError(exchange, 500, "internal_error", "The blocks could not be replaced");
        }
    }

    private void handleUndoLastEdit(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            UndoRequest request = parseUndoRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionEditor.undo(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (EditException exception) {
            sendEditError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected undo-last-edit failure", exception);
            sendError(exchange, 500, "internal_error", "The edit could not be undone");
        }
    }

    private void handleFillRegion(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            FillRequest request = parseFillRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionEditor.fill(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (EditException exception) {
            sendEditError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected fill-region failure", exception);
            sendError(exchange, 500, "internal_error", "The region could not be filled");
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

    private InspectionRequest parseInspectionRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            requireFields(object, INSPECTION_FIELDS, "Request");
            return new InspectionRequest(
                    parseString(object.get("world"), "world"),
                    parsePosition(object.get("min"), "min"),
                    parsePosition(object.get("max"), "max"));
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private ExactInspectionRequest parseExactInspectionRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            if (!object.keySet().containsAll(EXACT_INSPECTION_REQUIRED_FIELDS)
                    || !EXACT_INSPECTION_FIELDS.containsAll(object.keySet())) {
                throw new InvalidRequestException("Request contains missing or unknown fields");
            }
            int maxResults = object.has("maxResults")
                    ? parseInteger(object.get("maxResults"), "maxResults")
                    : this.settings.limits().defaultExactResults();
            if (maxResults < 1 || maxResults > this.settings.limits().maxExactResults()) {
                throw new InvalidRequestException(
                        "maxResults must be between 1 and "
                                + this.settings.limits().maxExactResults());
            }
            return new ExactInspectionRequest(
                    parseString(object.get("world"), "world"),
                    parsePosition(object.get("min"), "min"),
                    parsePosition(object.get("max"), "max"),
                    object.has("include") ? parseStringList(object.get("include"), "include") : List.of(),
                    object.has("exclude") ? parseStringList(object.get("exclude"), "exclude") : List.of(),
                    object.has("includeAir")
                            ? parseBoolean(object.get("includeAir"), "includeAir")
                            : this.settings.defaults().exactInspectionIncludeAir(),
                    maxResults,
                    object.has("mode")
                            ? parseInspectionMode(object.get("mode"))
                            : parseInspectionMode(this.settings.defaults().exactInspectionMode()));
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private ViewRequest parseViewRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            if (!object.keySet().containsAll(VIEW_REQUIRED_FIELDS)
                    || !VIEW_FIELDS.containsAll(object.keySet())) {
                throw new InvalidRequestException("Request contains missing or unknown fields");
            }
            int horizontalRadius = parseInteger(object.get("horizontalRadius"), "horizontalRadius");
            int verticalRadius = parseInteger(object.get("verticalRadius"), "verticalRadius");
            int maxDistance = parseInteger(object.get("maxDistance"), "maxDistance");
            int maxResults = object.has("maxResults")
                    ? parseInteger(object.get("maxResults"), "maxResults")
                    : this.settings.limits().defaultViewResults();
            if (horizontalRadius < 0 || verticalRadius < 0) {
                throw new InvalidRequestException(
                        "horizontalRadius and verticalRadius must be non-negative");
            }
            if (maxDistance < 1) {
                throw new InvalidRequestException("maxDistance must be positive");
            }
            if (maxResults < 1 || maxResults > this.settings.limits().maxViewResults()) {
                throw new InvalidRequestException(
                        "maxResults must be between 1 and "
                                + this.settings.limits().maxViewResults());
            }
            return new ViewRequest(
                    parseString(object.get("world"), "world"),
                    parsePosition(object.get("origin"), "origin"),
                    parseViewDirection(object.get("direction")),
                    horizontalRadius,
                    verticalRadius,
                    maxDistance,
                    maxResults);
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private ReplaceRequest parseReplaceRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            if (!object.keySet().containsAll(REPLACEMENT_REQUIRED_FIELDS)
                    || !REPLACEMENT_FIELDS.containsAll(object.keySet())) {
                throw new InvalidRequestException("Request contains missing or unknown fields");
            }
            return new ReplaceRequest(
                    parseString(object.get("world"), "world"),
                    parsePosition(object.get("min"), "min"),
                    parsePosition(object.get("max"), "max"),
                    parseString(object.get("source"), "source"),
                    parseString(object.get("destination"), "destination"),
                    object.has("dryRun")
                            ? parseBoolean(object.get("dryRun"), "dryRun")
                            : this.settings.defaults().replaceDryRun());
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private UndoRequest parseUndoRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            requireFields(object, UNDO_FIELDS, "Request");
            return new UndoRequest(parseString(object.get("world"), "world"));
        } catch (JsonParseException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private FillRequest parseFillRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            if (!object.keySet().containsAll(FILL_REQUIRED_FIELDS)
                    || !FILL_FIELDS.containsAll(object.keySet())) {
                throw new InvalidRequestException("Request contains missing or unknown fields");
            }
            return new FillRequest(
                    parseString(object.get("world"), "world"),
                    parsePosition(object.get("min"), "min"),
                    parsePosition(object.get("max"), "max"),
                    parseString(object.get("destination"), "destination"),
                    object.has("dryRun")
                            ? parseBoolean(object.get("dryRun"), "dryRun")
                            : this.settings.defaults().fillDryRun());
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private JsonObject parseRequestObject(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT).equals("application/json")) {
            throw new InvalidRequestException("Content-Type must be application/json");
        }
        int maximumRequestBytes = this.settings.bridge().maxRequestBytes();
        byte[] body = exchange.getRequestBody().readNBytes(maximumRequestBytes + 1);
        if (body.length > maximumRequestBytes) {
            throw new InvalidRequestException("Request body is too large");
        }
        JsonElement document = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        if (!document.isJsonObject()) {
            throw new InvalidRequestException("Request body must be a JSON object");
        }
        return document.getAsJsonObject();
    }

    private static String parseString(JsonElement element, String name) throws InvalidRequestException {
        if (!(element instanceof JsonPrimitive primitive)
                || !primitive.isString()
                || primitive.getAsString().isBlank()) {
            throw new InvalidRequestException(name + " must be a non-empty string");
        }
        return primitive.getAsString();
    }

    private static boolean parseBoolean(JsonElement element, String name) throws InvalidRequestException {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw new InvalidRequestException(name + " must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static List<String> parseStringList(JsonElement element, String name)
            throws InvalidRequestException {
        if (element == null || !element.isJsonArray()) {
            throw new InvalidRequestException(name + " must be an array of non-empty strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            values.add(parseString(value, name + "[]"));
        }
        return List.copyOf(values);
    }

    private static ExactInspectionMode parseInspectionMode(JsonElement element)
            throws InvalidRequestException {
        return parseInspectionMode(parseString(element, "mode"));
    }

    private static ExactInspectionMode parseInspectionMode(String mode)
            throws InvalidRequestException {
        return switch (mode) {
            case "blocks" -> ExactInspectionMode.BLOCKS;
            case "runs" -> ExactInspectionMode.RUNS;
            default -> throw new InvalidRequestException("mode must be blocks or runs");
        };
    }

    private static ViewDirection parseViewDirection(JsonElement element)
            throws InvalidRequestException {
        String direction = parseString(element, "direction");
        return switch (direction) {
            case "north" -> ViewDirection.NORTH;
            case "east" -> ViewDirection.EAST;
            case "south" -> ViewDirection.SOUTH;
            case "west" -> ViewDirection.WEST;
            case "up" -> ViewDirection.UP;
            case "down" -> ViewDirection.DOWN;
            default -> throw new InvalidRequestException(
                    "direction must be north, east, south, west, up, or down");
        };
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
            case REGION_TOO_LARGE, RESULT_TOO_LARGE -> 413;
            case WORLD_UNAVAILABLE -> 503;
        };
        sendError(exchange, status, failure.name().toLowerCase(Locale.ROOT), exception.getMessage());
    }

    private static void sendEditError(HttpExchange exchange, EditException exception) throws IOException {
        int status = switch (exception.failure()) {
            case INVALID_REQUEST -> 400;
            case WORLD_NOT_FOUND -> 404;
            case NOTHING_TO_UNDO, WORLD_BUSY -> 409;
            case CHANGE_LIMIT_EXCEEDED, REGION_TOO_LARGE -> 413;
            case WORLD_UNAVAILABLE -> 503;
        };
        sendError(
                exchange,
                status,
                exception.failure().name().toLowerCase(Locale.ROOT),
                exception.getMessage());
    }

    private static void sendError(HttpExchange exchange, int statusCode, String code, String message)
            throws IOException {
        send(exchange, statusCode, GSON.toJson(new ErrorEnvelope(new ErrorDetail(code, message))));
    }

    private static void send(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.setAttribute(STATUS_ATTRIBUTE, statusCode);
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
            PluginSettings configuration) {}

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
