package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

final class BridgeDispatcher implements AutoCloseable {
    private static final String CALL_ID_HEADER = "X-Dirt-Call-Id";
    private static final Pattern CALL_ID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final Map<String, Map<String, BridgeEndpoint>> routes;
    private final BearerAuthenticator authenticator;
    private final Semaphore admissions;
    private final int maximumRequestBytes;
    private final RequestBodyReader bodyReader;
    private final Logger logger;

    BridgeDispatcher(
            List<BridgeEndpoint> endpoints,
            BearerAuthenticator authenticator,
            int maximumConcurrentRequests,
            int maximumRequestBytes,
            int requestBodyTimeoutSeconds,
            Logger logger) {
        this.routes = routes(endpoints);
        this.authenticator = authenticator;
        this.admissions = new Semaphore(maximumConcurrentRequests);
        this.maximumRequestBytes = maximumRequestBytes;
        this.bodyReader = new RequestBodyReader(requestBodyTimeoutSeconds);
        this.logger = logger;
    }

    void handle(HttpExchange rawExchange) throws IOException {
        long started = System.nanoTime();
        BridgeExchange exchange =
                new BridgeExchange(rawExchange, this.maximumRequestBytes, this.bodyReader);
        String operation = "unknown";
        try {
            if (!this.authenticator.accepts(
                    rawExchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.challenge();
                exchange.sendError(401, "unauthorized", "A valid bearer token is required");
                return;
            }

            if (rawExchange.getRequestURI().getRawQuery() != null) {
                exchange.sendError(404, "not_found", "No bridge operation matches this path");
                return;
            }

            Map<String, BridgeEndpoint> methods =
                    this.routes.get(rawExchange.getRequestURI().getRawPath());
            if (methods == null) {
                exchange.sendError(404, "not_found", "No bridge operation matches this path");
                return;
            }

            BridgeEndpoint endpoint = methods.get(rawExchange.getRequestMethod());
            if (endpoint == null) {
                String allow = String.join(", ", methods.keySet());
                exchange.allow(allow);
                exchange.sendError(405, "method_not_allowed", "Method must be " + allow);
                return;
            }
            operation = endpoint.operation();

            if (!this.admissions.tryAcquire()) {
                exchange.sendError(503, "bridge_busy", "The bridge is handling too many requests");
                return;
            }
            try {
                endpoint.handle(exchange);
            } catch (RequestTimeoutException exception) {
                exchange.abort();
            } catch (InvalidRequestException exception) {
                exchange.sendError(400, "invalid_request", exception.getMessage());
            } catch (OperationException exception) {
                FailureMapper.send(exchange, exception);
            } catch (RuntimeException exception) {
                if (this.logger.isLoggable(Level.SEVERE)) {
                    this.logger.log(
                            Level.SEVERE,
                            "Unexpected " + endpoint.operation() + " failure",
                            exception);
                }
                exchange.sendError(500, "internal_error", endpoint.internalErrorMessage());
            } finally {
                this.admissions.release();
            }
        } finally {
            try {
                audit(rawExchange, exchange, operation, started);
            } finally {
                rawExchange.close();
            }
        }
    }

    @Override
    public void close() {
        this.bodyReader.close();
    }

    private void audit(
            HttpExchange rawExchange, BridgeExchange exchange, String operation, long started) {
        if (this.logger.isLoggable(Level.INFO)) {
            Integer status = exchange.status();
            String world = exchange.world();
            String callId = rawExchange.getRequestHeaders().getFirst(CALL_ID_HEADER);
            StringBuilder message =
                    new StringBuilder("Dirt MCP bridge_call operation=")
                            .append(operation)
                            .append(" method=")
                            .append(rawExchange.getRequestMethod())
                            .append(" status=")
                            .append(status == null ? "aborted" : status);
            if (world != null) {
                message.append(" world=").append(BridgeJson.GSON.toJson(world));
            }
            if (callId != null && CALL_ID_PATTERN.matcher(callId).matches()) {
                message.append(" call=").append(callId);
            }
            message.append(" duration_ms=")
                    .append(Math.max(0, (System.nanoTime() - started) / 1_000_000));
            this.logger.info(message.toString());
        }
    }

    private static Map<String, Map<String, BridgeEndpoint>> routes(List<BridgeEndpoint> endpoints) {
        Map<String, Map<String, BridgeEndpoint>> byPath = new LinkedHashMap<>();
        for (BridgeEndpoint endpoint : endpoints) {
            if (!endpoint.path().startsWith("/v1/")) {
                throw new IllegalArgumentException(
                        "Bridge route must be under /v1/: " + endpoint.path());
            }
            Map<String, BridgeEndpoint> methods =
                    byPath.computeIfAbsent(endpoint.path(), ignored -> new LinkedHashMap<>());
            if (methods.putIfAbsent(endpoint.method(), endpoint) != null) {
                throw new IllegalArgumentException(
                        "Duplicate bridge route: " + endpoint.method() + " " + endpoint.path());
            }
        }
        Map<String, Map<String, BridgeEndpoint>> frozen = new LinkedHashMap<>();
        byPath.forEach((path, methods) -> frozen.put(path, Map.copyOf(methods)));
        return Map.copyOf(frozen);
    }
}
