package ca.deliyannides.dirtmcp.paper.status;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.List;

@FunctionalInterface
public interface GetServerStatus {
    Result getStatus() throws OperationException;

    record Result(
            Builds builds,
            Performance performance,
            PlayerSummary players,
            List<WorldStatus> worlds,
            EffectiveLimits limits,
            EffectiveEditHistory editHistory,
            EffectiveDefaults defaults) {
        public Result {
            worlds = List.copyOf(worlds);
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
            int maxRequestBytes,
            int maxRegionVolume,
            int maxTouchedChunks,
            int maxInspectionTouchedChunks,
            int maxBlockStatePatterns,
            int maxChangedBlocks,
            int maxInspectionVolume,
            int defaultInspectionResultLimit,
            int maxInspectionResultLimit) {}

    record EffectiveEditHistory(
            int maxEntriesPerWorld, int maxEntriesTotal, int maxRetainedChangedBlocks) {}

    record EffectiveDefaults(
            boolean regionBlocksIncludeAir, String regionBlocksFormat, boolean editDryRun) {}
}
