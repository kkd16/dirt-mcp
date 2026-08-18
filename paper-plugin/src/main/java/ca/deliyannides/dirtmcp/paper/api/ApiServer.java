package ca.deliyannides.dirtmcp.paper.api;

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

    private final int port;
    private final String responseBody;
    private final Logger logger;

    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(int port, String pluginVersion, String minecraftVersion, Logger logger) {
        this.port = port;
        this.logger = logger;
        this.responseBody = """
                {"status":"ok","service":"dirt-mcp-paper","version":%s,"minecraftVersion":%s,"capabilities":{"worldEditing":false}}
                """.formatted(jsonString(pluginVersion), jsonString(minecraftVersion)).strip();
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
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            send(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }

        send(exchange, 200, this.responseBody);
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

    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append("\\u%04x".formatted((int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
