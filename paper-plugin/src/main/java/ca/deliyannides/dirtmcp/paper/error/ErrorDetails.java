package ca.deliyannides.dirtmcp.paper.error;

import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Strict, implementation-neutral context for a correctable bridge failure. */
public sealed interface ErrorDetails extends Serializable {
    record Unauthorized() implements ErrorDetails {}

    record NotFound() implements ErrorDetails {}

    record MethodNotAllowed(String allowedMethod) implements ErrorDetails {
        public MethodNotAllowed {
            if (!"GET".equals(allowedMethod) && !"POST".equals(allowedMethod)) {
                throw new IllegalArgumentException("Allowed method must be GET or POST");
            }
        }
    }

    record BridgeBusy(int maximumConcurrentRequests) implements ErrorDetails {
        public BridgeBusy {
            requirePositive(maximumConcurrentRequests, "maximumConcurrentRequests");
        }
    }

    sealed interface InvalidRequest extends ErrorDetails {
        record UnsupportedMediaType(String expected) implements InvalidRequest {
            public UnsupportedMediaType {
                if (!"application/json".equals(expected)) {
                    throw new IllegalArgumentException(
                            "Expected media type must be application/json");
                }
            }
        }

        record BodyTooLarge(int maximumBytes) implements InvalidRequest {
            public BodyTooLarge {
                requirePositive(maximumBytes, "maximumBytes");
            }
        }

        record MalformedJson() implements InvalidRequest {}

        record Missing(String field) implements InvalidRequest {
            public Missing {
                field = requireField(field);
            }
        }

        record InvalidValue(String field) implements InvalidRequest {
            public InvalidValue {
                field = requireField(field);
            }
        }

        record Duplicate(String field) implements InvalidRequest {
            public Duplicate {
                field = requireField(field);
            }
        }

        record UnknownFields(String field) implements InvalidRequest {
            public UnknownFields {
                field = requireField(field);
            }
        }

        record OutOfRange(String field, long value, long minimum, long maximum)
                implements InvalidRequest {
            public OutOfRange {
                field = requireField(field);
                requireSafeInteger(value, "value");
                requireSafeInteger(minimum, "minimum");
                requireSafeInteger(maximum, "maximum");
                if (minimum > maximum) {
                    throw new IllegalArgumentException("minimum must not exceed maximum");
                }
                if (value >= minimum && value <= maximum) {
                    throw new IllegalArgumentException("value must be outside the allowed range");
                }
            }
        }

        record TooManyItems(List<String> fields, int maximum) implements InvalidRequest {
            public TooManyItems {
                Objects.requireNonNull(fields, "fields");
                if (fields.isEmpty()) {
                    throw new IllegalArgumentException("fields must not be empty");
                }
                for (String field : fields) {
                    requireField(field);
                }
                fields = List.copyOf(fields);
                if (new HashSet<>(fields).size() != fields.size()) {
                    throw new IllegalArgumentException("fields must not contain duplicates");
                }
                requirePositive(maximum, "maximum");
            }
        }
    }

    record ChangeLimitExceeded(int maximum) implements ErrorDetails {
        public ChangeLimitExceeded {
            requirePositive(maximum, "maximum");
        }
    }

    record EditNotFound(String world, UUID requestedEditId) implements ErrorDetails {
        public EditNotFound {
            world = requireWorld(world);
            UuidV4.require(requestedEditId, "requestedEditId");
        }
    }

    record EditNotLatest(String world, UUID requestedEditId, UUID newestEditId)
            implements ErrorDetails {
        public EditNotLatest {
            world = requireWorld(world);
            UuidV4.require(requestedEditId, "requestedEditId");
            UuidV4.require(newestEditId, "newestEditId");
        }
    }

    sealed interface HistoryCapacityExceeded extends ErrorDetails {
        long maximum();

        record EntriesPerWorld(long maximum) implements HistoryCapacityExceeded {
            public EntriesPerWorld {
                requirePositive(maximum, "maximum");
            }
        }

        record EntriesTotal(long maximum) implements HistoryCapacityExceeded {
            public EntriesTotal {
                requirePositive(maximum, "maximum");
            }
        }

        record RetainedChangedBlocks(long maximum) implements HistoryCapacityExceeded {
            public RetainedChangedBlocks {
                requirePositive(maximum, "maximum");
            }
        }
    }

    sealed interface RegionTooLarge extends ErrorDetails {
        int maximum();

        record Volume(Dimensions dimensions, int maximum) implements RegionTooLarge {
            public Volume {
                Objects.requireNonNull(dimensions, "dimensions");
                requirePositive(maximum, "maximum");
                if (!volumeExceeds(dimensions, maximum)) {
                    throw new IllegalArgumentException("dimensions volume must exceed maximum");
                }
            }
        }

