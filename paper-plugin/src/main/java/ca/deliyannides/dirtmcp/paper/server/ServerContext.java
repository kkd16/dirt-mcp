package ca.deliyannides.dirtmcp.paper.server;

import ca.deliyannides.dirtmcp.paper.PluginSettings.Defaults;
import ca.deliyannides.dirtmcp.paper.PluginSettings.Limits;
import java.util.List;

public interface ServerContext {
    PingResult ping() throws ServerContextException;

    ServerStatus getStatus() throws ServerContextException;

    record PingResult(String status) {}

    record ServerStatus(
            Builds builds,
            Performance performance,
            PlayerSummary players,
            List<WorldStatus> worlds,
            Limits limits,
            Defaults defaults) {}

    record Builds(String minecraft, String paper, String dirtMcp, String fawe) {}

    record Performance(double tpsOneMinute, double averageTickTimeMillis) {}

    record PlayerSummary(int online, int maximum, List<OnlinePlayer> entries) {}

    record OnlinePlayer(String name, String world, String gameMode, BlockPosition blockPosition) {}

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

    record BlockPosition(int x, int y, int z) {}

    final class ServerContextException extends Exception {
        private static final long serialVersionUID = 1L;

        public ServerContextException(String message, Throwable cause) {
            super(message, cause);
        }

        public ServerContextException(String message) {
            super(message);
        }
    }
}
