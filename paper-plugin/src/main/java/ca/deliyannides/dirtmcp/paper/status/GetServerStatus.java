package ca.deliyannides.dirtmcp.paper.status;

import ca.deliyannides.dirtmcp.paper.config.McpTool;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface GetServerStatus {
    Result getStatus() throws OperationException;

    record Result(
            Builds builds,
            Performance performance,
            PlayerSummary players,
            List<WorldStatus> worlds,
            Map<String, Boolean> tools,
            EffectiveLogging logging,
            EffectiveLimits limits,
            EffectiveEditHistory editHistory,
            EffectiveDefaults defaults) {
        public Result {
            worlds = List.copyOf(worlds);
            tools = validateTools(tools);
        }

        private static Map<String, Boolean> validateTools(Map<String, Boolean> tools) {
            if (tools == null || tools.size() != McpTool.values().length) {
                throw new IllegalArgumentException(
                        "tools must contain every canonical MCP tool flag");
            }
            for (McpTool tool : McpTool.values()) {
                if (tools.get(tool.id()) == null) {
                    throw new IllegalArgumentException(
                            "tools must contain every canonical MCP tool flag");
                }
            }
            return Map.copyOf(tools);
        }
    }

    record Builds(String minecraft, String paper, String dirtMcp, String fawe) {}

    record Performance(double tpsOneMinute, double averageTickTimeMillis) {}

    record PlayerSummary(int online, int maximum, List<OnlinePlayer> entries) {
        public PlayerSummary {
            entries = List.copyOf(entries);
        }
    }

    record OnlinePlayer(
            String name,
            String world,
            String gameMode,
            String facing,
            BlockPosition blockPosition) {}

    record WorldStatus(
            String name,
            String environment,
            int minY,
            int maxY,
            BlockPosition spawn,
            long timeOfDay,
            boolean storm,
            boolean thundering,
            int playerCount) {}

    record EffectiveLimits(
            int maxConcurrentRequests,
            int maxConcurrentInspections,
            int maxRequestBytes,
            int maxRegionVolume,
            int maxTouchedChunks,
            int maxInspectionTouchedChunks,
            int maxPerspectiveTouchedChunks,
            int maxBlockStatePatterns,
            int maxPaletteEntries,
            int maxChangedBlocks,
            int maxInspectionVolume,
            int maxPerspectiveRayDistanceBudget,
            int defaultInspectionResultLimit,
            int maxInspectionResultLimit,
            int maxPerspectiveRays,
            int maxCommandsPerRequest,
            int maxCommandFeedbackCharacters) {}

    record EffectiveLogging(
            String consoleLevel, int detailFileMaxBytes, int detailFileRetainedFiles) {}

    record EffectiveEditHistory(
            int maxEntriesPerWorld, int maxEntriesTotal, int maxRetainedChangedBlocks) {}

    record EffectiveDefaults(boolean getBlocksIncludeAir, boolean editDryRun) {}
}
