package ca.deliyannides.dirtmcp.paper.api;

import ca.deliyannides.dirtmcp.paper.PluginSettings;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.CommandRunnerException;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.RunCommandsRequest;
import ca.deliyannides.dirtmcp.paper.server.ServerContext;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.ServerContextException;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.BlockChange;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.EditException;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillRegionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRegionBlocksRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.SetBlocksRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoLastDirtEditRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockStateCountRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.OrthographicViewDirection;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.OrthographicViewRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RegionBlocksFormat;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RegionBlocksRequest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
    private static final Set<String> BLOCK_STATE_COUNT_FIELDS = Set.of("world", "min", "max");
    private static final Set<String> REGION_BLOCKS_REQUIRED_FIELDS = Set.of("world", "min", "max");
    private static final Set<String> REGION_BLOCKS_FIELDS = Set.of(
            "world",
            "min",
            "max",
            "includeBlockStatePatterns",
            "excludeBlockStatePatterns",
            "includeAir",
            "maxResults",
            "format");
    private static final Set<String> ORTHOGRAPHIC_VIEW_REQUIRED_FIELDS = Set.of(
            "world",
            "origin",
            "direction",
            "horizontalRadius",
            "verticalRadius",
            "maxDistance");
    private static final Set<String> ORTHOGRAPHIC_VIEW_FIELDS = Set.of(
            "world",
            "origin",
            "direction",
            "horizontalRadius",
            "verticalRadius",
            "maxDistance",
            "maxResults");
    private static final Set<String> REPLACE_REGION_BLOCKS_REQUIRED_FIELDS =
            Set.of("world", "min", "max", "sourceBlockState", "destinationBlockState");
    private static final Set<String> REPLACE_REGION_BLOCKS_FIELDS = Set.of(
            "world", "min", "max", "sourceBlockState", "destinationBlockState", "dryRun");
    private static final Set<String> FILL_REGION_REQUIRED_FIELDS =
            Set.of("world", "min", "max", "blockState");
    private static final Set<String> FILL_REGION_FIELDS =
            Set.of("world", "min", "max", "blockState", "dryRun");
    private static final Set<String> SET_BLOCKS_REQUIRED_FIELDS = Set.of("world", "changes");
    private static final Set<String> SET_BLOCKS_FIELDS = Set.of("world", "changes", "dryRun");
    private static final Set<String> BLOCK_CHANGE_FIELDS = Set.of("position", "blockState");
    private static final Set<String> UNDO_FIELDS = Set.of("world");
    private static final Set<String> RUN_COMMANDS_FIELDS = Set.of("commands");
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final PluginSettings settings;
    private final BearerAuthentication authentication;
    private final ServerContext serverContext;
    private final RegionInspector regionInspector;
    private final RegionEditor regionEditor;
    private final CommandRunner commandRunner;
    private final Logger logger;

    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(
            PluginSettings settings,
            String bearerToken,
            ServerContext serverContext,
            RegionInspector regionInspector,
            RegionEditor regionEditor,
            CommandRunner commandRunner,
            Logger logger) {
        this.settings = settings;
        this.logger = logger;
        this.authentication = new BearerAuthentication(
                bearerToken, settings.bridge().minimumTokenBytes());
        this.serverContext = serverContext;
        this.regionInspector = regionInspector;
        this.regionEditor = regionEditor;
        this.commandRunner = commandRunner;
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
                    "/v1/ping",
                    exchange -> handleAudited("ping_server", exchange, this::handlePing));
            newServer.createContext(
                    "/v1/server-status",
                    exchange -> handleAudited("get_server_status", exchange, this::handleGetServerStatus));
            newServer.createContext(
                    "/v1/count-region-block-states",
                    exchange -> handleAudited(
                            "count_region_block_states", exchange, this::handleCountRegionBlockStates));
            newServer.createContext(
                    "/v1/get-region-blocks",
                    exchange -> handleAudited("get_region_blocks", exchange, this::handleGetRegionBlocks));
            newServer.createContext(
                    "/v1/scan-orthographic-view",
                    exchange -> handleAudited(
                            "scan_orthographic_view", exchange, this::handleScanOrthographicView));
            newServer.createContext(
                    "/v1/replace-region-blocks",
                    exchange -> handleAudited(
                            "replace_region_blocks", exchange, this::handleReplaceRegionBlocks));
            newServer.createContext(
                    "/v1/fill-region",
                    exchange -> handleAudited("fill_region", exchange, this::handleFillRegion));
            newServer.createContext(
                    "/v1/set-blocks",
                    exchange -> handleAudited("set_blocks", exchange, this::handleSetBlocks));
            newServer.createContext(
                    "/v1/undo-last-dirt-edit",
                    exchange -> handleAudited(
                            "undo_last_dirt_edit", exchange, this::handleUndoLastDirtEdit));
            newServer.createContext(
                    "/v1/run-minecraft-commands",
                    exchange -> handleAudited(
                            "run_minecraft_commands", exchange, this::handleRunMinecraftCommands));
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

    private void handlePing(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }

        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendError(exchange, 405, "method_not_allowed", "Method must be GET");
            return;
        }

        try {
            send(exchange, 200, GSON.toJson(this.serverContext.ping()));
        } catch (ServerContextException exception) {
            sendError(exchange, 503, "unhealthy", exception.getMessage());
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected ping failure", exception);
            sendError(exchange, 500, "internal_error", "The end-to-end health check failed");
        }
    }

    private void handleGetServerStatus(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }

        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendError(exchange, 405, "method_not_allowed", "Method must be GET");
            return;
        }

        try {
            send(exchange, 200, GSON.toJson(this.serverContext.getStatus()));
        } catch (ServerContextException exception) {
            sendError(exchange, 503, "server_unavailable", exception.getMessage());
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected server-status failure", exception);
            sendError(exchange, 500, "internal_error", "Server status could not be returned");
        }
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

    private void handleCountRegionBlockStates(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            BlockStateCountRequest request = parseBlockStateCountRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionInspector.countRegionBlockStates(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (InspectionException exception) {
            sendInspectionError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected count-region-block-states failure", exception);
            sendError(exchange, 500, "internal_error", "The region's block states could not be counted");
        }
    }

    private void handleGetRegionBlocks(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            RegionBlocksRequest request = parseRegionBlocksRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionInspector.getRegionBlocks(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (InspectionException exception) {
            sendInspectionError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected get-region-blocks failure", exception);
            sendError(exchange, 500, "internal_error", "The region's blocks could not be returned");
        }
    }

    private void handleScanOrthographicView(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            OrthographicViewRequest request = parseOrthographicViewRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionInspector.scanOrthographicView(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (InspectionException exception) {
            sendInspectionError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected scan-orthographic-view failure", exception);
            sendError(exchange, 500, "internal_error", "The orthographic view could not be scanned");
        }
    }

    private void handleReplaceRegionBlocks(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            ReplaceRegionBlocksRequest request = parseReplaceRegionBlocksRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionEditor.replaceRegionBlocks(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (EditException exception) {
            sendEditError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected replace-region-blocks failure", exception);
            sendError(exchange, 500, "internal_error", "The blocks could not be replaced");
        }
    }

    private void handleUndoLastDirtEdit(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            UndoLastDirtEditRequest request = parseUndoLastDirtEditRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionEditor.undoLastDirtEdit(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (EditException exception) {
            sendEditError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected undo-last-dirt-edit failure", exception);
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
            FillRegionRequest request = parseFillRegionRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionEditor.fillRegion(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (EditException exception) {
            sendEditError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected fill-region failure", exception);
            sendError(exchange, 500, "internal_error", "The region could not be filled");
        }
    }

    private void handleRunMinecraftCommands(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            RunCommandsRequest request = parseRunCommandsRequest(exchange);
            send(exchange, 200, GSON.toJson(this.commandRunner.runCommands(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (CommandRunnerException exception) {
            int status = exception.failure() == CommandRunner.Failure.INVALID_REQUEST ? 400 : 503;
            String code = exception.failure() == CommandRunner.Failure.INVALID_REQUEST
                    ? "invalid_request"
                    : "server_unavailable";
            sendError(exchange, status, code, exception.getMessage());
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected run-minecraft-commands failure", exception);
            sendError(exchange, 500, "internal_error", "The commands could not be run");
        }
    }

    private void handleSetBlocks(HttpExchange exchange) throws IOException {
        if (!authenticate(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, 405, "method_not_allowed", "Method must be POST");
            return;
        }

        try {
            SetBlocksRequest request = parseSetBlocksRequest(exchange);
            exchange.setAttribute(WORLD_ATTRIBUTE, request.world());
            send(exchange, 200, GSON.toJson(this.regionEditor.setBlocks(request)));
        } catch (InvalidRequestException exception) {
            sendError(exchange, 400, "invalid_request", exception.getMessage());
        } catch (EditException exception) {
            sendEditError(exchange, exception);
        } catch (RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Unexpected set-blocks failure", exception);
            sendError(exchange, 500, "internal_error", "The blocks could not be set");
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

    private BlockStateCountRequest parseBlockStateCountRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            requireFields(object, BLOCK_STATE_COUNT_FIELDS, "Request");
            return new BlockStateCountRequest(
                    parseString(object.get("world"), "world"),
                    parsePosition(object.get("min"), "min"),
                    parsePosition(object.get("max"), "max"));
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private RegionBlocksRequest parseRegionBlocksRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            if (!object.keySet().containsAll(REGION_BLOCKS_REQUIRED_FIELDS)
                    || !REGION_BLOCKS_FIELDS.containsAll(object.keySet())) {
                throw new InvalidRequestException("Request contains missing or unknown fields");
            }
            int maxResults = object.has("maxResults")
                    ? parseInteger(object.get("maxResults"), "maxResults")
                    : this.settings.limits().defaultInspectionResultLimit();
            if (maxResults < 1 || maxResults > this.settings.limits().maxInspectionResultLimit()) {
                throw new InvalidRequestException(
                        "maxResults must be between 1 and "
                                + this.settings.limits().maxInspectionResultLimit());
            }
            return new RegionBlocksRequest(
                    parseString(object.get("world"), "world"),
                    parsePosition(object.get("min"), "min"),
                    parsePosition(object.get("max"), "max"),
                    object.has("includeBlockStatePatterns")
                            ? parseStringList(
                                    object.get("includeBlockStatePatterns"),
                                    "includeBlockStatePatterns")
                            : List.of(),
                    object.has("excludeBlockStatePatterns")
                            ? parseStringList(
                                    object.get("excludeBlockStatePatterns"),
                                    "excludeBlockStatePatterns")
                            : List.of(),
                    object.has("includeAir")
                            ? parseBoolean(object.get("includeAir"), "includeAir")
                            : this.settings.defaults().regionBlocksIncludeAir(),
                    maxResults,
                    object.has("format")
                            ? parseRegionBlocksFormat(object.get("format"))
                            : parseRegionBlocksFormat(this.settings.defaults().regionBlocksFormat()));
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private OrthographicViewRequest parseOrthographicViewRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            if (!object.keySet().containsAll(ORTHOGRAPHIC_VIEW_REQUIRED_FIELDS)
                    || !ORTHOGRAPHIC_VIEW_FIELDS.containsAll(object.keySet())) {
                throw new InvalidRequestException("Request contains missing or unknown fields");
            }
            int horizontalRadius = parseInteger(object.get("horizontalRadius"), "horizontalRadius");
            int verticalRadius = parseInteger(object.get("verticalRadius"), "verticalRadius");
            int maxDistance = parseInteger(object.get("maxDistance"), "maxDistance");
            int maxResults = object.has("maxResults")
                    ? parseInteger(object.get("maxResults"), "maxResults")
                    : this.settings.limits().defaultInspectionResultLimit();
            if (horizontalRadius < 0 || verticalRadius < 0) {
                throw new InvalidRequestException(
                        "horizontalRadius and verticalRadius must be non-negative");
            }
            if (maxDistance < 1) {
                throw new InvalidRequestException("maxDistance must be positive");
            }
            if (maxResults < 1
                    || maxResults > this.settings.limits().maxInspectionResultLimit()) {
                throw new InvalidRequestException(
                        "maxResults must be between 1 and "
                                + this.settings.limits().maxInspectionResultLimit());
            }
            return new OrthographicViewRequest(
                    parseString(object.get("world"), "world"),
                    parsePosition(object.get("origin"), "origin"),
                    parseOrthographicViewDirection(object.get("direction")),
                    horizontalRadius,
                    verticalRadius,
                    maxDistance,
                    maxResults);
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private ReplaceRegionBlocksRequest parseReplaceRegionBlocksRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            if (!object.keySet().containsAll(REPLACE_REGION_BLOCKS_REQUIRED_FIELDS)
                    || !REPLACE_REGION_BLOCKS_FIELDS.containsAll(object.keySet())) {
                throw new InvalidRequestException("Request contains missing or unknown fields");
            }
            return new ReplaceRegionBlocksRequest(
                    parseString(object.get("world"), "world"),
                    parsePosition(object.get("min"), "min"),
                    parsePosition(object.get("max"), "max"),
                    parseString(object.get("sourceBlockState"), "sourceBlockState"),
                    parseString(object.get("destinationBlockState"), "destinationBlockState"),
                    object.has("dryRun")
                            ? parseBoolean(object.get("dryRun"), "dryRun")
                            : this.settings.defaults().editDryRun());
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private UndoLastDirtEditRequest parseUndoLastDirtEditRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            requireFields(object, UNDO_FIELDS, "Request");
            return new UndoLastDirtEditRequest(parseString(object.get("world"), "world"));
        } catch (JsonParseException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private FillRegionRequest parseFillRegionRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            if (!object.keySet().containsAll(FILL_REGION_REQUIRED_FIELDS)
                    || !FILL_REGION_FIELDS.containsAll(object.keySet())) {
                throw new InvalidRequestException("Request contains missing or unknown fields");
            }
            return new FillRegionRequest(
                    parseString(object.get("world"), "world"),
                    parsePosition(object.get("min"), "min"),
                    parsePosition(object.get("max"), "max"),
                    parseString(object.get("blockState"), "blockState"),
                    object.has("dryRun")
                            ? parseBoolean(object.get("dryRun"), "dryRun")
                            : this.settings.defaults().editDryRun());
        } catch (JsonParseException | NumberFormatException | ArithmeticException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private RunCommandsRequest parseRunCommandsRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            requireFields(object, RUN_COMMANDS_FIELDS, "Request");
            return new RunCommandsRequest(parseStringList(object.get("commands"), "commands"));
        } catch (JsonParseException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private SetBlocksRequest parseSetBlocksRequest(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        try {
            JsonObject object = parseRequestObject(exchange);
            if (!object.keySet().containsAll(SET_BLOCKS_REQUIRED_FIELDS)
                    || !SET_BLOCKS_FIELDS.containsAll(object.keySet())) {
                throw new InvalidRequestException("Request contains missing or unknown fields");
            }
            return new SetBlocksRequest(
                    parseString(object.get("world"), "world"),
                    parseBlockChanges(object.get("changes")),
                    object.has("dryRun")
                            ? parseBoolean(object.get("dryRun"), "dryRun")
                            : this.settings.defaults().editDryRun());
        } catch (JsonParseException exception) {
            throw new InvalidRequestException("Request body must contain valid JSON values");
        }
    }

    private static List<BlockChange> parseBlockChanges(JsonElement element)
            throws InvalidRequestException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new InvalidRequestException("changes must be a non-empty array");
        }
        List<BlockChange> changes = new ArrayList<>(element.getAsJsonArray().size());
        for (int index = 0; index < element.getAsJsonArray().size(); index++) {
            JsonElement entry = element.getAsJsonArray().get(index);
            if (!entry.isJsonObject()) {
                throw new InvalidRequestException("changes[" + index + "] must be an object");
            }
            JsonObject object = entry.getAsJsonObject();
            requireFields(object, BLOCK_CHANGE_FIELDS, "changes[" + index + "]");
            changes.add(new BlockChange(
                    parsePosition(object.get("position"), "changes[" + index + "].position"),
                    parseString(object.get("blockState"), "changes[" + index + "].blockState")));
        }
        return List.copyOf(changes);
    }

    private JsonObject parseRequestObject(HttpExchange exchange)
            throws IOException, InvalidRequestException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT).equals("application/json")) {
            throw new InvalidRequestException("Content-Type must be application/json");
        }
        int maximumRequestBytes = this.settings.limits().maxRequestBytes();
        byte[] body = exchange.getRequestBody().readNBytes(maximumRequestBytes + 1);
        if (body.length > maximumRequestBytes) {
            throw new InvalidRequestException(
                    "Request body exceeds the maximum of " + maximumRequestBytes + " bytes");
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

    private static RegionBlocksFormat parseRegionBlocksFormat(JsonElement element)
            throws InvalidRequestException {
        return parseRegionBlocksFormat(parseString(element, "format"));
    }

    private static RegionBlocksFormat parseRegionBlocksFormat(String format)
            throws InvalidRequestException {
        return switch (format) {
            case "blocks" -> RegionBlocksFormat.BLOCKS;
            case "runs" -> RegionBlocksFormat.RUNS;
            default -> throw new InvalidRequestException("format must be blocks or runs");
        };
    }

    private static OrthographicViewDirection parseOrthographicViewDirection(JsonElement element)
            throws InvalidRequestException {
        String direction = parseString(element, "direction");
        return switch (direction) {
            case "north" -> OrthographicViewDirection.NORTH;
            case "east" -> OrthographicViewDirection.EAST;
            case "south" -> OrthographicViewDirection.SOUTH;
            case "west" -> OrthographicViewDirection.WEST;
            case "up" -> OrthographicViewDirection.UP;
            case "down" -> OrthographicViewDirection.DOWN;
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
