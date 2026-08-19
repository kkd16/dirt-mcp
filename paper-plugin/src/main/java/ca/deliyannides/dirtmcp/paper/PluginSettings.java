package ca.deliyannides.dirtmcp.paper;

import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(
        Bridge bridge,
        Limits limits,
        Defaults defaults) {
    private static final Set<String> REGION_BLOCKS_FORMATS = Set.of("blocks", "runs");

    public static PluginSettings load(FileConfiguration config, String portOverride) {
        int configuredPort = positiveInteger(config, "bridge.port");
        int port = parsePortOverride(portOverride, configuredPort);
        if (port > 65_535) {
            throw new IllegalArgumentException("bridge.port must be between 1 and 65535");
        }

        int maximumRequestBytes = positiveInteger(config, "bridge.max-request-bytes");
        if (maximumRequestBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "bridge.max-request-bytes must be less than " + Integer.MAX_VALUE);
        }

        Bridge bridge = new Bridge(
                port,
                nonNegativeInteger(config, "bridge.backlog"),
                nonNegativeInteger(config, "bridge.shutdown-delay-seconds"),
                maximumRequestBytes,
                positiveInteger(config, "bridge.minimum-token-bytes"));

        int maximumRegionBlocksResults = positiveInteger(config, "limits.max-exact-results");
        int defaultRegionBlocksResults = positiveInteger(config, "limits.default-exact-results");
        requireAtMost(
                "limits.default-exact-results",
                defaultRegionBlocksResults,
                "limits.max-exact-results",
                maximumRegionBlocksResults);

        int maximumOrthographicViewResults = positiveInteger(config, "limits.max-view-results");
        int defaultOrthographicViewResults = positiveInteger(config, "limits.default-view-results");
        requireAtMost(
                "limits.default-view-results",
                defaultOrthographicViewResults,
                "limits.max-view-results",
                maximumOrthographicViewResults);

        Limits limits = new Limits(
                positiveInteger(config, "limits.max-region-volume"),
                positiveInteger(config, "limits.max-changed-blocks"),
                positiveInteger(config, "limits.max-exact-inspection-volume"),
                defaultRegionBlocksResults,
                maximumRegionBlocksResults,
                positiveInteger(config, "limits.max-view-volume"),
                defaultOrthographicViewResults,
                maximumOrthographicViewResults,
                nonNegativeInteger(config, "limits.undo-history-per-world"));

        String regionBlocksFormat = requiredString(config, "defaults.exact-inspection-mode")
                .toLowerCase(Locale.ROOT);
        if (!REGION_BLOCKS_FORMATS.contains(regionBlocksFormat)) {
            throw new IllegalArgumentException(
                    "defaults.exact-inspection-mode must be blocks or runs");
        }
        Defaults defaults = new Defaults(
                requiredBoolean(config, "defaults.exact-inspection-include-air"),
                regionBlocksFormat,
                requiredBoolean(config, "defaults.replace-dry-run"),
                requiredBoolean(config, "defaults.fill-dry-run"));

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

    private static void requireAtMost(
            String lowerPath,
            int lower,
            String upperPath,
            int upper) {
        if (lower > upper) {
            throw new IllegalArgumentException(lowerPath + " must not exceed " + upperPath);
        }
    }

    public record Bridge(
            int port,
            int backlog,
            int shutdownDelaySeconds,
            int maxRequestBytes,
            int minimumTokenBytes) {}

    public record Limits(
            int maxRegionVolume,
            int maxChangedBlocks,
            int maxRegionBlocksVolume,
            int defaultRegionBlocksResultLimit,
            int maxRegionBlocksResultLimit,
            int maxOrthographicViewVolume,
            int defaultOrthographicViewResultLimit,
            int maxOrthographicViewResultLimit,
            int undoHistoryPerWorld) {}

    public record Defaults(
            boolean regionBlocksIncludeAir,
            String regionBlocksFormat,
            boolean replaceRegionBlocksDryRun,
            boolean fillRegionDryRun) {}
}
