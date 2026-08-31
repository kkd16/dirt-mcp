package ca.deliyannides.dirtmcp.paper.config;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeOperation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

public final class DirtConfigLoader {
    private static final Set<String> SECTIONS =
            Set.of("bridge", "access-control", "logging", "limits", "edit-history");
    private static final Set<String> REQUIRED_PATHS =
            Set.of(
                    "bridge.port",
                    "bridge.shutdown-delay-seconds",
                    "bridge.request-body-timeout-seconds",
                    "bridge.max-concurrent-requests",
                    "bridge.max-concurrent-inspections",
                    "bridge.max-request-bytes",
                    "bridge.allowed-operations",
                    "access-control.url",
                    "access-control.connect-timeout-millis",
                    "access-control.request-timeout-millis",
                    "logging.console-level",
                    "logging.detail-file-max-bytes",
                    "logging.detail-file-retained-files",
                    "limits.max-region-volume",
                    "limits.max-edit-touched-chunks",
                    "limits.max-inspection-touched-chunks",
                    "limits.max-perspective-touched-chunks",
                    "limits.max-block-state-patterns",
                    "limits.max-palette-entries",
                    "limits.max-changed-blocks",
                    "limits.max-inspection-volume",
                    "limits.max-perspective-ray-distance-budget",
                    "limits.max-inspection-results",
                    "limits.max-perspective-rays",
                    "limits.max-commands-per-request",
                    "limits.max-command-feedback-characters",
                    "edit-history.max-entries-per-world",
                    "edit-history.max-entries-total",
                    "edit-history.max-retained-changed-blocks");

    private DirtConfigLoader() {}

    public static DirtConfig load(
            FileConfiguration config, String portOverride, String accessControlUrlOverride) {
        validateKeys(config);
        int configuredPort = requiredInteger(config, "bridge.port");
        int port = parsePortOverride(portOverride, configuredPort);
        String configuredAccessControlUrl = requiredString(config, "access-control.url");
        String accessControlUrl =
                accessControlUrlOverride == null || accessControlUrlOverride.isBlank()
                        ? configuredAccessControlUrl
                        : accessControlUrlOverride;

        return new DirtConfig(
                new DirtConfig.Bridge(
                        port,
                        requiredInteger(config, "bridge.shutdown-delay-seconds"),
                        requiredInteger(config, "bridge.request-body-timeout-seconds"),
                        requiredInteger(config, "bridge.max-concurrent-requests"),
                        requiredInteger(config, "bridge.max-concurrent-inspections"),
                        requiredInteger(config, "bridge.max-request-bytes"),
                        requiredOperations(config, "bridge.allowed-operations")),
                new DirtConfig.AccessControl(
                        accessControlUrl,
                        requiredInteger(config, "access-control.connect-timeout-millis"),
                        requiredInteger(config, "access-control.request-timeout-millis")),
                new DirtConfig.Logging(
                        DirtConfig.ConsoleLogLevel.parse(
                                requiredString(config, "logging.console-level")
                                        .toLowerCase(java.util.Locale.ROOT)),
                        requiredInteger(config, "logging.detail-file-max-bytes"),
                        requiredInteger(config, "logging.detail-file-retained-files")),
                new DirtConfig.Limits(
                        requiredInteger(config, "limits.max-region-volume"),
                        requiredInteger(config, "limits.max-edit-touched-chunks"),
                        requiredInteger(config, "limits.max-inspection-touched-chunks"),
                        requiredInteger(config, "limits.max-perspective-touched-chunks"),
                        requiredInteger(config, "limits.max-block-state-patterns"),
                        requiredInteger(config, "limits.max-palette-entries"),
                        requiredInteger(config, "limits.max-changed-blocks"),
                        requiredInteger(config, "limits.max-inspection-volume"),
                        requiredInteger(config, "limits.max-perspective-ray-distance-budget"),
                        requiredInteger(config, "limits.max-inspection-results"),
                        requiredInteger(config, "limits.max-perspective-rays"),
                        requiredInteger(config, "limits.max-commands-per-request"),
                        requiredInteger(config, "limits.max-command-feedback-characters")),
                new DirtConfig.EditHistory(
                        requiredInteger(config, "edit-history.max-entries-per-world"),
                        requiredInteger(config, "edit-history.max-entries-total"),
                        requiredInteger(config, "edit-history.max-retained-changed-blocks")));
    }

    private static void validateKeys(FileConfiguration config) {
        for (String path : REQUIRED_PATHS) {
            if (!isExplicitlySet(config, path)) {
                throw new IllegalArgumentException(path + " is required");
            }
        }
        for (String key : config.getKeys(true)) {
            if (!SECTIONS.contains(key) && !REQUIRED_PATHS.contains(key)) {
                throw new IllegalArgumentException("Unknown configuration key: " + key);
            }
        }
    }

    private static List<BridgeOperation> requiredOperations(FileConfiguration config, String path) {
        Object configured = config.get(path);
        if (!(configured instanceof List<?> values)) {
            throw new IllegalArgumentException(path + " must be a list of operationIds");
        }
        List<BridgeOperation> operations = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (!(value instanceof String operationId) || operationId.isBlank()) {
                throw new IllegalArgumentException(
                        path + "[" + index + "] must be a non-empty operationId");
            }
            BridgeOperation operation = BridgeOperation.parse(operationId);
            operations.add(operation);
        }
        return operations;
    }

    private static int parsePortOverride(String override, int configuredPort) {
        if (override == null || override.isBlank()) {
            return configuredPort;
        }
        try {
            int port = Integer.parseInt(override.trim());
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("DIRT_BRIDGE_PORT must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("DIRT_BRIDGE_PORT must be an integer", exception);
        }
    }

    private static int requiredInteger(FileConfiguration config, String path) {
        if (!config.isInt(path)) {
            throw new IllegalArgumentException(path + " must be a signed 32-bit integer");
        }
        return config.getInt(path);
    }

    private static boolean isExplicitlySet(FileConfiguration config, String path) {
        return config.contains(path, true);
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
