package ca.deliyannides.dirtmcp.paper.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

final class BridgeDispatcherTest {
    @Test
    void rejectsDuplicatePathsOperationsAndUnversionedRoutes() {
        BridgeEndpoint ping = endpoint("pingServer", "GET", "/v1/ping", exchange -> {});
        BridgeEndpoint duplicatePath = endpoint("getBlocks", "POST", "/v1/ping", exchange -> {});
        BridgeEndpoint duplicateOperation =
                endpoint("pingServer", "POST", "/v1/other", exchange -> {});

        assertThrows(
                IllegalArgumentException.class,
                () -> dispatcher(List.of(ping, duplicatePath), allOperations(), log()));
        assertThrows(
                IllegalArgumentException.class,
                () -> dispatcher(List.of(ping, duplicateOperation), allOperations(), log()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        dispatcher(
                                List.of(endpoint("pingServer", "GET", "/ping", exchange -> {})),
                                allOperations(),
                                log()));
    }

    @Test
    void authenticatesBeforeDisclosingRoutes() throws Exception {
        TestExchange exchange = new TestExchange("GET", "/v1/missing");
        exchange.requestHeaders.clear();
        BridgeDispatcher dispatcher =
                dispatcher(
                        List.of(endpoint("pingServer", "GET", "/v1/ping", ignored -> {})),
                        allOperations(),
                        log());

        try (dispatcher) {
            dispatcher.handle(exchange);
        }

        assertEquals(401, exchange.getResponseCode());
        assertEquals(
                "Bearer realm=\"dirt-mcp\"", exchange.responseHeaders.getFirst("WWW-Authenticate"));
    }

    @Test
    void rejectsAmbiguousAuthorizationHeaders() throws Exception {
        TestExchange exchange = new TestExchange("GET", "/v1/ping");
        exchange.requestHeaders.add("Authorization", "Bearer invalid");
        BridgeDispatcher dispatcher =
                dispatcher(
                        List.of(endpoint("pingServer", "GET", "/v1/ping", ignored -> {})),
                        allOperations(),
                        log());

        try (dispatcher) {
            dispatcher.handle(exchange);
        }

        assertEquals(401, exchange.getResponseCode());
    }

    @Test
    void rejectsDisabledOperationsBeforeCallIdAndHandlerAdmission() throws Exception {
        AtomicBoolean handled = new AtomicBoolean();
        TestExchange exchange = new TestExchange("POST", "/v1/get-blocks");
        exchange.requestHeaders.remove("X-Dirt-Call-Id");
        BridgeDispatcher dispatcher =
                dispatcher(
                        List.of(
                                endpoint(
                                        "getBlocks",
                                        "POST",
                                        "/v1/get-blocks",
                                        ignored -> handled.set(true))),
                        List.of(),
                        log());

        try (dispatcher) {
            dispatcher.handle(exchange);
        }

        assertEquals(403, exchange.getResponseCode());
        assertFalse(handled.get());
        var error =
                BridgeTestFixture.json(exchange.responseBody())
                        .getAsJsonObject()
                        .getAsJsonObject("error");
        assertEquals("operation_disabled", error.get("code").getAsString());
        assertEquals(
                "getBlocks", error.getAsJsonObject("details").get("operationId").getAsString());
    }

    @Test
    void centrallyRequiresExactlyOneCanonicalUuidV4CallId() throws Exception {
        AtomicBoolean handled = new AtomicBoolean();
        BridgeEndpoint endpoint =
                endpoint("pingServer", "GET", "/v1/ping", ignored -> handled.set(true));
        for (List<String> values :
                List.of(
                        List.<String>of(),
                        List.of("123e4567-e89b-12d3-a456-426614174000"),
                        List.of(BridgeTestFixture.CALL_ID, BridgeTestFixture.CALL_ID))) {
            TestExchange exchange = new TestExchange("GET", "/v1/ping");
            exchange.requestHeaders.remove("X-Dirt-Call-Id");
            values.forEach(value -> exchange.requestHeaders.add("X-Dirt-Call-Id", value));
            BridgeDispatcher dispatcher = dispatcher(List.of(endpoint), allOperations(), log());

            try (dispatcher) {
                dispatcher.handle(exchange);
            }

            assertEquals(400, exchange.getResponseCode());
        }
        assertFalse(handled.get());
    }

    @Test
    void makesTheCentrallyParsedCallIdAvailableToAnEndpoint() throws Exception {
        AtomicReference<UUID> callId = new AtomicReference<>();
        BridgeEndpoint endpoint =
                endpoint(
                        "pingServer",
                        "GET",
                        "/v1/ping",
                        exchange -> {
                            callId.set(exchange.callId());
                            exchange.ok(Map.of("status", "ok"));
                        });
        TestExchange exchange = new TestExchange("GET", "/v1/ping");
        BridgeDispatcher dispatcher = dispatcher(List.of(endpoint), allOperations(), log());

        try (dispatcher) {
            dispatcher.handle(exchange);
        }

        assertEquals(UUID.fromString(BridgeTestFixture.CALL_ID), callId.get());
        assertEquals(200, exchange.getResponseCode());
    }

    @Test
    void rejectsBodiesOnGetOperations() throws Exception {
        AtomicBoolean handled = new AtomicBoolean();
        BridgeEndpoint endpoint =
                endpoint("pingServer", "GET", "/v1/ping", ignored -> handled.set(true));
        TestExchange exchange = new TestExchange("GET", "/v1/ping");
        exchange.setStreams(
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());

        try (BridgeDispatcher dispatcher = dispatcher(List.of(endpoint), allOperations(), log())) {
            dispatcher.handle(exchange);
        }

        assertEquals(400, exchange.getResponseCode());
        assertFalse(handled.get());
        var details =
                BridgeTestFixture.json(exchange.responseBody())
                        .getAsJsonObject()
                        .getAsJsonObject("error")
                        .getAsJsonObject("details");
        assertEquals("invalid_value", details.get("reason").getAsString());
        assertEquals("body", details.get("field").getAsString());
    }

    @Test
    void rejectsAmbiguousContentTypeHeaders() throws Exception {
        AtomicBoolean handled = new AtomicBoolean();
        BridgeEndpoint endpoint =
                endpoint(
                        "getBlocks",
                        "POST",
                        "/v1/get-blocks",
                        exchange -> {
                            exchange.readJsonObject();
                            handled.set(true);
                        });
        TestExchange exchange = new TestExchange("POST", "/v1/get-blocks");
        exchange.requestHeaders.add("Content-Type", "application/json");
        exchange.requestHeaders.add("Content-Type", "text/plain");
        exchange.setStreams(
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());

        try (BridgeDispatcher dispatcher = dispatcher(List.of(endpoint), allOperations(), log())) {
            dispatcher.handle(exchange);
        }

        assertEquals(400, exchange.getResponseCode());
        assertFalse(handled.get());
        var details =
                BridgeTestFixture.json(exchange.responseBody())
                        .getAsJsonObject()
                        .getAsJsonObject("error")
                        .getAsJsonObject("details");
        assertEquals("unsupported_media_type", details.get("reason").getAsString());
        assertEquals("application/json", details.get("expected").getAsString());
    }

    @Test
    void mapsExpectedAndUnexpectedEndpointFailures() throws Exception {
        BridgeEndpoint expected =
                endpoint(
                        "pingServer",
                        "GET",
                        "/v1/ping",
                        exchange -> {
                            throw new OperationException(
                                    OperationFailure.UNHEALTHY,
                                    "not healthy",
                                    new ErrorDetails.Unhealthy.HealthCheckFailed());
                        });
        TestExchange expectedExchange = new TestExchange("GET", "/v1/ping");
        try (BridgeDispatcher dispatcher = dispatcher(List.of(expected), allOperations(), log())) {
            dispatcher.handle(expectedExchange);
        }
        assertEquals(503, expectedExchange.getResponseCode());

        BridgeEndpoint unexpected =
                endpoint(
                        "pingServer",
                        "GET",
                        "/v1/ping",
                        exchange -> {
                            throw new IllegalStateException("private detail");
                        });
        TestExchange unexpectedExchange = new TestExchange("GET", "/v1/ping");
        try (BridgeDispatcher dispatcher =
                dispatcher(List.of(unexpected), allOperations(), log())) {
            dispatcher.handle(unexpectedExchange);
        }
        assertEquals(500, unexpectedExchange.getResponseCode());
        assertFalse(unexpectedExchange.responseBody().contains("private detail"));
    }

    @Test
    void sanitizesMutationOnlyWorldUnavailableFailureWithoutEditId() throws Exception {
        OperationException failure =
                new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        "private mutation detail",
                        new ErrorDetails.WorldUnavailable.OperationFailed());
        BridgeEndpoint endpoint =
                endpoint(
                        "setBlocks",
                        "POST",
                        "/v1/set-blocks",
                        exchange -> {
                            throw failure;
                        });
        TestExchange exchange = new TestExchange("POST", "/v1/set-blocks");
        try (BridgeDispatcher dispatcher = dispatcher(List.of(endpoint), allOperations(), log())) {
            dispatcher.handle(exchange);
        }

        assertEquals(500, exchange.getResponseCode());
        assertEquals(
                BridgeTestFixture.json(
                        """
                        {"error":{"code":"internal_error",
                                  "message":"The operation failed unexpectedly"}}
                        """),
                BridgeTestFixture.json(exchange.responseBody()));
        assertFalse(exchange.responseBody().contains(failure.getMessage()));
    }

