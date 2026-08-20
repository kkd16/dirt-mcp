package ca.deliyannides.dirtmcp.paper.logging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable, explicitly propagated scalar context for one log event. */
public final class LogContext {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern SENSITIVE_KEY =
            Pattern.compile(
                    ".*(api_?key|access_?key|authorization|bearer|credential|password|secret|token).*");
    private static final Set<String> RESERVED_KEYS =
            Set.of(
                    "timestamp",
                    "level",
                    "service",
                    "component",
                    "event",
                    "message",
                    "pid",
                    "thread",
                    "thread_id",
                    "exception");
    private static final int MAX_STRING_LENGTH = 2_048;
    private static final int MAX_FIELDS = 64;
    private static final String TRUNCATION_SUFFIX = "...[truncated]";
    private static final LogContext EMPTY = new LogContext(Map.of());

    private final Map<String, Object> values;

    private LogContext(Map<String, Object> values) {
        this.values = values;
    }

    public static LogContext empty() {
        return EMPTY;
    }

    public static LogContext of(String key, Object value) {
        return EMPTY.with(key, value);
    }

    public LogContext with(String key, Object value) {
        requireKey(key);
        Object scalar = requireScalar(value);
        if (!this.values.containsKey(key) && this.values.size() >= MAX_FIELDS) {
            throw new IllegalStateException("Log context cannot contain more than 64 fields");
        }
        Map<String, Object> copy = new LinkedHashMap<>(this.values);
        copy.put(key, scalar);
        return new LogContext(Collections.unmodifiableMap(copy));
    }

    public Map<String, Object> values() {
        return this.values;
    }

    private static void requireKey(String key) {
        if (key == null || key.length() > 64 || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Log context keys must use snake_case");
        }
        if (SENSITIVE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Sensitive values cannot be added to log context");
        }
        if (RESERVED_KEYS.contains(key)) {
            throw new IllegalArgumentException("Canonical log fields cannot be replaced");
        }
    }

    private static Object requireScalar(Object value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof String stringValue) {
            return bounded(stringValue);
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof Boolean
                || value instanceof UUID) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name().toLowerCase(java.util.Locale.ROOT);
        }
        throw new IllegalArgumentException(
                "Log context values must be strings, numbers, booleans, UUIDs, or enums");
    }

    private static String bounded(String value) {
        if (value.length() <= MAX_STRING_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_STRING_LENGTH - TRUNCATION_SUFFIX.length())
                + TRUNCATION_SUFFIX;
    }
}
