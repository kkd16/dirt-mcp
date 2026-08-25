package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

final class BridgeDispatcher implements AutoCloseable {
    private static final String CALL_ID_HEADER = "X-Dirt-Call-Id";

    private final Map<String, BridgeEndpoint> routes;
    private final Set<BridgeOperation> allowedOperations;
    private final BearerAuthenticator authenticator;
    private final Semaphore admissions;
    private final int maximumConcurrentRequests;
    private final int maximumRequestBytes;
    private final RequestBodyReader bodyReader;
    private final DirtLog log;

    BridgeDispatcher(
            List<BridgeEndpoint> endpoints,
            List<BridgeOperation> allowedOperations,
            BearerAuthenticator authenticator,
            int maximumConcurrentRequests,
            int maximumRequestBytes,
            int requestBodyTimeoutSeconds,
            DirtLog log) {
        this.routes = routes(endpoints);
        this.allowedOperations = Set.copyOf(allowedOperations);
        this.authenticator = authenticator;
        this.admissions = new Semaphore(maximumConcurrentRequests);
        this.maximumConcurrentRequests = maximumConcurrentRequests;
        this.maximumRequestBytes = maximumRequestBytes;
        this.bodyReader = new RequestBodyReader(requestBodyTimeoutSeconds);
        this.log = log;
    }

