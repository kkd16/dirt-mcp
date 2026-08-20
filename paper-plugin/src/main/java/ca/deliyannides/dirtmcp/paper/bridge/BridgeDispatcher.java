package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

final class BridgeDispatcher implements AutoCloseable {
    private static final String CALL_ID_HEADER = "X-Dirt-Call-Id";
    private static final Pattern CALL_ID_PATTERN =
            Pattern.compile(
                    "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89ABab][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}");

    private final Map<String, Map<String, BridgeEndpoint>> routes;
    private final BearerAuthenticator authenticator;
    private final Semaphore admissions;
    private final int maximumRequestBytes;
    private final RequestBodyReader bodyReader;
    private final DirtLog log;

    BridgeDispatcher(
            List<BridgeEndpoint> endpoints,
            BearerAuthenticator authenticator,
            int maximumConcurrentRequests,
            int maximumRequestBytes,
            int requestBodyTimeoutSeconds,
            DirtLog log) {
        this.routes = routes(endpoints);
        this.authenticator = authenticator;
        this.admissions = new Semaphore(maximumConcurrentRequests);
        this.maximumRequestBytes = maximumRequestBytes;
        this.bodyReader = new RequestBodyReader(requestBodyTimeoutSeconds);
        this.log = log;
    }

    void handle(HttpExchange rawExchange) throws IOException {
        long started = System.nanoTime();
        BridgeExchange exchange =
                new BridgeExchange(rawExchange, this.maximumRequestBytes, this.bodyReader);
        String operation = "unknown";
        Throwable requestFailure = null;
        boolean unexpectedFailure = false;
        boolean recoveryRisk = false;
        boolean responseTransportFailure = false;
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
            } catch (RequestBodyReader.BodyTimeoutException exception) {
                requestFailure = exception;
                exchange.abort("request_body_timeout");
            } catch (InvalidRequestException exception) {
                requestFailure = exception;
                exchange.sendError(400, "invalid_request", exception.getMessage());
            } catch (OperationException exception) {
                requestFailure = exception;
                recoveryRisk = exception.editId().isPresent();
                FailureMapper.send(exchange, exception);
            } catch (RuntimeException exception) {
                requestFailure = exception;
                unexpectedFailure = true;
                if (exchange.editId() == null) {
                    exchange.sendError(500, "internal_error", endpoint.internalErrorMessage());
                } else {
                    recoveryRisk = true;
                    exchange.sendError(
                            500,
                            "internal_error",
                            endpoint.internalErrorMessage(),
                            exchange.editId());
                }
            } finally {
                this.admissions.release();
            }
        } catch (IOException failure) {
            responseTransportFailure = true;
            requestFailure = preserveFirstFailure(requestFailure, failure);
            throw failure;
        } catch (RuntimeException failure) {
            requestFailure = preserveFirstFailure(requestFailure, failure);
            unexpectedFailure = true;
            throw failure;
        } finally {
            try {
                audit(
                        rawExchange,
                        exchange,
                        operation,
                        started,
                        requestFailure,
                        unexpectedFailure,
                        recoveryRisk,
                        responseTransportFailure);
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
            HttpExchange rawExchange,
            BridgeExchange exchange,
            String operation,
            long started,
            Throwable failure,
            boolean unexpectedFailure,
            boolean recoveryRisk,
            boolean responseTransportFailure) {
        Integer status = exchange.status();
        boolean transportFailure = responseTransportFailure || failure instanceof IOException;
        boolean internalFailure =
                status != null && status == 500 && "internal_error".equals(exchange.errorCode());
        boolean recoveryAmbiguity = recoveryRisk || transportFailure && exchange.editId() != null;
        String callId = rawExchange.getRequestHeaders().getFirst(CALL_ID_HEADER);
        String canonicalCallId =
                callId != null && CALL_ID_PATTERN.matcher(callId).matches()
                        ? callId.toLowerCase(Locale.ROOT)
                        : null;
        LogContext context =
                LogContext.of("operation", operation)
                        .with("method", rawExchange.getRequestMethod())
                        .with("path", rawExchange.getRequestURI().getRawPath())
                        .with(
                                "duration_ms",
                                Math.max(0, (System.nanoTime() - started) / 1_000_000));
        context = optional(context, "http_status", status);
        if (status == null || transportFailure) {
            context = context.with("aborted", true);
        }
        context = optional(context, "world", exchange.world());
        context = optional(context, "call_id", canonicalCallId);
        context = optional(context, "request_bytes", exchange.requestBytes());
        context = optional(context, "response_bytes", exchange.responseBytes());
        context = optional(context, "error_code", exchange.errorCode());
        if (!unexpectedFailure && failure != null) {
            context = optional(context, "failure_reason", failure.getMessage());
        }
        context = optional(context, "edit_id", exchange.editId());
        context = optional(context, "outcome", exchange.outcome());
        context = optional(context, "changed_block_count", exchange.changedBlockCount());
        context = optional(context, "result_count", exchange.resultCount());
        context = optional(context, "bounds", exchange.bounds());

        String message =
                summary(
                        operation,
                        exchange,
                        canonicalCallId,
                        unexpectedFailure || internalFailure,
                        recoveryAmbiguity,
                        transportFailure);
        if (unexpectedFailure) {
            this.log.error("bridge", "bridge.request_completed", message, context, failure);
        } else if (recoveryAmbiguity) {
            this.log.warning("bridge", "bridge.request_completed", message, context, failure);
        } else if (internalFailure) {
            this.log.error("bridge", "bridge.request_completed", message, context, failure);
        } else if (status != null
                && status == 200
                && ("committed".equals(exchange.outcome())
                        || "undone".equals(exchange.outcome()))) {
            this.log.info("bridge", "bridge.request_completed", message, context);
        } else {
            Throwable detailFailure =
                    transportFailure || failure != null && failure.getCause() != null
                            ? failure
                            : null;
            this.log.debug("bridge", "bridge.request_completed", message, context, detailFailure);
        }
    }

    private static LogContext optional(LogContext context, String key, Object value) {
        return value == null ? context : context.with(key, value);
    }

    private static String summary(
            String operation,
            BridgeExchange exchange,
            String canonicalCallId,
            boolean internalFailure,
            boolean recoveryAmbiguity,
            boolean transportFailure) {
        if (recoveryAmbiguity) {
            boolean knownCompletion =
                    "committed".equals(exchange.outcome()) || "undone".equals(exchange.outcome());
            String completion;
            if (!transportFailure) {
                completion = "requires edit-history reconciliation";
            } else if (knownCompletion) {
                completion = "completed, but its response could not be delivered";
            } else {
                completion = "response delivery failed and edit history must be reconciled";
            }
            return "Dirt MCP "
                    + operation
                    + ' '
                    + completion
                    + "; reconcile edit "
                    + exchange.editId()
                    + (canonicalCallId == null ? "" : " from call " + canonicalCallId)
                    + " before retrying";
        }
        if (!internalFailure && "committed".equals(exchange.outcome())) {
            return "Dirt MCP committed "
                    + operation
                    + " in world "
                    + exchange.world()
                    + " ("
                    + exchange.changedBlockCount()
                    + " blocks, edit "
                    + exchange.editId()
                    + ')';
        }
        if (!internalFailure && "undone".equals(exchange.outcome())) {
            return "Dirt MCP undid edit "
                    + exchange.editId()
                    + " in world "
                    + exchange.world()
                    + " ("
                    + exchange.changedBlockCount()
                    + " blocks)";
        }
        if (internalFailure || exchange.status() != null && exchange.status() >= 500) {
            return "Dirt MCP "
                    + operation
                    + " failed"
                    + (canonicalCallId == null ? "" : " (call " + canonicalCallId + ')');
        }
        return "Dirt MCP bridge request completed";
    }

    private static Throwable preserveFirstFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
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
