package ca.deliyannides.dirtmcp.paper.logging;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ErrorManager;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

/** Routes concise operator messages to Paper and detailed structured events to a bounded file. */
public final class DirtLog implements AutoCloseable {
    public static final String DETAIL_FILE_PATTERN = "logs/dirt-detail.%g.jsonl";

    private static final String SERVICE = "dirt-mcp-paper";
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_MESSAGE_LENGTH = 4_096;
    private static final int MAX_STACK_LENGTH = 65_536;
    private static final int MAX_THREAD_NAME_LENGTH = 256;
    private static final int MAX_INCOMPLETE_TAIL_SCAN_BYTES = 262_144;
    private static final String TRUNCATION_SUFFIX = "...[truncated]";
    private static final Pattern COMPONENT = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final Pattern EVENT = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");

    private final Logger console;
    private final DirtConfig.ConsoleLogLevel consoleLevel;
    private final Handler detail;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DirtLog(Logger console, DirtConfig.ConsoleLogLevel consoleLevel, Handler detail) {
        this.console = console;
        this.consoleLevel = consoleLevel;
        this.detail = detail;
    }

    public static DirtLog open(JavaPlugin plugin, DirtConfig.Logging config) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(config, "config");
        Logger console = plugin.getSLF4JLogger();
        Path pattern = plugin.getDataPath().resolve(DETAIL_FILE_PATTERN);
        return open(console, pattern, config);
    }

    static DirtLog open(Logger console, Path pattern, DirtConfig.Logging config) {
        Objects.requireNonNull(console, "console");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(config, "config");
        Handler detail = openDetailHandler(console, pattern, config);
        return new DirtLog(console, config.consoleLevel(), detail);
    }

    public static DirtLog consoleOnly(Logger console, DirtConfig.ConsoleLogLevel consoleLevel) {
        return new DirtLog(
                Objects.requireNonNull(console, "console"),
                Objects.requireNonNull(consoleLevel, "consoleLevel"),
                null);
    }

    public static DirtLog withDetailHandler(
            Logger console, DirtConfig.ConsoleLogLevel consoleLevel, Handler detail) {
        return new DirtLog(
                Objects.requireNonNull(console, "console"),
                Objects.requireNonNull(consoleLevel, "consoleLevel"),
                Objects.requireNonNull(detail, "detail"));
    }

    public boolean hasDetailFile() {
        return this.detail != null;
    }

    public void debug(String component, String event, String message, LogContext context) {
        write(Severity.DEBUG, component, event, message, context, null);
    }

    public void debug(
            String component, String event, String message, LogContext context, Throwable failure) {
        write(Severity.DEBUG, component, event, message, context, failure);
    }

    public void info(String component, String event, String message, LogContext context) {
        write(Severity.INFO, component, event, message, context, null);
    }

    public void warning(String component, String event, String message, LogContext context) {
        write(Severity.WARNING, component, event, message, context, null);
    }

    public void warning(
            String component, String event, String message, LogContext context, Throwable failure) {
        write(Severity.WARNING, component, event, message, context, failure);
    }

    public void error(
            String component, String event, String message, LogContext context, Throwable failure) {
        write(Severity.ERROR, component, event, message, context, failure);
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true) && this.detail != null) {
            this.detail.close();
        }
    }

    private void write(
            Severity severity,
            String component,
            String event,
            String message,
            LogContext context,
            Throwable failure) {
        if (this.closed.get()) {
            return;
        }
        requireIdentifier(component, "component");
        requireEvent(event);
        String checkedMessage =
                bounded(Objects.requireNonNull(message, "message"), MAX_MESSAGE_LENGTH);
        LogContext checkedContext = Objects.requireNonNull(context, "context");

        if (this.detail != null) {
            LogRecord record = new LogRecord(severity.javaLevel, checkedMessage);
            record.setLongThreadID(Thread.currentThread().threadId());
            record.setLoggerName(event);
            record.setSourceClassName(component);
            String threadName = Thread.currentThread().getName();
            record.setSourceMethodName(
                    threadName.isEmpty() ? "unnamed" : bounded(threadName, MAX_THREAD_NAME_LENGTH));
            record.setParameters(new Object[] {checkedContext});
            record.setThrown(failure);
            this.detail.publish(record);
        }

        if (severity != Severity.DEBUG && this.consoleLevel.allows(severity.consoleLevel)) {
            switch (severity) {
                case DEBUG -> throw new AssertionError("Debug is file-only");
                case INFO -> this.console.info(checkedMessage);
                case WARNING -> this.console.warn(checkedMessage);
                case ERROR -> this.console.error(checkedMessage);
            }
        }
    }

    private static Handler openDetailHandler(
            Logger console, Path pattern, DirtConfig.Logging config) {
        FileHandler handler = null;
        try {
            Files.createDirectories(pattern.getParent());
            discardIncompleteActiveRecord(pattern);
            handler =
                    new FileHandler(
                            pattern.toString(),
                            config.detailFileMaxBytes(),
                            config.detailFileRetainedFiles(),
                            true);
            handler.setEncoding(StandardCharsets.UTF_8.name());
            handler.setLevel(Level.ALL);
            handler.setFormatter(new JsonLineFormatter(ProcessHandle.current().pid()));
            handler.setErrorManager(new DetailErrorManager(console, pattern));
            return handler;
        } catch (IOException | RuntimeException failure) {
            if (handler != null) {
                try {
                    handler.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            String message =
                    "Dirt MCP detailed logging is unavailable at " + pattern + "; continuing";
            console.error(message, failure);
            return null;
        }
    }

    private static void discardIncompleteActiveRecord(Path pattern) throws IOException {
        String fileName = pattern.getFileName().toString();
        if (!fileName.contains("%g")) {
            return;
        }
        Path active = pattern.resolveSibling(fileName.replace("%g", "0"));
        if (!Files.isRegularFile(active)) {
            return;
        }
        try (FileChannel channel =
                FileChannel.open(active, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long cursor = channel.size();
            long minimumCursor = Math.max(0, cursor - MAX_INCOMPLETE_TAIL_SCAN_BYTES);
            ByteBuffer chunk = ByteBuffer.allocate(8_192);
            while (cursor > minimumCursor) {
                int length = (int) Math.min(chunk.capacity(), cursor - minimumCursor);
                cursor -= length;
                chunk.clear().limit(length);
                channel.position(cursor);
                while (chunk.hasRemaining() && channel.read(chunk) >= 0) {
                    // Regular files normally fill in one read; continue for short reads.
                }
                chunk.flip();
                for (int index = chunk.limit() - 1; index >= 0; index--) {
                    if (chunk.get(index) == '\n') {
                        channel.truncate(cursor + index + 1);
                        return;
                    }
                }
            }
            channel.truncate(0);
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.length() > 64 || !COMPONENT.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase identifier");
        }
    }

    private static void requireEvent(String event) {
        if (event == null || event.length() > 128 || !EVENT.matcher(event).matches()) {
            throw new IllegalArgumentException("event must be a dotted lowercase identifier");
        }
    }

    private enum Severity {
        DEBUG("debug", Level.FINE, DirtConfig.ConsoleLogLevel.INFO),
        INFO("info", Level.INFO, DirtConfig.ConsoleLogLevel.INFO),
        WARNING("warning", Level.WARNING, DirtConfig.ConsoleLogLevel.WARNING),
        ERROR("error", Level.SEVERE, DirtConfig.ConsoleLogLevel.ERROR);

        private final String jsonName;
        private final Level javaLevel;
        private final DirtConfig.ConsoleLogLevel consoleLevel;

        Severity(String jsonName, Level javaLevel, DirtConfig.ConsoleLogLevel consoleLevel) {
            this.jsonName = jsonName;
            this.javaLevel = javaLevel;
            this.consoleLevel = consoleLevel;
        }

        private static Severity from(Level level) {
            if (level.intValue() >= Level.SEVERE.intValue()) {
                return ERROR;
            }
            if (level.intValue() >= Level.WARNING.intValue()) {
                return WARNING;
            }
            if (level.intValue() >= Level.INFO.intValue()) {
                return INFO;
            }
            return DEBUG;
        }
    }

    static final class JsonLineFormatter extends Formatter {
        private final long processId;

        JsonLineFormatter(long processId) {
            this.processId = processId;
        }

        @Override
        public String format(LogRecord record) {
            JsonObject json = new JsonObject();
            json.addProperty("timestamp", record.getInstant().toString());
            json.addProperty("level", Severity.from(record.getLevel()).jsonName);
            json.addProperty("service", SERVICE);
            json.addProperty("component", record.getSourceClassName());
            json.addProperty("event", record.getLoggerName());
            json.addProperty("message", bounded(record.getMessage(), MAX_MESSAGE_LENGTH));
            json.addProperty("pid", this.processId);
            json.addProperty("thread", record.getSourceMethodName());
            json.addProperty("thread_id", record.getLongThreadID());

            Object[] parameters = record.getParameters();
            if (parameters != null
                    && parameters.length == 1
                    && parameters[0] instanceof LogContext context) {
                for (Map.Entry<String, Object> entry : context.values().entrySet()) {
                    scalar(json, entry.getKey(), entry.getValue());
                }
            }
            if (record.getThrown() != null) {
                Throwable failure = record.getThrown();
                JsonObject exception = new JsonObject();
                exception.addProperty("type", failure.getClass().getName());
                if (failure.getMessage() != null) {
                    exception.addProperty(
                            "message", bounded(failure.getMessage(), MAX_MESSAGE_LENGTH));
                }
                exception.addProperty("stack", stackTrace(failure));
                json.add("exception", exception);
            }
            return JSON.toJson(json) + '\n';
        }

        private static void scalar(JsonObject json, String key, Object value) {
            if (value instanceof Number number) {
                if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                        || number instanceof Float floatValue && !Float.isFinite(floatValue)) {
                    json.add(key, JsonNull.INSTANCE);
                } else {
                    json.addProperty(key, number);
                }
            } else if (value instanceof Boolean booleanValue) {
                json.addProperty(key, booleanValue);
            } else {
                json.addProperty(key, value.toString());
            }
        }

        private static String stackTrace(Throwable failure) {
            StringWriter buffer = new StringWriter();
            failure.printStackTrace(new PrintWriter(buffer));
            return bounded(buffer.toString(), MAX_STACK_LENGTH);
        }
    }

    private static final class DetailErrorManager extends ErrorManager {
        private final Logger console;
        private final Path path;
        private final AtomicBoolean reported = new AtomicBoolean();

        private DetailErrorManager(Logger console, Path path) {
            this.console = console;
            this.path = path;
        }

        @Override
        public void error(String message, Exception failure, int code) {
            if (this.reported.compareAndSet(false, true)) {
                String summary =
                        "Dirt MCP detailed logging failed at "
                                + this.path
                                + "; further writer errors are suppressed";
                this.console.error(summary, failure);
            }
        }
    }

    private static String bounded(String value, int maximumLength) {
        if (value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength - TRUNCATION_SUFFIX.length()) + TRUNCATION_SUFFIX;
    }
}
