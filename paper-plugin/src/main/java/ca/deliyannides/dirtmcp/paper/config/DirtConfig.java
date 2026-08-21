package ca.deliyannides.dirtmcp.paper.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record DirtConfig(
        Bridge bridge,
        Tools tools,
        Logging logging,
        Limits limits,
        EditHistory editHistory,
        Defaults defaults) {
    private static final Set<String> REGION_BLOCKS_FORMATS = Set.of("blocks", "runs");

    public DirtConfig {
        if (bridge == null
                || tools == null
                || logging == null
                || limits == null
                || editHistory == null
                || defaults == null) {
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

    public record Tools(Set<McpTool> enabled) {
        public Tools {
            if (enabled == null) {
                throw new IllegalArgumentException("tools configuration is required");
            }
            enabled = Set.copyOf(enabled);
        }

        public boolean isEnabled(McpTool tool) {
            return this.enabled.contains(tool);
        }

        public Map<String, Boolean> flags() {
            Map<String, Boolean> flags = new LinkedHashMap<>();
            for (McpTool tool : McpTool.values()) {
                flags.put(tool.id(), isEnabled(tool));
            }
            return Collections.unmodifiableMap(flags);
        }
    }

    public record Bridge(
            int port,
            int shutdownDelaySeconds,
            int requestBodyTimeoutSeconds,
            int maxConcurrentRequests,
            int maxConcurrentInspections) {
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
        }
    }

    public record Limits(
            int maxRequestBytes,
            int maxRegionVolume,
            int maxTouchedChunks,
            int maxInspectionTouchedChunks,
            int maxBlockStatePatterns,
            int maxChangedBlocks,
            int maxInspectionVolume,
            int defaultInspectionResultLimit,
            int maxInspectionResultLimit,
            int maxCommandsPerRequest,
            int maxCommandFeedbackCharacters) {
        public Limits {
            if (maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "limits.max-request-bytes must be positive and less than "
                                + Integer.MAX_VALUE);
            }
            requirePositive("limits.max-region-volume", maxRegionVolume);
            requirePositive("limits.max-touched-chunks", maxTouchedChunks);
            requirePositive("limits.max-inspection-touched-chunks", maxInspectionTouchedChunks);
            requirePositive("limits.max-block-state-patterns", maxBlockStatePatterns);
            requirePositive("limits.max-changed-blocks", maxChangedBlocks);
            requirePositive("limits.max-inspection-volume", maxInspectionVolume);
            requirePositive("limits.default-inspection-results", defaultInspectionResultLimit);
            requirePositive("limits.max-inspection-results", maxInspectionResultLimit);
            requirePositive("limits.max-commands-per-request", maxCommandsPerRequest);
            requirePositive("limits.max-command-feedback-characters", maxCommandFeedbackCharacters);
            requireAtMost(
                    "limits.max-inspection-touched-chunks",
                    maxInspectionTouchedChunks,
                    "limits.max-touched-chunks",
                    maxTouchedChunks);
            requireAtMost(
                    "limits.max-block-state-patterns",
                    maxBlockStatePatterns,
                    "the protocol maximum",
                    64);
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
                    "limits.default-inspection-results",
                    defaultInspectionResultLimit,
                    "limits.max-inspection-results",
                    maxInspectionResultLimit);
            requireAtMost(
                    "limits.max-inspection-results",
                    maxInspectionResultLimit,
                    "limits.max-inspection-volume",
                    maxInspectionVolume);
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

    public record Defaults(
            boolean regionBlocksIncludeAir, String regionBlocksFormat, boolean editDryRun) {
        public Defaults {
            if (regionBlocksFormat == null || regionBlocksFormat.isBlank()) {
                throw new IllegalArgumentException(
                        "defaults.region-blocks-format must be a non-empty string");
            }
            regionBlocksFormat = regionBlocksFormat.toLowerCase(Locale.ROOT);
            if (!REGION_BLOCKS_FORMATS.contains(regionBlocksFormat)) {
                throw new IllegalArgumentException(
                        "defaults.region-blocks-format must be blocks or runs");
            }
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