        record TouchedChunks(long minimumRequired, int maximum) implements RegionTooLarge {
            public TouchedChunks {
                requirePositive(minimumRequired, "minimumRequired");
                requirePositive(maximum, "maximum");
                if (minimumRequired <= maximum) {
                    throw new IllegalArgumentException("minimumRequired must exceed maximum");
                }
            }
        }

        record BlockCount(int requested, int maximum) implements RegionTooLarge {
            public BlockCount {
                requirePositive(requested, "requested");
                requirePositive(maximum, "maximum");
                if (requested <= maximum) {
                    throw new IllegalArgumentException("requested must exceed maximum");
                }
            }
        }
    }

    sealed interface ResultTooLarge extends ErrorDetails {
        long minimumRequired();

        int maximum();

        record Blocks(long minimumRequired, int maximum) implements ResultTooLarge {
            public Blocks {
                validateResultLimit(minimumRequired, maximum);
            }
        }

        record Runs(long minimumRequired, int maximum) implements ResultTooLarge {
            public Runs {
                validateResultLimit(minimumRequired, maximum);
            }
        }

        record VisibleBlocks(long minimumRequired, int maximum) implements ResultTooLarge {
            public VisibleBlocks {
                validateResultLimit(minimumRequired, maximum);
            }
        }
    }

    sealed interface ServerUnavailable extends ErrorDetails {
        record DependencyUnavailable() implements ServerUnavailable {}

        record PaperUnavailable() implements ServerUnavailable {}

        record InspectionBusy(int maximumConcurrentInspections) implements ServerUnavailable {
            public InspectionBusy {
                requirePositive(maximumConcurrentInspections, "maximumConcurrentInspections");
            }
        }
    }

    sealed interface Unhealthy extends ErrorDetails {
        record PluginDisabled() implements Unhealthy {}

        record DependencyUnavailable() implements Unhealthy {}

        record NoLoadedWorlds() implements Unhealthy {}

        record HealthCheckFailed() implements Unhealthy {}

        record PaperUnavailable() implements Unhealthy {}
    }

    sealed interface WorldBusy extends ErrorDetails {
        String world();

        record OperationInProgress(String world) implements WorldBusy {
            public OperationInProgress {
                world = requireWorld(world);
            }
        }

        record RecoveryRequired(String world, UUID newestEditId) implements WorldBusy {
            public RecoveryRequired {
                world = requireWorld(world);
                UuidV4.require(newestEditId, "newestEditId");
            }
        }
    }

    record WorldNotFound(String world) implements ErrorDetails {
        public WorldNotFound {
            world = requireWorld(world);
        }
    }

    sealed interface WorldUnavailable extends ErrorDetails {
        record Stopping() implements WorldUnavailable {}

        record Interrupted() implements WorldUnavailable {}

        record PaperUnavailable() implements WorldUnavailable {}

        record OperationFailed() implements WorldUnavailable {}

        record RollbackFailed() implements WorldUnavailable {}

        record WorldUnloaded(String world) implements WorldUnavailable {
            public WorldUnloaded {
                world = requireWorld(world);
            }
        }

        record ChunkUnloaded(String world, Chunk chunk) implements WorldUnavailable {
            public ChunkUnloaded {
                world = requireWorld(world);
                Objects.requireNonNull(chunk, "chunk");
            }
        }

        record ChunkLoadFailed(String world, Chunk chunk) implements WorldUnavailable {
            public ChunkLoadFailed {
                world = requireWorld(world);
                Objects.requireNonNull(chunk, "chunk");
            }
        }
    }

    record Dimensions(long x, long y, long z) implements Serializable {
        public Dimensions {
            requirePositive(x, "x");
            requirePositive(y, "y");
            requirePositive(z, "z");
        }
    }

    record Chunk(int x, int z) implements Serializable {}

    private static String requireField(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        return value;
    }

    private static String requireWorld(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("world must not be blank");
        }
        return value;
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        requireSafeInteger(value, name);
    }

    private static void requireSafeInteger(long value, String name) {
        if (value < -9_007_199_254_740_991L || value > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException(name + " must be a JSON-safe integer");
        }
    }

    private static void validateResultLimit(long minimumRequired, int maximum) {
        requirePositive(minimumRequired, "minimumRequired");
        requirePositive(maximum, "maximum");
        if (minimumRequired <= maximum) {
            throw new IllegalArgumentException("minimumRequired must exceed maximum");
        }
    }

    private static boolean volumeExceeds(Dimensions dimensions, int maximum) {
        return dimensions.x() > maximum
                || dimensions.y() > maximum / dimensions.x()
                || dimensions.z() > maximum / (dimensions.x() * dimensions.y());
    }
}
