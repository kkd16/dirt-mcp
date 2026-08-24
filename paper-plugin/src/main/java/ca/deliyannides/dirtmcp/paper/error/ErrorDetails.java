package ca.deliyannides.dirtmcp.paper.error;

import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Strict, implementation-neutral context for a correctable application failure. */
public sealed interface ErrorDetails extends Serializable {
    sealed interface InvalidRequest extends ErrorDetails {
        record Missing(String field) implements InvalidRequest {
            public Missing {
                field = requireNonBlank(field, "field");
            }
        }

        record InvalidValue(String field) implements InvalidRequest {
            public InvalidValue {
                field = requireNonBlank(field, "field");
            }
        }

        record Duplicate(String field) implements InvalidRequest {
            public Duplicate {
                field = requireNonBlank(field, "field");
            }
        }

        record UnknownFields(String field) implements InvalidRequest {
            public UnknownFields {
                field = requireNonBlank(field, "field");
            }
        }

        record OutOfRange(String target, long value, long minimum, long maximum)
                implements InvalidRequest {
            public OutOfRange {
                target = requireNonBlank(target, "target");
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

        record UnsupportedValue(String target, List<String> allowedValues)
                implements InvalidRequest {
            public UnsupportedValue {
                target = requireNonBlank(target, "target");
                allowedValues = requireDistinctNonBlank(allowedValues, "allowedValues");
            }
        }

        record PaletteWeightsMixed(String field) implements InvalidRequest {
            public PaletteWeightsMixed {
                field = requireNonBlank(field, "field");
            }
        }

        record PaletteWeightTotal(String field, long requested) implements InvalidRequest {
            public PaletteWeightTotal {
                field = requireNonBlank(field, "field");
                requirePositive(requested, "requested");
                if (requested == 100) {
                    throw new IllegalArgumentException("requested must differ from required");
                }
            }
        }

        record TooManyItems(List<String> fields, int maximum) implements InvalidRequest {
            public TooManyItems {
                fields = requireDistinctNonBlank(fields, "fields");
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
            world = requireNonBlank(world, "world");
            UuidV4.require(requestedEditId, "requestedEditId");
        }
    }

    record EditNotLatest(String world, UUID requestedEditId, UUID newestEditId)
            implements ErrorDetails {
        public EditNotLatest {
            world = requireNonBlank(world, "world");
            UuidV4.require(requestedEditId, "requestedEditId");
            UuidV4.require(newestEditId, "newestEditId");
            if (requestedEditId.equals(newestEditId)) {
                throw new IllegalArgumentException("requestedEditId must differ from newestEditId");
            }
        }
    }

    record PlayerNotFound(String player) implements ErrorDetails {
        public PlayerNotFound {
            player = requirePlayerSelector(player);
        }
    }

    sealed interface PlayerUnavailable extends ErrorDetails {
        record SpectatingEntity(String player) implements PlayerUnavailable {
            public SpectatingEntity {
                player = requirePlayerSelector(player);
            }
        }

        record NonFiniteState(String player, String field) implements PlayerUnavailable {
            private static final Set<String> FIELDS =
                    Set.of(
                            "feetPosition.x",
                            "feetPosition.y",
                            "feetPosition.z",
                            "eyePosition.x",
                            "eyePosition.y",
                            "eyePosition.z",
                            "rotation.yaw",
                            "rotation.pitch",
                            "vitals.health",
                            "vitals.maxHealth",
                            "vitals.absorptionAmount",
                            "vitals.saturation",
                            "vitals.exhaustion",
                            "vitals.experienceProgress",
                            "movement.velocity.x",
                            "movement.velocity.y",
                            "movement.velocity.z",
                            "movement.fallDistance");

            public NonFiniteState {
                player = requirePlayerSelector(player);
                if (!FIELDS.contains(field)) {
                    throw new IllegalArgumentException("Unsupported non-finite player state field");
                }
            }
        }

        record PositionOutOfRange(String player, String field) implements PlayerUnavailable {
            private static final Set<String> FIELDS =
                    Set.of(
                            "feetPosition.x",
                            "feetPosition.y",
                            "feetPosition.z",
                            "eyePosition.x",
                            "eyePosition.y",
                            "eyePosition.z",
                            "perspectiveEndpoint.x",
                            "perspectiveEndpoint.y",
                            "perspectiveEndpoint.z");

            public PositionOutOfRange {
                player = requirePlayerSelector(player);
                if (!FIELDS.contains(field)) {
                    throw new IllegalArgumentException(
                            "Unsupported out-of-range player position field");
                }
            }
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

        record PerspectiveChunks(int requested, int maximum) implements RegionTooLarge {
            public PerspectiveChunks {
                requirePositive(requested, "requested");
                requirePositive(maximum, "maximum");
                if (requested <= maximum) {
                    throw new IllegalArgumentException("requested must exceed maximum");
                }
            }
        }

        record BlockCount(long minimumRequired, int maximum) implements RegionTooLarge {
            public BlockCount {
                requirePositive(minimumRequired, "minimumRequired");
                requirePositive(maximum, "maximum");
                if (minimumRequired <= maximum) {
                    throw new IllegalArgumentException("minimumRequired must exceed maximum");
                }
            }
        }
    }

    sealed interface ResultTooLarge extends ErrorDetails {
        long minimumRequired();

        int maximum();

        record StructureEntries(long minimumRequired, int maximum) implements ResultTooLarge {
            public StructureEntries {
                validateResultLimit(minimumRequired, maximum);
            }
        }

        record Palettes(long minimumRequired, int maximum) implements ResultTooLarge {
            public Palettes {
                validateResultLimit(minimumRequired, maximum);
            }
        }

        record PerspectiveRays(long minimumRequired, int maximum) implements ResultTooLarge {
            public PerspectiveRays {
                validateResultLimit(minimumRequired, maximum);
            }
        }

        record PerspectiveRayDistance(long minimumRequired, int maximum) implements ResultTooLarge {
            public PerspectiveRayDistance {
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
                world = requireNonBlank(world, "world");
            }
        }

        record RecoveryRequired(String world, UUID newestEditId) implements WorldBusy {
            public RecoveryRequired {
                world = requireNonBlank(world, "world");
                UuidV4.require(newestEditId, "newestEditId");
            }
        }
    }

    record WorldNotFound(String world) implements ErrorDetails {
        public WorldNotFound {
            world = requireNonBlank(world, "world");
        }
    }

    sealed interface WorldUnavailable extends ErrorDetails {
        record Stopping() implements WorldUnavailable {}

        record Interrupted() implements WorldUnavailable {}

        record PaperUnavailable() implements WorldUnavailable {}

        record OperationFailed() implements WorldUnavailable {}

        record RolledBack() implements WorldUnavailable {}

        record RollbackFailed() implements WorldUnavailable {}

        record WorldUnloaded(String world) implements WorldUnavailable {
            public WorldUnloaded {
                world = requireNonBlank(world, "world");
            }
        }

        record ChunkUnloaded(String world, Chunk chunk) implements WorldUnavailable {
            public ChunkUnloaded {
                world = requireNonBlank(world, "world");
                Objects.requireNonNull(chunk, "chunk");
            }
        }

        record ChunkLoadFailed(String world, Chunk chunk) implements WorldUnavailable {
            public ChunkLoadFailed {
                world = requireNonBlank(world, "world");
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

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requirePlayerSelector(String value) {
        String selector = requireNonBlank(value, "player");
        if (selector.length() > 36) {
            throw new IllegalArgumentException("player must contain at most 36 characters");
        }
        return selector;
    }

    private static List<String> requireDistinctNonBlank(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank values");
            }
        }
        List<String> copy = List.copyOf(values);
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicates");
        }
        return copy;
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
