package ca.deliyannides.dirtmcp.paper.config;

import java.util.Locale;
import java.util.Set;

public record DirtConfig(Bridge bridge, Limits limits, Defaults defaults) {
    private static final Set<String> REGION_BLOCKS_FORMATS = Set.of("blocks", "runs");

    public DirtConfig {
        if (bridge == null || limits == null || defaults == null) {
            throw new IllegalArgumentException("Configuration sections are required");
        }
    }

    public record Bridge(
            int port,
            int backlog,
            int shutdownDelaySeconds,
            int minimumTokenBytes,
            int maxConcurrentRequests) {
        public Bridge {
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("bridge.port must be between 1 and 65535");
            }
            if (backlog < 0) {
                throw new IllegalArgumentException("bridge.backlog must be non-negative");
            }
            if (shutdownDelaySeconds < 0) {
                throw new IllegalArgumentException(
                        "bridge.shutdown-delay-seconds must be non-negative");
            }
            if (minimumTokenBytes < 1) {
                throw new IllegalArgumentException("bridge.minimum-token-bytes must be positive");
            }
            if (maxConcurrentRequests < 1) {
                throw new IllegalArgumentException(
                        "bridge.max-concurrent-requests must be positive");
            }
        }
    }

    public record Limits(
            int maxRequestBytes,
            int maxRegionVolume,
            int maxTouchedChunks,
            int maxChangedBlocks,
            int maxInspectionVolume,
            int defaultInspectionResultLimit,
            int maxInspectionResultLimit,
            int maxCommandsPerRequest,
            int maxCommandFeedbackCharacters,
            int undoHistoryPerWorld) {
        public Limits {
            if (maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "limits.max-request-bytes must be positive and less than "
                                + Integer.MAX_VALUE);
            }
            requirePositive("limits.max-region-volume", maxRegionVolume);
            requirePositive("limits.max-touched-chunks", maxTouchedChunks);
            requirePositive("limits.max-changed-blocks", maxChangedBlocks);
            requirePositive("limits.max-inspection-volume", maxInspectionVolume);
            requirePositive("limits.default-inspection-results", defaultInspectionResultLimit);
            requirePositive("limits.max-inspection-results", maxInspectionResultLimit);
            requirePositive("limits.max-commands-per-request", maxCommandsPerRequest);
            requirePositive("limits.max-command-feedback-characters", maxCommandFeedbackCharacters);
            if (undoHistoryPerWorld < 0) {
                throw new IllegalArgumentException(
                        "limits.undo-history-per-world must be non-negative");
            }
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
