package ca.deliyannides.dirtmcp.paper.config;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeOperation;
import java.util.HashSet;
import java.util.List;

public record DirtConfig(Bridge bridge, Logging logging, Limits limits, EditHistory editHistory) {
    public DirtConfig {
        if (bridge == null || logging == null || limits == null || editHistory == null) {
            throw new IllegalArgumentException("Configuration sections are required");
        }
        requireAtMost(
                "limits.max-changed-blocks",
                limits.maxChangedBlocks(),
                "edit-history.max-retained-changed-blocks",
                editHistory.maxRetainedChangedBlocks());
    }

    public enum ConsoleLogLevel {
        INFO("info", 0),
        WARNING("warning", 1),
        ERROR("error", 2);

        private final String configName;
        private final int severity;

        ConsoleLogLevel(String configName, int severity) {
            this.configName = configName;
            this.severity = severity;
        }

        public String configName() {
            return this.configName;
        }

        public boolean allows(ConsoleLogLevel eventLevel) {
            return eventLevel.severity >= this.severity;
        }

        public static ConsoleLogLevel parse(String value) {
            for (ConsoleLogLevel level : values()) {
                if (level.configName.equals(value)) {
                    return level;
                }
            }
            throw new IllegalArgumentException(
                    "logging.console-level must be info, warning, or error");
        }
    }

    public record Logging(
            ConsoleLogLevel consoleLevel, int detailFileMaxBytes, int detailFileRetainedFiles) {
        public Logging {
            if (consoleLevel == null) {
                throw new IllegalArgumentException("logging.console-level is required");
            }
            if (detailFileMaxBytes < 1) {
                throw new IllegalArgumentException(
                        "logging.detail-file-max-bytes must be positive");
            }
            if (detailFileRetainedFiles < 2 || detailFileRetainedFiles > 100) {
                throw new IllegalArgumentException(
                        "logging.detail-file-retained-files must be between 2 and 100");
            }
        }
    }

    public record Bridge(
            int port,
            int shutdownDelaySeconds,
            int requestBodyTimeoutSeconds,
            int maxConcurrentRequests,
            int maxConcurrentInspections,
            int maxRequestBytes,
            List<BridgeOperation> allowedOperations) {
        private static final int MAXIMUM_REQUEST_BYTES = 64 * 1024 * 1024;

        public Bridge {
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("bridge.port must be between 1 and 65535");
            }
            if (shutdownDelaySeconds < 1 || shutdownDelaySeconds > 30) {
                throw new IllegalArgumentException(
                        "bridge.shutdown-delay-seconds must be between 1 and 30");
            }
            if (requestBodyTimeoutSeconds < 1 || requestBodyTimeoutSeconds > 30) {
                throw new IllegalArgumentException(
                        "bridge.request-body-timeout-seconds must be between 1 and 30");
            }
            if (maxConcurrentRequests < 1) {
                throw new IllegalArgumentException(
                        "bridge.max-concurrent-requests must be positive");
            }
            if (maxConcurrentInspections < 1 || maxConcurrentInspections > maxConcurrentRequests) {
                throw new IllegalArgumentException(
                        "bridge.max-concurrent-inspections must be positive and not exceed "
                                + "bridge.max-concurrent-requests");
            }
            if (maxRequestBytes < 1 || maxRequestBytes > MAXIMUM_REQUEST_BYTES) {
                throw new IllegalArgumentException(
                        "bridge.max-request-bytes must be between 1 and " + MAXIMUM_REQUEST_BYTES);
            }
            if (allowedOperations == null) {
                throw new IllegalArgumentException("bridge.allowed-operations is required");
            }
            if (allowedOperations.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException(
                        "bridge.allowed-operations must contain only operationIds");
            }
            allowedOperations = List.copyOf(allowedOperations);
            if (new HashSet<>(allowedOperations).size() != allowedOperations.size()) {
                throw new IllegalArgumentException(
                        "bridge.allowed-operations must not contain duplicates");
            }
        }
    }

    public record Limits(
            int maxRegionVolume,
            int maxEditTouchedChunks,
            int maxInspectionTouchedChunks,
            int maxPerspectiveTouchedChunks,
            int maxBlockStatePatterns,
            int maxPaletteEntries,
            int maxChangedBlocks,
            int maxInspectionVolume,
            int maxPerspectiveRayDistanceBudget,
            int maxInspectionResultLimit,
            int maxPerspectiveRays,
            int maxCommandsPerRequest,
            int maxCommandFeedbackCharacters) {
        public Limits {
            requirePositive("limits.max-region-volume", maxRegionVolume);
            requirePositive("limits.max-edit-touched-chunks", maxEditTouchedChunks);
            requirePositive("limits.max-inspection-touched-chunks", maxInspectionTouchedChunks);
            requirePositive("limits.max-perspective-touched-chunks", maxPerspectiveTouchedChunks);
            requirePositive("limits.max-block-state-patterns", maxBlockStatePatterns);
            requirePositive("limits.max-palette-entries", maxPaletteEntries);
            requirePositive("limits.max-changed-blocks", maxChangedBlocks);
            requirePositive("limits.max-inspection-volume", maxInspectionVolume);
            requirePositive(
                    "limits.max-perspective-ray-distance-budget", maxPerspectiveRayDistanceBudget);
            requirePositive("limits.max-inspection-results", maxInspectionResultLimit);
            requirePositive("limits.max-perspective-rays", maxPerspectiveRays);
            requirePositive("limits.max-commands-per-request", maxCommandsPerRequest);
            requirePositive("limits.max-command-feedback-characters", maxCommandFeedbackCharacters);
            requireAtMost(
                    "limits.max-block-state-patterns",
                    maxBlockStatePatterns,
                    "the protocol maximum",
                    64);
            requireAtMost(
                    "limits.max-palette-entries", maxPaletteEntries, "the protocol maximum", 256);
            requireAtMost(
                    "limits.max-changed-blocks",
                    maxChangedBlocks,
                    "limits.max-region-volume",
                    maxRegionVolume);
            requireAtMost(
                    "limits.max-inspection-volume",
                    maxInspectionVolume,
                    "limits.max-region-volume",
                    maxRegionVolume);
            requireAtMost(
                    "limits.max-inspection-results",
                    maxInspectionResultLimit,
                    "limits.max-inspection-volume",
                    maxInspectionVolume);
            requireAtMost(
                    "limits.max-perspective-rays",
                    maxPerspectiveRays,
                    "limits.max-perspective-ray-distance-budget",
                    maxPerspectiveRayDistanceBudget);
        }
    }

    public record EditHistory(
            int maxEntriesPerWorld, int maxEntriesTotal, int maxRetainedChangedBlocks) {
        public EditHistory {
            requirePositive("edit-history.max-entries-per-world", maxEntriesPerWorld);
            requirePositive("edit-history.max-entries-total", maxEntriesTotal);
            requirePositive("edit-history.max-retained-changed-blocks", maxRetainedChangedBlocks);
            requireAtMost(
                    "edit-history.max-entries-per-world",
                    maxEntriesPerWorld,
                    "edit-history.max-entries-total",
                    maxEntriesTotal);
        }
    }

    private static void requirePositive(String path, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(path + " must be positive");
        }
    }

    private static void requireAtMost(String lowerPath, int lower, String upperPath, int upper) {
        if (lower > upper) {
            throw new IllegalArgumentException(lowerPath + " must not exceed " + upperPath);
        }
    }
}
