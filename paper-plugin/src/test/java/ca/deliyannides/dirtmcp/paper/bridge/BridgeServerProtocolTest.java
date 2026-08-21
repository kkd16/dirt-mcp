package ca.deliyannides.dirtmcp.paper.bridge;

import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.authorized;
import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.availablePort;
import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.config;
import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.json;
import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.server;
import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.uri;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.status.PingServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.helpers.NOPLogger;

final class BridgeServerProtocolTest {
    @Test
    void servesJsonOnLoopbackWithRequiredResponseHeaders() throws Exception {
        try (BridgeServer bridge =
                        server(config(availablePort(), 4), new BridgeTestFixture.TestOperations());
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> response =
                    client.send(
                            authorized(bridge, "/v1/ping").GET().build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(json("{\"status\":\"ok\"}"), json(response.body()));
            assertEquals(
                    "application/json; charset=utf-8",
                    response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        }
    }

    @Test
    void authenticatesBeforeDisclosingRoutesOrMethods() throws Exception {
        try (BridgeServer bridge =
                        server(config(availablePort(), 4), new BridgeTestFixture.TestOperations());
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            for (String path : List.of("/v1", "/v1/not-real", "/v1/ping?probe=true")) {
                HttpResponse<String> response =
                        client.send(
                                HttpRequest.newBuilder(uri(bridge, path))
                                        .POST(HttpRequest.BodyPublishers.noBody())
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(401, response.statusCode());
                assertEquals(
                        json(
                                "{\"error\":{\"code\":\"unauthorized\",\"message\":\"A valid bearer token is required\","
                                        + "\"details\":{\"reason\":\"authentication_failed\"}}}"),
                        json(response.body()));
                assertEquals(
                        "Bearer realm=\"dirt-mcp\"",
                        response.headers().firstValue("WWW-Authenticate").orElseThrow());
            }
        }
    }

    @Test
    void usesExactPathsAndReportsUnknownRoutesAndMethodsAsJson() throws Exception {
        try (BridgeServer bridge =
                        server(config(availablePort(), 4), new BridgeTestFixture.TestOperations());
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            for (String path :
                    List.of("/v1", "/v1/not-real", "/v1/ping/extra", "/v1/ping?probe=true")) {
                HttpResponse<String> response =
                        client.send(
                                authorized(bridge, path).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(404, response.statusCode());
                assertEquals(
                        "not_found",
                        json(response.body())
                                .getAsJsonObject()
                                .getAsJsonObject("error")
                                .get("code")
                                .getAsString());
            }

            for (String path : List.of("/v10", "/v1evil")) {
                HttpResponse<String> response =
                        client.send(
                                HttpRequest.newBuilder(uri(bridge, path)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(404, response.statusCode());
            }

            HttpResponse<String> wrongMethod =
                    client.send(
                            authorized(bridge, "/v1/ping")
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElseThrow());
            assertEquals(
                    "method_not_allowed",
                    json(wrongMethod.body())
                            .getAsJsonObject()
                            .getAsJsonObject("error")
                            .get("code")
                            .getAsString());
        }
    }

    @ParameterizedTest
    @MethodSource("operationFailures")
    void mapsTypedOperationFailures(
            OperationFailure failure, int expectedStatus, String expectedCode, ErrorDetails details)
            throws Exception {
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public PingServer.Result ping() throws OperationException {
                        throw new OperationException(failure, "safe message", details);
                    }
                };
        try (BridgeServer bridge = server(config(availablePort(), 4), operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            HttpResponse<String> response =
                    client.send(
                            authorized(bridge, "/v1/ping").GET().build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(expectedStatus, response.statusCode());
            var error = json(response.body()).getAsJsonObject().getAsJsonObject("error");
            assertEquals(expectedCode, error.get("code").getAsString());
            assertEquals("safe message", error.get("message").getAsString());
            assertEquals(ErrorDetailsJson.serialize(details), error.getAsJsonObject("details"));
        }
    }

    @Test
    void includesEditIdInTypedOperationFailureResponses() throws Exception {
        UUID editId = BridgeTestFixture.EDIT_ID;
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        CountDownLatch audited = new CountDownLatch(1);
        DirtLog log = recordingLog(records, audited);
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public PingServer.Result ping() throws OperationException {
                        throw new OperationException(
                                OperationFailure.INTERNAL_ERROR, "edit failed", null, null, editId);
                    }
                };
        try (log;
                BridgeServer bridge = server(config(availablePort(), 4), operations, log);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> response =
                    client.send(
                            authorized(bridge, "/v1/ping").GET().build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(500, response.statusCode());
            assertEquals(
                    json(
                            "{\"error\":{\"code\":\"internal_error\",\"message\":\"edit failed\","
                                    + "\"editId\":\""
                                    + editId
                                    + "\"}}"),
                    json(response.body()));
            assertTrue(audited.await(2, TimeUnit.SECONDS));
            LogRecord audit = requestRecords(records).getFirst();
            assertEquals(Level.WARNING, audit.getLevel());
            assertEquals(editId, context(audit).values().get("edit_id"));
        }
    }

    @Test
    void elevatesTypedInternalFailuresWithoutEditIds() throws Exception {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        CountDownLatch audited = new CountDownLatch(1);
        DirtLog log = recordingLog(records, audited);
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public PingServer.Result ping() throws OperationException {
                        throw new OperationException(
                                OperationFailure.INTERNAL_ERROR, "internal failure", null);
                    }
                };

        try (log;
                BridgeServer bridge = server(config(availablePort(), 4), operations, log);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            HttpResponse<String> response =
                    client.send(
                            authorized(bridge, "/v1/ping").GET().build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(500, response.statusCode());
            assertTrue(audited.await(2, TimeUnit.SECONDS));
            LogRecord audit = requestRecords(records).getFirst();
            assertEquals(Level.SEVERE, audit.getLevel());
            assertEquals("internal_error", context(audit).values().get("error_code"));
        }
    }

    @Test
    void keepsExpectedAvailabilityFailuresDetailOnly() throws Exception {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        CountDownLatch audited = new CountDownLatch(1);
        DirtLog log = recordingLog(records, audited);
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public PingServer.Result ping() throws OperationException {
                        throw new OperationException(
                                OperationFailure.SERVER_UNAVAILABLE,
                                "temporarily unavailable",
                                new ErrorDetails.ServerUnavailable.DependencyUnavailable());
                    }
                };

        try (log;
                BridgeServer bridge = server(config(availablePort(), 4), operations, log);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            HttpResponse<String> response =
                    client.send(
                            authorized(bridge, "/v1/ping").GET().build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
            assertTrue(audited.await(2, TimeUnit.SECONDS));
            LogRecord audit = requestRecords(records).getFirst();
            assertEquals(Level.FINE, audit.getLevel());
            assertEquals("temporarily unavailable", context(audit).values().get("failure_reason"));
        }
    }

    @Test
    void neverRecordsBearerCredentials() throws Exception {
        String suppliedCredential = "credential-that-must-never-appear-in-logs";
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        CountDownLatch audited = new CountDownLatch(1);
        DirtLog log = recordingLog(records, audited);

        try (log;
                BridgeServer bridge =
                        server(
                                config(availablePort(), 4),
                                new BridgeTestFixture.TestOperations(),
                                log);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            HttpResponse<String> response =
                    client.send(
                            HttpRequest.newBuilder(uri(bridge, "/v1/ping"))
                                    .header("Authorization", "Bearer " + suppliedCredential)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
            assertTrue(audited.await(2, TimeUnit.SECONDS));
            LogRecord audit = requestRecords(records).getFirst();
            assertFalse(audit.getMessage().contains(suppliedCredential));
            assertFalse(context(audit).values().toString().contains(suppliedCredential));
            assertEquals("unauthorized", context(audit).values().get("error_code"));
        }
    }

    @Test
    void rejectsExcessConcurrentWorkWithoutQueueing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public PingServer.Result ping() {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        return new PingServer.Result("ok");
                    }
                };
        try (BridgeServer bridge = server(config(availablePort(), 1), operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            CompletableFuture<HttpResponse<String>> first =
                    client.sendAsync(
                            authorized(bridge, "/v1/ping").GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            HttpResponse<String> busy =
                    client.send(
                            authorized(bridge, "/v1/ping").GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(503, busy.statusCode());
            assertEquals(
                    "bridge_busy",
                    json(busy.body())
                            .getAsJsonObject()
                            .getAsJsonObject("error")
                            .get("code")
                            .getAsString());
            release.countDown();
            assertEquals(200, first.get(2, TimeUnit.SECONDS).statusCode());
        } finally {
            release.countDown();
        }
    }

    @Test
    void rollsBackFailedStartsAndClosesIdempotently() throws Exception {
        int port = availablePort();
        BridgeServer bridge = server(config(port, 4), new BridgeTestFixture.TestOperations());
        assertThrows(IllegalStateException.class, bridge::boundPort);

        try (ServerSocket occupied =
                new ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"))) {
            assertFalse(occupied.isClosed());
            assertThrows(IOException.class, bridge::start);
        }

        bridge.start();
        assertThrows(IllegalStateException.class, bridge::start);
        bridge.close();
        bridge.close();
        assertThrows(IllegalStateException.class, bridge::boundPort);
        assertThrows(IllegalStateException.class, bridge::start);
    }

    @Test
    void rejectsDeclaredOversizeAndMalformedUtf8Bodies() throws Exception {
        try (BridgeServer bridge =
                server(config(availablePort(), 4), new BridgeTestFixture.TestOperations())) {
            bridge.start();

            String oversized = rawRequest(bridge, 262_145, "{}".getBytes(StandardCharsets.UTF_8));
            String malformed = rawRequest(bridge, 2, new byte[] {(byte) 0xc3, 0x28});

            assertTrue(oversized.startsWith("HTTP/1.1 400"));
            assertTrue(oversized.contains("invalid_request"));
            assertTrue(malformed.startsWith("HTTP/1.1 400"));
            assertTrue(malformed.contains("valid JSON values"));
        }
    }

    @Test
    void timesOutIncompleteAuthenticatedRequestBodies() throws Exception {
        try (BridgeServer bridge =
                        server(config(availablePort(), 4), new BridgeTestFixture.TestOperations());
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            String response = rawRequest(bridge, 100, "{".getBytes(StandardCharsets.UTF_8));

            assertEquals("", response);
            assertEquals(
                    200,
                    client.send(
                                    authorized(bridge, "/v1/ping").GET().build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
        }
    }

    @Test
    void sanitizesUnexpectedFailuresAndWritesOneAuditRecord() throws Exception {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        CountDownLatch audited = new CountDownLatch(1);
        DirtLog log = recordingLog(records, audited);
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public PingServer.Result ping() {
                        throw new IllegalStateException("secret detail");
                    }
                };
        try (log;
                BridgeServer bridge = server(config(availablePort(), 4), operations, log);
                HttpClient client =
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            bridge.start();
            HttpResponse<String> response =
                    client.send(
                            authorized(bridge, "/v1/ping").GET().build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(500, response.statusCode());
            assertEquals(
                    "internal_error",
                    json(response.body())
                            .getAsJsonObject()
                            .getAsJsonObject("error")
                            .get("code")
                            .getAsString());
            assertTrue(response.body().contains("end-to-end health check failed"));
            assertFalse(response.body().contains("secret detail"));
            assertTrue(audited.await(2, TimeUnit.SECONDS));
            List<LogRecord> audits = requestRecords(records);
            assertEquals(1, audits.size());
            LogRecord audit = audits.getFirst();
            assertEquals(Level.SEVERE, audit.getLevel());
            assertEquals("secret detail", audit.getThrown().getMessage());
            assertEquals("ping_server", context(audit).values().get("operation"));
            assertEquals(500, context(audit).values().get("http_status"));
        }
    }

    @Test
    void keepsAuditMetadataRequestLocalAndNeverLogsRequestBodies() throws Exception {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        CountDownLatch audited = new CountDownLatch(2);
        DirtLog log = recordingLog(records, audited);
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public RunMinecraftCommands.Result runCommands(
                            RunMinecraftCommands.Request request) {
                        return new RunMinecraftCommands.Result(
                                new RunMinecraftCommands.Sender("DirtMCP", true, false),
                                false,
                                List.of(
                                        new RunMinecraftCommands.CommandResult(
                                                request.commands().get(0),
                                                RunMinecraftCommands.Outcome.DISPATCHED,
                                                List.of("private-feedback-payload"),
                                                null,
                                                null),
                                        new RunMinecraftCommands.CommandResult(
                                                request.commands().get(1),
                                                RunMinecraftCommands.Outcome.DISPATCH_FAILED,
                                                List.of(),
                                                "private-message-payload",
                                                "private-raw-message-payload")));
                    }
                };
        try (log;
                BridgeServer bridge = server(config(availablePort(), 4), operations, log);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            HttpResponse<String> edit =
                    client.send(
                            authorized(bridge, "/v1/set-blocks")
                                    .header("Content-Type", "application/json")
                                    .header(
                                            "X-Dirt-Call-Id",
                                            "123e4567-e89b-42d3-a456-426614174000")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    """
                                                    {"world":"audit-world",
                                                     "origin":{"x":1,"y":2,"z":3},
                                                     "palettes":[[{"blockState":
                                                     "minecraft:secret_gold_block"}]],
                                                     "placements":[[0,0,0,0]]}
                                                    """))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, edit.statusCode());
            HttpResponse<String> commands =
                    client.send(
                            authorized(bridge, "/v1/run-minecraft-commands")
                                    .header("Content-Type", "application/json")
                                    .header(
                                            "X-Dirt-Call-Id",
                                            "223e4567-e89b-42d3-a456-426614174000")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "{\"commands\":[\"say private-command-payload\","
                                                            + "\"missing private-command-payload\","
                                                            + "\"must-not-run private-command-payload\"]}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, commands.statusCode());
            assertTrue(audited.await(2, TimeUnit.SECONDS));

            List<LogRecord> calls = requestRecords(records);
            assertEquals(2, calls.size());
            LogRecord editAudit =
                    calls.stream()
                            .filter(
                                    record ->
                                            "set_blocks"
                                                    .equals(
                                                            context(record)
                                                                    .values()
                                                                    .get("operation")))
                            .findFirst()
                            .orElseThrow();
            assertEquals(Level.INFO, editAudit.getLevel());
            LogContext editContext = context(editAudit);
            assertEquals("audit-world", editContext.values().get("world"));
            assertEquals(
                    "123e4567-e89b-42d3-a456-426614174000", editContext.values().get("call_id"));
            assertEquals("committed", editContext.values().get("outcome"));
            assertEquals(1L, editContext.values().get("changed_block_count"));

            LogRecord commandAudit =
                    calls.stream()
                            .filter(
                                    record ->
                                            "run_minecraft_commands"
                                                    .equals(
                                                            context(record)
                                                                    .values()
                                                                    .get("operation")))
                            .findFirst()
                            .orElseThrow();
            assertEquals(Level.WARNING, commandAudit.getLevel());
            assertTrue(commandAudit.getMessage().contains("2 attempted; stopped at first failure"));
            assertEquals("partial_failure", context(commandAudit).values().get("outcome"));
            assertEquals(2L, context(commandAudit).values().get("result_count"));
            assertEquals(
                    "223e4567-e89b-42d3-a456-426614174000",
                    context(commandAudit).values().get("call_id"));

            String auditText =
                    calls.stream()
                            .map(record -> record.getMessage() + context(record).values())
                            .reduce("", String::concat);
            assertFalse(auditText.contains("secret_gold_block"));
            assertFalse(auditText.contains("private-command-payload"));
            assertFalse(auditText.contains("private-feedback-payload"));
            assertFalse(auditText.contains("private-message-payload"));
            assertFalse(auditText.contains("private-raw-message-payload"));
        }
    }

    private static DirtLog recordingLog(List<LogRecord> records, CountDownLatch audited) {
        Handler handler =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        records.add(record);
                        if (audited != null
                                && "bridge.request_completed".equals(record.getLoggerName())) {
                            audited.countDown();
                        }
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        return DirtLog.withDetailHandler(
                NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR, handler);
    }

    private static List<LogRecord> requestRecords(List<LogRecord> records) {
        return records.stream()
                .filter(record -> "bridge.request_completed".equals(record.getLoggerName()))
                .toList();
    }

    private static LogContext context(LogRecord record) {
        return (LogContext) record.getParameters()[0];
    }

    private static Stream<Arguments> operationFailures() {
        UUID requestedEditId = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        UUID newestEditId = UUID.fromString("123e4567-e89b-42d3-a456-426614174001");
        return Stream.of(
                Arguments.of(
                        OperationFailure.INVALID_REQUEST,
                        400,
                        "invalid_request",
                        new ErrorDetails.InvalidRequest.InvalidValue("field")),
                Arguments.of(
                        OperationFailure.EDIT_NOT_FOUND,
                        404,
                        "edit_not_found",
                        new ErrorDetails.EditNotFound("world", requestedEditId)),
                Arguments.of(
                        OperationFailure.WORLD_NOT_FOUND,
                        404,
                        "world_not_found",
                        new ErrorDetails.WorldNotFound("world")),
                Arguments.of(
                        OperationFailure.PLAYER_NOT_FOUND,
                        404,
                        "player_not_found",
                        new ErrorDetails.PlayerNotFound("Builder")),
                Arguments.of(
                        OperationFailure.EDIT_NOT_LATEST,
                        409,
                        "edit_not_latest",
                        new ErrorDetails.EditNotLatest("world", requestedEditId, newestEditId)),
                Arguments.of(
                        OperationFailure.WORLD_BUSY,
                        409,
                        "world_busy",
                        new ErrorDetails.WorldBusy.OperationInProgress("world")),
                Arguments.of(
                        OperationFailure.PLAYER_UNAVAILABLE,
                        409,
                        "player_unavailable",
                        new ErrorDetails.PlayerUnavailable.SpectatingEntity("Builder")),
                Arguments.of(
                        OperationFailure.CHANGE_LIMIT_EXCEEDED,
                        413,
                        "change_limit_exceeded",
                        new ErrorDetails.ChangeLimitExceeded(1)),
                Arguments.of(
                        OperationFailure.REGION_TOO_LARGE,
                        413,
                        "region_too_large",
                        new ErrorDetails.RegionTooLarge.BlockCount(2, 1)),
                Arguments.of(
                        OperationFailure.RESULT_TOO_LARGE,
                        413,
                        "result_too_large",
                        new ErrorDetails.ResultTooLarge.Blocks(2, 1)),
                Arguments.of(
                        OperationFailure.HISTORY_CAPACITY_EXCEEDED,
                        503,
                        "history_capacity_exceeded",
                        new ErrorDetails.HistoryCapacityExceeded.EntriesTotal(1)),
                Arguments.of(
                        OperationFailure.SERVER_UNAVAILABLE,
                        503,
                        "server_unavailable",
                        new ErrorDetails.ServerUnavailable.PaperUnavailable()),
                Arguments.of(
                        OperationFailure.UNHEALTHY,
                        503,
                        "unhealthy",
                        new ErrorDetails.Unhealthy.HealthCheckFailed()),
                Arguments.of(
                        OperationFailure.WORLD_UNAVAILABLE,
                        503,
                        "world_unavailable",
                        new ErrorDetails.WorldUnavailable.PaperUnavailable()));
    }

    private static String rawRequest(BridgeServer bridge, int contentLength, byte[] partialBody)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", bridge.boundPort())) {
            socket.setSoTimeout(4_000);
            String headers =
                    "POST /v1/count-region-block-states HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Authorization: Bearer "
                            + BridgeTestFixture.TOKEN
                            + "\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: "
                            + contentLength
                            + "\r\n"
                            + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(partialBody);
            socket.getOutputStream().flush();
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int next = socket.getInputStream().read();
                if (next < 0) {
                    return "";
                }
                headerBytes.write(next);
                matched =
                        switch (matched) {
                            case 0 -> next == '\r' ? 1 : 0;
                            case 1 -> next == '\n' ? 2 : 0;
                            case 2 -> next == '\r' ? 3 : 0;
                            case 3 -> next == '\n' ? 4 : 0;
                            default -> matched;
                        };
            }
            String responseHeaders = headerBytes.toString(StandardCharsets.US_ASCII);
            int responseLength =
                    responseHeaders
                            .lines()
                            .filter(line -> line.regionMatches(true, 0, "Content-Length:", 0, 15))
                            .map(line -> line.substring(line.indexOf(':') + 1).trim())
                            .mapToInt(Integer::parseInt)
                            .findFirst()
                            .orElseThrow(
                                    () -> new IOException("HTTP response has no Content-Length"));
            byte[] responseBody = socket.getInputStream().readNBytes(responseLength);
            return responseHeaders + new String(responseBody, StandardCharsets.UTF_8);
        }
    }
}
