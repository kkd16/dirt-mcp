package ca.deliyannides.dirtmcp.paper;

import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(Bridge bridge, Limits limits, Defaults defaults) {
    private static final Set<String> REGION_BLOCKS_FORMATS = Set.of("blocks", "runs");

    public static PluginSettings load(FileConfiguration config, String portOverride) {
        int configuredPort = positiveInteger(config, "bridge.port");
        int port = parsePortOverride(portOverride, configuredPort);
        if (port > 65_535) {
            throw new IllegalArgumentException("bridge.port must be between 1 and 65535");
        }

        int maximumRequestBytes = positiveInteger(config, "limits.max-request-bytes");
        if (maximumRequestBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "limits.max-request-bytes must be less than " + Integer.MAX_VALUE);
        }

        Bridge bridge =
                new Bridge(
                        port,
                        nonNegativeInteger(config, "bridge.backlog"),
                        nonNegativeInteger(config, "bridge.shutdown-delay-seconds"),
                        positiveInteger(config, "bridge.minimum-token-bytes"));

        int maximumRegionVolume = positiveInteger(config, "limits.max-region-volume");
        int maximumChangedBlocks = positiveInteger(config, "limits.max-changed-blocks");
        int maximumInspectionVolume = positiveInteger(config, "limits.max-inspection-volume");
        int maximumInspectionResults = positiveInteger(config, "limits.max-inspection-results");
        int defaultInspectionResults = positiveInteger(config, "limits.default-inspection-results");
        requireAtMost(
                "limits.max-changed-blocks",
                maximumChangedBlocks,
                "limits.max-region-volume",
                maximumRegionVolume);
        requireAtMost(
                "limits.max-inspection-volume",
                maximumInspectionVolume,
                "limits.max-region-volume",
                maximumRegionVolume);
        requireAtMost(
                "limits.default-inspection-results",
                defaultInspectionResults,
                "limits.max-inspection-results",
                maximumInspectionResults);
        requireAtMost(
                "limits.max-inspection-results",
                maximumInspectionResults,
                "limits.max-inspection-volume",
                maximumInspectionVolume);

        Limits limits =
                new Limits(
                        maximumRequestBytes,
                        maximumRegionVolume,
                        maximumChangedBlocks,
                        maximumInspectionVolume,
                        defaultInspectionResults,
                        maximumInspectionResults,
                        positiveInteger(config, "limits.max-commands-per-request"),
                        positiveInteger(config, "limits.max-command-feedback-characters"),
                        nonNegativeInteger(config, "limits.undo-history-per-world"));

        String regionBlocksFormat =
                requiredString(config, "defaults.region-blocks-format").toLowerCase(Locale.ROOT);
        if (!REGION_BLOCKS_FORMATS.contains(regionBlocksFormat)) {
            throw new IllegalArgumentException(
                    "defaults.region-blocks-format must be blocks or runs");
        }
        Defaults defaults =
                new Defaults(
                        requiredBoolean(config, "defaults.region-blocks-include-air"),
                        regionBlocksFormat,
                        requiredBoolean(config, "defaults.edit-dry-run"));

        return new PluginSettings(bridge, limits, defaults);
    }

    private static int parsePortOverride(String override, int configuredPort) {
        if (override == null || override.isBlank()) {
            return configuredPort;
        }
        try {
            int port = Integer.parseInt(override.trim());
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                        "DIRT_MCP_BRIDGE_PORT must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "DIRT_MCP_BRIDGE_PORT must be an integer", exception);
        }
    }

    private static int positiveInteger(FileConfiguration config, String path) {
        int value = requiredInteger(config, path);
        if (value < 1) {
            throw new IllegalArgumentException(path + " must be positive");
        }
        return value;
    }

    private static int nonNegativeInteger(FileConfiguration config, String path) {
        int value = requiredInteger(config, path);
        if (value < 0) {
            throw new IllegalArgumentException(path + " must be non-negative");
        }
        return value;
    }

    private static int requiredInteger(FileConfiguration config, String path) {
        if (!config.isInt(path)) {
            throw new IllegalArgumentException(path + " must be a signed 32-bit integer");
        }
        return config.getInt(path);
    }

    private static boolean requiredBoolean(FileConfiguration config, String path) {
        if (!config.isBoolean(path)) {
            throw new IllegalArgumentException(path + " must be true or false");
        }
        return config.getBoolean(path);
    }

    private static String requiredString(FileConfiguration config, String path) {
        if (!config.isString(path)) {
            throw new IllegalArgumentException(path + " must be a non-empty string");
        }
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " must be a non-empty string");
        }
        return value;
    }

    private static void requireAtMost(String lowerPath, int lower, String upperPath, int upper) {
        if (lower > upper) {
            throw new IllegalArgumentException(lowerPath + " must not exceed " + upperPath);
        }
    }

    public record Bridge(int port, int backlog, int shutdownDelaySeconds, int minimumTokenBytes) {}

    public record Limits(
            int maxRequestBytes,
            int maxRegionVolume,
            int maxChangedBlocks,
            int maxInspectionVolume,
            int defaultInspectionResultLimit,
            int maxInspectionResultLimit,
            int maxCommandsPerRequest,
            int maxCommandFeedbackCharacters,
            int undoHistoryPerWorld) {}

    public record Defaults(
            boolean regionBlocksIncludeAir, String regionBlocksFormat, boolean editDryRun) {}
}
