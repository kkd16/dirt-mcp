package ca.deliyannides.dirtmcp.paper.api;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public final class ApiServer implements AutoCloseable {
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final Gson GSON = new Gson();

    private final int port;
    private final String responseBody;
    private final BearerAuthentication authentication;
    private final Logger logger;

    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(
            int port,
            String pluginVersion,
            String minecraftVersion,
            String bearerToken,
            Logger logger) {
        this.port = port;
        this.logger = logger;
        this.authentication = new BearerAuthentication(bearerToken);
        this.responseBody = GSON.toJson(new HealthResponse(
                "ok",
                "dirt-mcp-paper",
                pluginVersion,
                minecraftVersion,
                new Capabilities(false)));
    }

    public void start() throws IOException {
        if (this.server != null) {
            throw new IllegalStateException("Dirt MCP bridge is already running");
        }

        HttpServer newServer = HttpServer.create(new InetSocketAddress(LOOPBACK_ADDRESS, this.port), 0);
        ExecutorService newExecutor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            newServer.createContext("/v1/health", this::handleHealth);
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
            this.executor.close();
            this.executor = null;
        }

        this.logger.info("Dirt MCP bridge stopped.");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!this.authentication.accepts(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"dirt-mcp\"");
            sendError(exchange, 401, "unauthorized", "A valid bearer token is required");
            return;
        }

        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendError(exchange, 405, "method_not_allowed", "Method must be GET");
            return;
        }

        send(exchange, 200, this.responseBody);
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

    private record Capabilities(boolean worldEditing) {}

    private record ErrorEnvelope(ErrorDetail error) {}

    private record ErrorDetail(String code, String message) {}
}
