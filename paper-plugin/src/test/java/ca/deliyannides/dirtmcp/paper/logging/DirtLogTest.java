package ca.deliyannides.dirtmcp.paper.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

final class DirtLogTest {
    @Test
    void routesDetailEventsAndOnlyThresholdedSummariesToConsole() {
        List<ConsoleCall> consoleCalls = new ArrayList<>();
        List<LogRecord> detailRecords = new ArrayList<>();
        DirtLog log =
                DirtLog.withDetailHandler(
                        console(consoleCalls),
                        DirtConfig.ConsoleLogLevel.WARNING,
                        handler(detailRecords));
        LogContext empty = LogContext.empty();

        log.debug("bridge", "bridge.request_completed", "detail", empty);
        log.info("runtime", "runtime.started", "ready", empty);
        log.warning("edit", "edit.recovery_required", "recover", empty);
        IllegalStateException failure = new IllegalStateException("detail only");
        log.error("bridge", "bridge.request_failed", "failed", empty, failure);
        log.close();

        assertEquals(
                List.of("warn", "error"), consoleCalls.stream().map(ConsoleCall::method).toList());
        assertEquals(
                List.of(Level.FINE, Level.INFO, Level.WARNING, Level.SEVERE),
                detailRecords.stream().map(LogRecord::getLevel).toList());
        assertEquals(Thread.currentThread().threadId(), detailRecords.getFirst().getLongThreadID());
        assertEquals(
                Thread.currentThread().getName(), detailRecords.getFirst().getSourceMethodName());
        assertEquals("detail only", detailRecords.getLast().getThrown().getMessage());
        assertEquals(1, consoleCalls.getLast().arguments().length);
    }

    @Test
    void formatsOneBoundedJsonLineWithStableFieldsAndExceptionDetail() {
        String oversized = "x".repeat(80_000);
        LogRecord record = new LogRecord(Level.SEVERE, oversized);
        record.setInstant(Instant.parse("2026-08-20T12:34:56.789Z"));
        record.setLoggerName("bridge.request_completed");
        record.setSourceClassName("bridge");
        record.setSourceMethodName("test-thread");
        record.setLongThreadID(42);
        record.setParameters(
                new Object[] {
                    LogContext.of(
                                    "call_id",
                                    UUID.fromString("123e4567-e89b-42d3-a456-426614174000"))
                            .with("http_status", 500)
                            .with("aborted", false)
                });
        record.setThrown(new IllegalStateException(oversized));

        String line = new DirtLog.JsonLineFormatter(1234).format(record);
        JsonObject json = JsonParser.parseString(line).getAsJsonObject();

        assertEquals(1, line.lines().count());
        assertEquals("2026-08-20T12:34:56.789Z", json.get("timestamp").getAsString());
        assertEquals("error", json.get("level").getAsString());
        assertEquals("dirt-mcp-paper", json.get("service").getAsString());
        assertEquals("bridge", json.get("component").getAsString());
        assertEquals("bridge.request_completed", json.get("event").getAsString());
        assertEquals(1234, json.get("pid").getAsLong());
        assertEquals("test-thread", json.get("thread").getAsString());
        assertEquals(42, json.get("thread_id").getAsLong());
        assertEquals(500, json.get("http_status").getAsInt());
        assertEquals(4_096, json.get("message").getAsString().length());
        assertTrue(json.get("message").getAsString().endsWith("...[truncated]"));
        assertEquals(
                4_096, json.getAsJsonObject("exception").get("message").getAsString().length());
        assertTrue(
                json.getAsJsonObject("exception")
                        .get("message")
                        .getAsString()
                        .endsWith("...[truncated]"));
        assertTrue(
                json.getAsJsonObject("exception")
                        .get("stack")
                        .getAsString()
                        .endsWith("...[truncated]"));
    }

    @Test
    void writesAndClosesTheConfiguredRotatingDetailFile(@TempDir Path temporaryDirectory)
            throws Exception {
        Path pattern = temporaryDirectory.resolve("dirt-detail.%g.jsonl");
        DirtLog log = DirtLog.open(NOPLogger.NOP_LOGGER, pattern, logging());

        assertTrue(log.hasDetailFile());
        LogContext context = LogContext.of("http_status", 200);
        log.debug("bridge", "bridge.request_completed", "request complete", context);
        log.close();

        Path detailFile = temporaryDirectory.resolve("dirt-detail.0.jsonl");
        assertTrue(Files.isRegularFile(detailFile));
        JsonObject event = JsonParser.parseString(Files.readString(detailFile)).getAsJsonObject();
        assertEquals("debug", event.get("level").getAsString());
        assertEquals(200, event.get("http_status").getAsInt());
    }

