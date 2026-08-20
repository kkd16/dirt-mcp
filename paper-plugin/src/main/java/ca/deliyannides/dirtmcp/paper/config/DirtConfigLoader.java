package ca.deliyannides.dirtmcp.paper.config;

import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

public final class DirtConfigLoader {
    private static final Set<String> SECTIONS =
            Set.of("bridge", "limits", "edit-history", "defaults");
    private static final Set<String> PATHS =
            Set.of(
                    "bridge.port",
                    "bridge.backlog",
                    "bridge.shutdown-delay-seconds",
                    "bridge.request-body-timeout-seconds",
                    "bridge.minimum-token-bytes",
                    "bridge.max-concurrent-requests",
                    "bridge.max-concurrent-inspections",
                    "limits.max-request-bytes",
                    "limits.max-region-volume",
                    "limits.max-touched-chunks",
                    "limits.max-inspection-touched-chunks",
                    "limits.max-block-state-patterns",
                    "limits.max-changed-blocks",
                    "limits.max-inspection-volume",
                    "limits.default-inspection-results",
                    "limits.max-inspection-results",
                    "edit-history.max-entries-per-world",
                    "edit-history.max-entries-total",
                    "edit-history.max-retained-changed-blocks",
                    "defaults.region-blocks-include-air",
                    "defaults.region-blocks-format",
                    "defaults.edit-dry-run");

    private DirtConfigLoader() {}

    public static DirtConfig load(FileConfiguration config, String portOverride) {
        validateKeys(config);
        int configuredPort = requiredInteger(config, "bridge.port");
        int port = parsePortOverride(portOverride, configuredPort);

        return new DirtConfig(
                new DirtConfig.Bridge(
                        port,
                        requiredInteger(config, "bridge.backlog"),
                        requiredInteger(config, "bridge.shutdown-delay-seconds"),
                        requiredInteger(config, "bridge.request-body-timeout-seconds"),
                        requiredInteger(config, "bridge.minimum-token-bytes"),
                        requiredInteger(config, "bridge.max-concurrent-requests"),
                        requiredInteger(config, "bridge.max-concurrent-inspections")),
                new DirtConfig.Limits(
                        requiredInteger(config, "limits.max-request-bytes"),
                        requiredInteger(config, "limits.max-region-volume"),
                        requiredInteger(config, "limits.max-touched-chunks"),
                        requiredInteger(config, "limits.max-inspection-touched-chunks"),
                        requiredInteger(config, "limits.max-block-state-patterns"),
                        requiredInteger(config, "limits.max-changed-blocks"),
                        requiredInteger(config, "limits.max-inspection-volume"),
                        requiredInteger(config, "limits.default-inspection-results"),
                        requiredInteger(config, "limits.max-inspection-results")),
                new DirtConfig.EditHistory(
                        requiredInteger(config, "edit-history.max-entries-per-world"),
                        requiredInteger(config, "edit-history.max-entries-total"),
                        requiredInteger(config, "edit-history.max-retained-changed-blocks")),
                new DirtConfig.Defaults(
                        requiredBoolean(config, "defaults.region-blocks-include-air"),
                        requiredString(config, "defaults.region-blocks-format"),
                        requiredBoolean(config, "defaults.edit-dry-run")));
    }

    private static void validateKeys(FileConfiguration config) {
        for (String path : PATHS) {
            if (!config.isSet(path)) {
                throw new IllegalArgumentException(path + " is required");
            }
        }
        for (String key : config.getKeys(true)) {
            if (!SECTIONS.contains(key) && !PATHS.contains(key)) {
                throw new IllegalArgumentException("Unknown configuration key: " + key);
            }
        }
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
}
