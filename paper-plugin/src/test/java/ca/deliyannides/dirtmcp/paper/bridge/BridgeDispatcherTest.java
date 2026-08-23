package ca.deliyannides.dirtmcp.paper.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.edit.DestinationPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOperation;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOutcome;
import ca.deliyannides.dirtmcp.paper.world.edit.EditRecord;
import ca.deliyannides.dirtmcp.paper.world.edit.EditStatus;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdits;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEditsException;
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
import java.time.Instant;
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
    void rejectsAnEmptyDirectErrorMessage() {
        try (RequestBodyReader reader = new RequestBodyReader(1)) {
            BridgeExchange exchange =
                    new BridgeExchange(new FailingExchange(DeliveryFailure.NONE), 1_024, reader);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> exchange.sendError(404, "", new ErrorDetails.NotFound()));
        }
    }

    @Test
    void rejectsNonVersionFourEditIdsInTheFinalErrorEnvelope() {
        UUID versionOne = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        try (RequestBodyReader reader = new RequestBodyReader(1)) {
            FailingExchange rawExchange = new FailingExchange(DeliveryFailure.NONE);
            BridgeExchange exchange = new BridgeExchange(rawExchange, 1_024, reader);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> exchange.sendInternalError(500, "safe failure", versionOne));
            assertEquals(-1, rawExchange.getResponseCode());
        }
    }

    @Test
    void rejectsDuplicatePaths() {
        BridgeEndpoint first = endpoint("first", "GET", "/v1/ping");
        BridgeEndpoint duplicate = endpoint("duplicate", "POST", "/v1/ping");

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
                        return "set_blocks";
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
                        exchange.ok(committedSetResult());
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
    void warnsWithoutPayloadsWhenACompletedCommandResponseCannotBeDelivered() {
        List<LogRecord> records = new ArrayList<>();
        DirtLog log = recordingLog(records);
        BridgeDispatcher dispatcher = commandDispatcher(log);
        FailingExchange exchange = new FailingExchange(DeliveryFailure.ALWAYS_IO);
        exchange.requestHeaders.add("X-Dirt-Call-Id", BridgeTestFixture.CALL_ID);

        try (log;
                dispatcher) {
            assertThrows(IOException.class, () -> dispatcher.handle(exchange));

            LogRecord audit = records.getFirst();
            assertEquals(Level.WARNING, audit.getLevel());
            assertTrue(audit.getMessage().contains("2 commands"));
            assertTrue(audit.getMessage().contains(BridgeTestFixture.CALL_ID));
            assertTrue(audit.getMessage().contains("inspect server state before retrying"));
            assertNoCommandPayload(audit.getMessage());
            LogContext context = (LogContext) audit.getParameters()[0];
            assertEquals(2L, context.values().get("result_count"));
            assertEquals("partial_failure", context.values().get("outcome"));
            assertEquals(true, context.values().get("aborted"));
        }
    }

    @Test
    void summarizesSuccessfulPluralUndoWithoutInventingAnEditId() throws IOException {
        List<LogRecord> records = new ArrayList<>();
        DirtLog log = recordingLog(records);
        BridgeDispatcher dispatcher = undoDispatcher(log);
        FailingExchange exchange = new FailingExchange(DeliveryFailure.NONE);

        try (log;
                dispatcher) {
            dispatcher.handle(exchange);

            LogRecord audit = records.getFirst();
            assertEquals(Level.INFO, audit.getLevel());
            assertTrue(audit.getMessage().contains("undid 2 edits"));
            assertTrue(audit.getMessage().contains("3 changed-block entries"));
            assertFalse(audit.getMessage().contains("null"));
            LogContext context = (LogContext) audit.getParameters()[0];
            assertEquals(2L, context.values().get("result_count"));
            assertEquals(3L, context.values().get("changed_block_count"));
            assertFalse(context.values().containsKey("edit_id"));
        }
    }

    @Test
    void warnsToReconcileHistoryWhenPluralUndoResponseDeliveryFails() {
        List<LogRecord> records = new ArrayList<>();
        DirtLog log = recordingLog(records);
        BridgeDispatcher dispatcher = undoDispatcher(log);
        FailingExchange exchange = new FailingExchange(DeliveryFailure.ALWAYS_IO);
        exchange.requestHeaders.add("X-Dirt-Call-Id", BridgeTestFixture.CALL_ID);

        try (log;
                dispatcher) {
            assertThrows(IOException.class, () -> dispatcher.handle(exchange));

            LogRecord audit = records.getFirst();
            assertEquals(Level.WARNING, audit.getLevel());
            assertTrue(audit.getMessage().contains("restored 2 edits"));
            assertTrue(audit.getMessage().contains("reconcile edit history"));
            assertTrue(audit.getMessage().contains(BridgeTestFixture.CALL_ID));
            assertFalse(audit.getMessage().contains("null"));
            LogContext context = (LogContext) audit.getParameters()[0];
            assertEquals("undone", context.values().get("outcome"));
            assertEquals(2L, context.values().get("result_count"));
            assertEquals(true, context.values().get("aborted"));
            assertFalse(context.values().containsKey("edit_id"));
        }
    }

    @Test
    void summarizesPartialUndoAsHistoryReconciliationRatherThanACommandBatch() throws IOException {
        UUID failedEditId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        List<LogRecord> records = new ArrayList<>();
        DirtLog log = recordingLog(records);
        BridgeEndpoint endpoint =
                new BridgeEndpoint() {
                    @Override
                    public String operation() {
                        return "undo_edits";
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
                    public void handle(BridgeExchange exchange) throws OperationException {
                        exchange.world("world");
                        throw new UndoEditsException(
                                OperationFailure.WORLD_UNAVAILABLE,
                                "Undo execution failed",
                                new ErrorDetails.WorldUnavailable.OperationFailed(),
                                new IllegalStateException("test failure"),
                                failedEditId,
                                List.of(committedSetResult().edit()));
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
        FailingExchange exchange = new FailingExchange(DeliveryFailure.NONE);

        try (log;
                dispatcher) {
            dispatcher.handle(exchange);

            assertEquals(503, exchange.getResponseCode());
            LogRecord audit = records.getFirst();
            assertEquals(Level.WARNING, audit.getLevel());
            assertTrue(audit.getMessage().contains("undo_edits"));
            assertTrue(audit.getMessage().contains("reconcile edit " + failedEditId));
            assertFalse(audit.getMessage().contains("Minecraft command"));
            LogContext context = (LogContext) audit.getParameters()[0];
            assertEquals("partial_failure", context.values().get("outcome"));
            assertEquals(1L, context.values().get("result_count"));
            assertEquals(failedEditId, context.values().get("edit_id"));
        }
    }

    @Test
    void keepsUnexpectedCommandResponseFinalizationFailuresSevere() throws IOException {
        List<LogRecord> records = new ArrayList<>();
        DirtLog log = recordingLog(records);
        BridgeDispatcher dispatcher = commandDispatcher(log);
        FailingExchange exchange = new FailingExchange(DeliveryFailure.FIRST_RUNTIME);
        exchange.requestHeaders.add("X-Dirt-Call-Id", BridgeTestFixture.CALL_ID);

        try (log;
                dispatcher) {
            dispatcher.handle(exchange);

            assertEquals(500, exchange.getResponseCode());
            LogRecord audit = records.getFirst();
            assertEquals(Level.SEVERE, audit.getLevel());
            assertTrue(audit.getMessage().contains("2 commands"));
            assertTrue(audit.getMessage().contains(BridgeTestFixture.CALL_ID));
            assertTrue(audit.getMessage().contains("inspect server state before retrying"));
            assertNoCommandPayload(audit.getMessage());
            LogContext context = (LogContext) audit.getParameters()[0];
            assertEquals(2L, context.values().get("result_count"));
            assertEquals("partial_failure", context.values().get("outcome"));
            assertEquals("internal_error", context.values().get("error_code"));
        }
    }

    @Test
    void treatsUnexpectedCommandExecutionFailuresAsAmbiguousWithoutLoggingPayloads()
            throws IOException {
        List<LogRecord> records = new ArrayList<>();
        DirtLog log = recordingLog(records);
        BridgeEndpoint endpoint =
                new BridgeEndpoint() {
                    @Override
                    public String operation() {
                        return "run_minecraft_commands";
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
                        throw new IllegalStateException("private-command-runtime-payload");
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
        FailingExchange exchange = new FailingExchange(DeliveryFailure.NONE);
        exchange.requestHeaders.add("X-Dirt-Call-Id", BridgeTestFixture.CALL_ID);

        try (log;
                dispatcher) {
            dispatcher.handle(exchange);

            assertEquals(500, exchange.getResponseCode());
            LogRecord audit = records.getFirst();
            assertEquals(Level.SEVERE, audit.getLevel());
            assertTrue(audit.getMessage().contains("commands may have taken effect"));
            assertTrue(audit.getMessage().contains(BridgeTestFixture.CALL_ID));
            assertTrue(audit.getMessage().contains("inspect server state before retrying"));
            assertFalse(audit.getMessage().contains("private-command-runtime-payload"));
            assertNull(audit.getThrown());
            LogContext context = (LogContext) audit.getParameters()[0];
            assertEquals("internal_error", context.values().get("error_code"));
            assertFalse(context.values().containsKey("result_count"));
            assertFalse(context.values().containsKey("outcome"));
        }
    }

    @Test
    void invalidSuccessEditMetadataFallsBackToAnUncorrelatedInternalError() throws IOException {
        BridgeEndpoint endpoint =
                new BridgeEndpoint() {
                    @Override
                    public String operation() {
                        return "set_blocks";
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
                        exchange.ok(
                                committedSetResult(
                                        UUID.fromString("123e4567-e89b-12d3-a456-426614174000")));
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
                        log());
        FailingExchange exchange = new FailingExchange(DeliveryFailure.NONE);

        try (dispatcher) {
            dispatcher.handle(exchange);
        }

        assertEquals(500, exchange.getResponseCode());
        assertEquals(
                BridgeTestFixture.json("{error:{code:'internal_error',message:'safe failure'}}"),
                BridgeTestFixture.json(exchange.responseBody()));
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

    private static BridgeDispatcher commandDispatcher(DirtLog log) {
        BridgeEndpoint endpoint =
                new BridgeEndpoint() {
                    @Override
                    public String operation() {
                        return "run_minecraft_commands";
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
                        exchange.ok(commandResult());
                    }

                    @Override
                    public String internalErrorMessage() {
                        return "safe failure";
                    }
                };
        return new BridgeDispatcher(
                List.of(endpoint),
                new BearerAuthenticator(BridgeTestFixture.TOKEN),
                1,
                1_024,
                1,
                log);
    }

    private static BridgeDispatcher undoDispatcher(DirtLog log) {
        BridgeEndpoint endpoint =
                new BridgeEndpoint() {
                    @Override
                    public String operation() {
                        return "undo_edits";
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
                        EditRecord first = committedSetResult().edit();
                        EditRecord second =
                                new EditRecord(
                                        UUID.fromString("44444444-4444-4444-8444-444444444444"),
                                        UUID.fromString("55555555-5555-4555-8555-555555555555"),
                                        EditOperation.SET_BLOCKS,
                                        "Second test edit",
                                        first.world(),
                                        first.worldId(),
                                        first.bounds(),
                                        2,
                                        first.completedAt(),
                                        EditStatus.COMMITTED);
                        exchange.world("world");
                        exchange.ok(
                                new UndoEdits.Result(
                                        "world",
                                        List.of(first, second),
                                        UUID.fromString(BridgeTestFixture.CALL_ID),
                                        Instant.parse("2026-08-20T00:01:00Z")));
                    }

                    @Override
                    public String internalErrorMessage() {
                        return "safe failure";
                    }
                };
        return new BridgeDispatcher(
                List.of(endpoint),
                new BearerAuthenticator(BridgeTestFixture.TOKEN),
                1,
                1_024,
                1,
                log);
    }

    private static void assertNoCommandPayload(String message) {
        assertFalse(message.contains("private-command"));
        assertFalse(message.contains("private-feedback"));
        assertFalse(message.contains("private-message"));
        assertFalse(message.contains("private-raw-message"));
    }

    private static RunMinecraftCommands.Result commandResult() {
        return new RunMinecraftCommands.Result(
                new RunMinecraftCommands.Sender("DirtMCP", true, false),
                false,
                List.of(
                        new RunMinecraftCommands.CommandResult(
                                "private-command-one",
                                RunMinecraftCommands.Outcome.DISPATCHED,
                                List.of("private-feedback"),
                                null,
                                null),
                        new RunMinecraftCommands.CommandResult(
                                "private-command-two",
                                RunMinecraftCommands.Outcome.DISPATCH_FAILED,
                                List.of(),
                                "private-message",
                                "private-raw-message")));
    }

    private static SetBlocks.Result committedSetResult() {
        return committedSetResult(EDIT_ID);
    }

    private static SetBlocks.Result committedSetResult(UUID editId) {
        BlockBounds bounds =
                new BlockBounds(new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0));
        EditRecord edit =
                new EditRecord(
                        editId,
                        UUID.fromString("22222222-2222-4222-8222-222222222222"),
                        EditOperation.SET_BLOCKS,
                        "Place test block",
                        "world",
                        UUID.fromString("33333333-3333-4333-8333-333333333333"),
                        bounds,
                        1,
                        Instant.parse("2026-08-20T00:00:00Z"),
                        EditStatus.COMMITTED);
        return new SetBlocks.Result(
                "world",
                bounds,
                List.of(List.of(new DestinationPaletteEntry("minecraft:stone", null))),
                0,
                EditOutcome.COMMITTED,
                1,
                1,
                0,
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