    void handle(HttpExchange rawExchange) throws IOException {
        long started = System.nanoTime();
        BridgeExchange exchange =
                new BridgeExchange(rawExchange, this.maximumRequestBytes, this.bodyReader);
        String operationId = "unknown";
        Throwable requestFailure = null;
        boolean unexpectedFailure = false;
        boolean recoveryRisk = false;
        boolean responseTransportFailure = false;
        try {
            List<String> authorization = rawExchange.getRequestHeaders().get("Authorization");
            if (authorization == null
                    || authorization.size() != 1
                    || !this.authenticator.accepts(authorization.getFirst())) {
                exchange.challenge();
                exchange.sendProblem(
                        401, "A valid bearer token is required", BridgeProblem.unauthorized());
                return;
            }

            if (rawExchange.getRequestURI().getRawQuery() != null) {
                exchange.sendProblem(
                        404,
                        "No bridge operation matches this path",
                        BridgeProblem.routeNotFound());
                return;
            }

            BridgeEndpoint endpoint = this.routes.get(rawExchange.getRequestURI().getRawPath());
            if (endpoint == null) {
                exchange.sendProblem(
                        404,
                        "No bridge operation matches this path",
                        BridgeProblem.routeNotFound());
                return;
            }
            operationId = endpoint.operationId();

            if (!endpoint.method().equals(rawExchange.getRequestMethod())) {
                exchange.allow(endpoint.method());
                exchange.sendProblem(
                        405,
                        "Method must be " + endpoint.method(),
                        BridgeProblem.methodNotAllowed(endpoint.method()));
                return;
            }

            if (!endpoint.isEnabled(this.allowedOperations)) {
                exchange.sendProblem(
                        403,
                        "Bridge operation is disabled",
                        BridgeProblem.operationDisabled(operationId));
                return;
            }

            try {
                exchange.parseCallId(rawExchange.getRequestHeaders().get(CALL_ID_HEADER));
            } catch (BridgeRequestException exception) {
                requestFailure = exception;
                exchange.sendProblem(400, exception.getMessage(), exception.problem());
                return;
            }

            if (!this.admissions.tryAcquire()) {
                exchange.sendProblem(
                        503,
                        "The bridge is handling too many requests",
                        BridgeProblem.bridgeBusy(this.maximumConcurrentRequests));
                return;
            }
            try {
                if ("GET".equals(endpoint.method())) {
                    exchange.requireEmptyBody();
                }
                endpoint.handle(exchange);
            } catch (RequestBodyReader.BodyTimeoutException exception) {
                requestFailure = exception;
                exchange.abort("request_body_timeout");
            } catch (BridgeRequestException exception) {
                requestFailure = exception;
                exchange.sendProblem(400, exception.getMessage(), exception.problem());
            } catch (OperationException exception) {
                requestFailure = exception;
                recoveryRisk = exception.editId().isPresent();
                FailureMapper.send(exchange, exception);
            } catch (RuntimeException exception) {
                requestFailure = exception;
                unexpectedFailure = true;
                exchange.sendInternalError(500, endpoint.internalErrorMessage());
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
                        operationId,
                        started,
                        preserveFirstFailure(requestFailure, exchange.auditFailure()),
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
            String operationId,
            long started,
            Throwable failure,
            boolean unexpectedFailure,
            boolean recoveryRisk,
            boolean responseTransportFailure) {
        Integer status = exchange.status();
        boolean transportFailure = responseTransportFailure || failure instanceof IOException;
        boolean internalFailure = status != null && status == 500;
        boolean uncertainCompletion =
                recoveryRisk || transportFailure && exchange.responseDeliveryRisk();
        LogContext context =
                LogContext.of("operation_id", operationId)
                        .with("method", rawExchange.getRequestMethod())
                        .with("path", rawExchange.getRequestURI().getRawPath())
                        .with(
                                "duration_ms",
                                Math.max(0, (System.nanoTime() - started) / 1_000_000));
        context = optional(context, "http_status", status);
        if (status == null || transportFailure) {
            context = context.with("aborted", true);
        }
        context = optional(context, "call_id", exchange.admittedCallId());
        context = optional(context, "request_bytes", exchange.requestBytes());
        context = optional(context, "response_bytes", exchange.responseBytes());
        context = optional(context, "error_code", exchange.errorCode());
        if (!unexpectedFailure && failure != null) {
            context = optional(context, "failure_reason", failure.getMessage());
        }
        for (Map.Entry<String, Object> field : exchange.auditFields().entrySet()) {
            context = context.with(field.getKey(), field.getValue());
        }

        String callSuffix =
                exchange.admittedCallId() == null
                        ? ""
                        : " (call " + exchange.admittedCallId() + ')';
        String message;
        if (uncertainCompletion) {
            message =
                    "Dirt MCP "
                            + operationId
                            + " may have completed without a response; inspect server state before retrying"
                            + callSuffix;
        } else if (unexpectedFailure || internalFailure || status != null && status >= 500) {
            message = "Dirt MCP " + operationId + " failed" + callSuffix;
        } else {
            message = "Dirt MCP bridge request completed";
        }

        Throwable loggedFailure = exchange.suppressesFailureDetails() ? null : failure;
        if (unexpectedFailure || internalFailure) {
            this.log.error("bridge", "bridge.request_completed", message, context, loggedFailure);
        } else if (uncertainCompletion
                || exchange.auditLevel() == BridgeExchange.AuditLevel.WARNING) {
            this.log.warning("bridge", "bridge.request_completed", message, context, loggedFailure);
        } else if (exchange.auditLevel() == BridgeExchange.AuditLevel.INFO) {
            this.log.info("bridge", "bridge.request_completed", message, context);
        } else {
            Throwable detailFailure =
                    transportFailure || failure != null && failure.getCause() != null
                            ? loggedFailure
                            : null;
            this.log.debug("bridge", "bridge.request_completed", message, context, detailFailure);
        }
    }

    private static LogContext optional(LogContext context, String key, Object value) {
        return value == null ? context : context.with(key, value);
    }

    private static Throwable preserveFirstFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (next == null) {
            return first;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static Map<String, BridgeEndpoint> routes(List<BridgeEndpoint> endpoints) {
        Map<String, BridgeEndpoint> byPath = new HashMap<>();
        Set<String> operationIds = new java.util.HashSet<>();
        for (BridgeEndpoint endpoint : endpoints) {
            if (!endpoint.path().startsWith("/v1/")) {
                throw new IllegalArgumentException(
                        "Bridge route must be under /v1/: " + endpoint.path());
            }
            if (byPath.putIfAbsent(endpoint.path(), endpoint) != null) {
                throw new IllegalArgumentException("Duplicate bridge path: " + endpoint.path());
            }
            if (!operationIds.add(endpoint.operationId())) {
                throw new IllegalArgumentException(
                        "Duplicate bridge operationId: " + endpoint.operationId());
            }
        }
        return Map.copyOf(byPath);
    }
}