    @Test
    void preservesBaseWorldUnavailableFailureWithEditId() throws Exception {
        UUID editId = UUID.fromString("223e4567-e89b-42d3-a456-426614174000");
        OperationException failure =
                new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        "Paper became unavailable",
                        new ErrorDetails.WorldUnavailable.PaperUnavailable(),
                        null,
                        editId);
        BridgeEndpoint endpoint =
                endpoint(
                        "setBlocks",
                        "POST",
                        "/v1/set-blocks",
                        exchange -> {
                            throw failure;
                        });
        TestExchange exchange = new TestExchange("POST", "/v1/set-blocks");
        try (BridgeDispatcher dispatcher = dispatcher(List.of(endpoint), allOperations(), log())) {
            dispatcher.handle(exchange);
        }

        assertEquals(503, exchange.getResponseCode());
        assertEquals(
                BridgeTestFixture.json(
                        """
                        {"error":{"code":"world_unavailable",
                                  "message":"Paper became unavailable",
                                  "details":{"reason":"paper_unavailable"},
                                  "editId":"223e4567-e89b-42d3-a456-426614174000"}}
                        """),
                BridgeTestFixture.json(exchange.responseBody()));
    }

    @Test
    void logsOnlyExplicitGenericEndpointMetadata() throws Exception {
        List<LogRecord> records = new ArrayList<>();
        DirtLog log = recordingLog(records);
        BridgeEndpoint endpoint =
                endpoint(
                        "setBlocks",
                        "POST",
                        "/v1/set-blocks",
                        exchange -> {
                            exchange.auditField("world", "world");
                            exchange.auditField("outcome", "committed");
                            exchange.auditCompletion(BridgeExchange.AuditLevel.INFO, true);
                            exchange.ok(Map.of("ok", true));
                        });
        TestExchange exchange = new TestExchange("POST", "/v1/set-blocks");

        try (log;
                BridgeDispatcher dispatcher = dispatcher(List.of(endpoint), allOperations(), log)) {
            dispatcher.handle(exchange);
        }

        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.getFirst().getLevel());
        LogContext context = (LogContext) records.getFirst().getParameters()[0];
        assertEquals("setBlocks", context.values().get("operation_id"));
        assertEquals("world", context.values().get("world"));
        assertEquals("committed", context.values().get("outcome"));
    }

    private static BridgeDispatcher dispatcher(
            List<BridgeEndpoint> endpoints, List<BridgeOperation> allowedOperations, DirtLog log) {
        return new BridgeDispatcher(
                endpoints,
                allowedOperations,
                new BearerAuthenticator(BridgeTestFixture.TOKEN),
                2,
                1_024,
                1,
                log);
    }

    private static List<BridgeOperation> allOperations() {
        return List.of(BridgeOperation.values());
    }

    private static BridgeEndpoint endpoint(
            String operationId, String method, String path, EndpointHandler handler) {
        return new BridgeEndpoint() {
            @Override
            public String operationId() {
                return operationId;
            }

            @Override
            public String method() {
                return method;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public void handle(BridgeExchange exchange) throws IOException, OperationException {
                handler.handle(exchange);
            }

            @Override
            public String internalErrorMessage() {
                return "Safe internal failure";
            }
        };
    }

    private static DirtLog log() {
        return DirtLog.consoleOnly(NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR);
    }

    private static DirtLog recordingLog(List<LogRecord> records) {
        Handler handler =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        records.add(record);
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        return DirtLog.withDetailHandler(
                NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR, handler);
    }

    @FunctionalInterface
    private interface EndpointHandler {
        void handle(BridgeExchange exchange) throws IOException, OperationException;
    }

    private static final class TestExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final Map<String, Object> attributes = new HashMap<>();
        private final URI requestUri;
        private final String requestMethod;
        private InputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private OutputStream responseBody = new ByteArrayOutputStream();
        private ByteArrayOutputStream capturedResponse = new ByteArrayOutputStream();
        private int responseCode = -1;

        private TestExchange(String requestMethod, String path) {
            this.requestMethod = requestMethod;
            this.requestUri = URI.create(path);
            this.requestHeaders.add("Authorization", "Bearer " + BridgeTestFixture.TOKEN);
            this.requestHeaders.add("X-Dirt-Call-Id", BridgeTestFixture.CALL_ID);
        }

        @Override
        public Headers getRequestHeaders() {
            return this.requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return this.responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return this.requestUri;
        }

        @Override
        public String getRequestMethod() {
            return this.requestMethod;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {}

        @Override
        public InputStream getRequestBody() {
            return this.requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return this.responseBody;
        }

        @Override
        public void sendResponseHeaders(int status, long responseLength) {
            this.responseCode = status;
            this.capturedResponse = new ByteArrayOutputStream();
            this.responseBody = this.capturedResponse;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12_345);
        }

        @Override
        public int getResponseCode() {
            return this.responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8_765);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return this.attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            this.attributes.put(name, value);
        }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
            this.requestBody = input;
            this.responseBody = output;
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }

        private String responseBody() {
            return this.capturedResponse.toString(StandardCharsets.UTF_8);
        }
    }
}
