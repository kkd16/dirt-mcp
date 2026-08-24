package ca.deliyannides.dirtmcp.paper.status;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface GetServerStatus {
    Result getStatus(Request request) throws OperationException;

    record Request(boolean includePlayers, boolean includeWorlds, boolean includeConfiguration) {}

    record Result(
            Builds builds,
            Performance performance,
            PlayerSummary players,
            List<WorldStatus> worlds,
            EffectiveConfiguration configuration) {
        public Result {
            Objects.requireNonNull(builds, "builds");
            Objects.requireNonNull(performance, "performance");
            if (worlds != null) {
                worlds = List.copyOf(worlds);
            }
        }
    }

    record Builds(String minecraft, String paper, String dirtPlugin, String fawe) {}

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

    record EffectiveConfiguration(EffectiveLimits limits, EffectiveEditHistory editHistory) {
        public EffectiveConfiguration {
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(editHistory, "editHistory");
        }
    }

    record EffectiveLimits(
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
            int maxCommandFeedbackCharacters) {}

    record EffectiveEditHistory(
            int maxEntriesPerWorld, int maxEntriesTotal, int maxRetainedChangedBlocks) {}
}