    @Test
    void rotatesAtTheConfiguredLimitAndRetainsOnlyTheConfiguredGenerations(
            @TempDir Path temporaryDirectory) throws Exception {
        Path pattern = temporaryDirectory.resolve("dirt-detail.%g.jsonl");
        DirtConfig.Logging config = new DirtConfig.Logging(DirtConfig.ConsoleLogLevel.INFO, 512, 2);
        DirtLog log = DirtLog.open(NOPLogger.NOP_LOGGER, pattern, config);
        String message = "x".repeat(400);

        for (int sequence = 0; sequence < 10; sequence++) {
            LogContext context = LogContext.of("sequence", sequence);
            log.debug("bridge", "bridge.request_completed", message, context);
        }
        log.close();

        List<String> fileNames;
        try (var paths = Files.list(temporaryDirectory)) {
            fileNames =
                    paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                            .map(path -> path.getFileName().toString())
                            .sorted()
                            .toList();
        }
        assertEquals(List.of("dirt-detail.0.jsonl", "dirt-detail.1.jsonl"), fileNames);
        assertTrue(Files.size(temporaryDirectory.resolve("dirt-detail.1.jsonl")) > 512);
    }

    @Test
    void discardsAnIncompleteCrashTailBeforeAppending(@TempDir Path temporaryDirectory)
            throws Exception {
        Path active = temporaryDirectory.resolve("dirt-detail.0.jsonl");
        Files.writeString(active, "{\"timestamp\":\"complete\"}\n{\"partial\":");
        Path pattern = temporaryDirectory.resolve("dirt-detail.%g.jsonl");
        DirtLog log = DirtLog.open(NOPLogger.NOP_LOGGER, pattern, logging());

        LogContext context = LogContext.empty();
        log.info("runtime", "runtime.started", "ready", context);
        log.close();

        List<String> lines = Files.readAllLines(active);
        assertEquals(2, lines.size());
        assertEquals(
                "complete",
                JsonParser.parseString(lines.getFirst())
                        .getAsJsonObject()
                        .get("timestamp")
                        .getAsString());
        assertEquals(
                "runtime.started",
                JsonParser.parseString(lines.getLast())
                        .getAsJsonObject()
                        .get("event")
                        .getAsString());
    }

    @Test
    void failsOpenOnceWhenTheDetailPathCannotBeCreated(@TempDir Path temporaryDirectory)
            throws Exception {
        Path fileInsteadOfDirectory = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(fileInsteadOfDirectory, "occupied");
        List<ConsoleCall> consoleCalls = new ArrayList<>();

        DirtLog log =
                DirtLog.open(
                        console(consoleCalls),
                        fileInsteadOfDirectory.resolve("detail.%g.jsonl"),
                        logging());

        assertFalse(log.hasDetailFile());
        assertEquals(1, consoleCalls.size());
        assertEquals("error", consoleCalls.getFirst().method());
        assertEquals(2, consoleCalls.getFirst().arguments().length);
        IllegalStateException applicationFailure =
                new IllegalStateException("request detail must stay out of the console");
        LogContext context = LogContext.empty();
        log.error("bridge", "bridge.request_failed", "Request failed", context, applicationFailure);
        assertEquals(2, consoleCalls.size());
        assertEquals(1, consoleCalls.getLast().arguments().length);
        assertFalse(consoleCalls.getLast().arguments()[0].toString().contains("request detail"));
        log.close();
    }

    @Test
    void rejectsSensitiveKeysAndBoundsAllFreeFormText() {
        for (String key :
                List.of(
                        "authorization",
                        "access_token",
                        "api_key",
                        "bearer_value",
                        "password_hash",
                        "client_secret",
                        "credential")) {
            assertThrows(IllegalArgumentException.class, () -> LogContext.of(key, "leak"));
        }
        for (String key : List.of("timestamp", "service", "thread_id", "exception")) {
            assertThrows(IllegalArgumentException.class, () -> LogContext.of(key, "replacement"));
        }

        LogContext context = LogContext.of("world", "w".repeat(10_000));
        String value = (String) context.values().get("world");
        assertEquals(2_048, value.length());
        assertTrue(value.endsWith("...[truncated]"));
        assertFalse(context.values().toString().contains("Bearer"));
    }

    private static DirtConfig.Logging logging() {
        return new DirtConfig.Logging(DirtConfig.ConsoleLogLevel.INFO, 1_048_576, 2);
    }

    private static Handler handler(List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    private static Logger console(List<ConsoleCall> calls) {
        return (Logger)
                Proxy.newProxyInstance(
                        DirtLogTest.class.getClassLoader(),
                        new Class<?>[] {Logger.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("getName")) {
                                return "test";
                            }
                            if (method.getName().equals("info")
                                    || method.getName().equals("warn")
                                    || method.getName().equals("error")) {
                                calls.add(
                                        new ConsoleCall(
                                                method.getName(),
                                                arguments == null ? new Object[0] : arguments));
                                return null;
                            }
                            if (method.getReturnType() == boolean.class) {
                                return true;
                            }
                            return null;
                        });
    }

    private record ConsoleCall(String method, Object[] arguments) {}
}
