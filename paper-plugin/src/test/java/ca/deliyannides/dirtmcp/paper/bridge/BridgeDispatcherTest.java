package ca.deliyannides.dirtmcp.paper.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.world.edit.DestinationPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOperation;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOutcome;
import ca.deliyannides.dirtmcp.paper.world.edit.EditRecord;
import ca.deliyannides.dirtmcp.paper.world.edit.EditStatus;
import ca.deliyannides.dirtmcp.paper.world.edit.FillRegion;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

final class BridgeDispatcherTest {
    private static final UUID EDIT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void rejectsDuplicateRoutes() {
        BridgeEndpoint first = endpoint("first", "GET", "/v1/ping");
        BridgeEndpoint duplicate = endpoint("duplicate", "GET", "/v1/ping");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BridgeDispatcher(
                                List.of(first, duplicate),
                                new BearerAuthenticator(BridgeTestFixture.TOKEN),
                                1,
                                1024,
                                1,
                                log()));
    }

    @Test
    void rejectsRoutesOutsideTheVersionedNamespace() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BridgeDispatcher(
                                List.of(endpoint("bad", "GET", "/ping")),
                                new BearerAuthenticator(BridgeTestFixture.TOKEN),
                                1,
                                1024,
                                1,
                                log()));
    }

    @Test
    void preservesTheApplicationFailureWhenErrorDeliveryAlsoFails() {
        List<LogRecord> records = new ArrayList<>();
        DirtLog log = recordingLog(records);
        BridgeEndpoint endpoint =
                new BridgeEndpoint() {
                    @Override
                    public String operation() {
                        return "ping_server";
                    }

                    @Override
                    public String method() {
                        return "GET";
                    }

                    @Override
                    public String path() {
                        return "/v1/ping";
                    }

                    @Override
                    public void handle(BridgeExchange exchange) {
                        throw new IllegalStateException("application failure");
                    }

                    @Override
                    public String internalErrorMessage() {
                        return "safe failure";
                    }
                };
        BridgeDispatcher dispatcher =
                new BridgeDispatcher(
                        List.of(endpoint),
                        new BearerAuthenticator(BridgeTestFixture.TOKEN),
                        1,
                        1_024,
                        1,
                        log);

        try (log;
                dispatcher) {
            IOException deliveryFailure =
                    assertThrows(IOException.class, () -> dispatcher.handle(new FailingExchange()));

            assertEquals("delivery failure", deliveryFailure.getMessage());
            assertEquals(1, records.size());
            LogRecord audit = records.getFirst();
            assertEquals(Level.SEVERE, audit.getLevel());
            assertEquals("application failure", audit.getThrown().getMessage());
            assertEquals(1, audit.getThrown().getSuppressed().length);
            assertEquals(deliveryFailure, audit.getThrown().getSuppressed()[0]);
            LogContext context = (LogContext) audit.getParameters()[0];
            assertEquals(true, context.values().get("aborted"));
            assertTrue(audit.getMessage().contains("failed"));
        }
    }

    @Test
    void preservesKnownEditMetadataWhenFinalizingAResponseFails() throws IOException {
        List<LogRecord> records = new ArrayList<>();
        DirtLog log = recordingLog(records);
        BridgeEndpoint endpoint =
                new BridgeEndpoint() {
                    @Override
                    public String operation() {
                        return "fill_region";
                    }

                    @Override
                    public String method() {
                        return "GET";
                    }

                    @Override
                    public String path() {
                        return "/v1/ping";
                    }

                    @Override
                    public void handle(BridgeExchange exchange) throws IOException {
                        exchange.ok(committedFillResult());
                    }

                    @Override
                    public String internalErrorMessage() {
                        return "safe failure";
                    }
                };
        BridgeDispatcher dispatcher =
                new BridgeDispatcher(
                        List.of(endpoint),
                        new BearerAuthenticator(BridgeTestFixture.TOKEN),
                        1,
                        1_024,
                        1,
                        log);
        FailingExchange exchange = new FailingExchange(DeliveryFailure.FIRST_RUNTIME);

        try (log;
                dispatcher) {
            dispatcher.handle(exchange);

            assertEquals(500, exchange.getResponseCode());
            assertTrue(exchange.responseBody().contains(EDIT_ID.toString()));
            assertEquals(1, records.size());
            LogRecord audit = records.getFirst();
            assertEquals(Level.SEVERE, audit.getLevel());
            assertTrue(audit.getMessage().contains("reconcile edit " + EDIT_ID));
            LogContext context = (LogContext) audit.getParameters()[0];
            assertEquals(EDIT_ID, context.values().get("edit_id"));
            assertEquals("internal_error", context.values().get("error_code"));
        }
    }

    @Test
    void dispatcherOwnsAndClosesTheExchangeOnce() throws IOException {
        BridgeEndpoint endpoint = endpoint("ping_server", "GET", "/v1/ping");
        BridgeDispatcher dispatcher =
                new BridgeDispatcher(
                        List.of(endpoint),
                        new BearerAuthenticator(BridgeTestFixture.TOKEN),
                        1,
                        1_024,
                        1,
                        log());
        FailingExchange exchange = new FailingExchange(DeliveryFailure.NONE);

        try (dispatcher) {
            dispatcher.handle(exchange);
        }

        assertEquals(1, exchange.closeCalls());
    }

    private static BridgeEndpoint endpoint(String operation, String method, String path) {
        return new BridgeEndpoint() {
            @Override
            public String operation() {
                return operation;
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
            public void handle(BridgeExchange exchange) throws IOException {
                exchange.ok(Map.of("status", "ok"));
            }

            @Override
            public String internalErrorMessage() {
                return "failure";
            }
        };
    }

    private static FillRegion.Result committedFillResult() {
        BlockBounds bounds =
                new BlockBounds(new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0));
        EditRecord edit =
                new EditRecord(
                        EDIT_ID,
                        UUID.fromString("22222222-2222-4222-8222-222222222222"),
                        EditOperation.FILL_REGION,
                        "world",
                        UUID.fromString("33333333-3333-4333-8333-333333333333"),
                        bounds,
                        1,
                        "2026-08-20T00:00:00Z",
                        EditStatus.COMMITTED);
        return new FillRegion.Result(
                "world",
                bounds,
                List.of(new DestinationPaletteEntry("minecraft:stone", null)),
                0,
                EditOutcome.COMMITTED,
                1,
                1,
                edit);
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

    private static final class FailingExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final Map<String, Object> attributes = new HashMap<>();
        private InputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private final DeliveryFailure deliveryFailure;
        private OutputStream responseBody = new ByteArrayOutputStream();
        private ByteArrayOutputStream capturedResponse = new ByteArrayOutputStream();
        private int responseCode = -1;
        private int responseAttempts;
        private int closeCalls;

        private FailingExchange() {
            this(DeliveryFailure.ALWAYS_IO);
        }

        private FailingExchange(DeliveryFailure deliveryFailure) {
            this.deliveryFailure = deliveryFailure;
            this.requestHeaders.add("Authorization", "Bearer " + BridgeTestFixture.TOKEN);
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
            return URI.create("/v1/ping");
        }

        @Override
        public String getRequestMethod() {
            return "GET";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
            this.closeCalls++;
        }

        @Override
        public InputStream getRequestBody() {
            return this.requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return this.responseBody;
        }

        @Override
        public void sendResponseHeaders(int status, long responseLength) throws IOException {
            this.responseAttempts++;
            if (this.deliveryFailure == DeliveryFailure.ALWAYS_IO) {
                throw new IOException("delivery failure");
            }
            if (this.deliveryFailure == DeliveryFailure.FIRST_RUNTIME
                    && this.responseAttempts == 1) {
                throw new IllegalStateException("response finalization failure");
            }
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

        private int closeCalls() {
            return this.closeCalls;
        }
    }

    private enum DeliveryFailure {
        NONE,
        ALWAYS_IO,
        FIRST_RUNTIME
    }
}
