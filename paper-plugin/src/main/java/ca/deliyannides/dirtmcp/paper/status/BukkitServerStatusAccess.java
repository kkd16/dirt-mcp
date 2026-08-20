package ca.deliyannides.dirtmcp.paper.status;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.Builds;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.EffectiveDefaults;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.EffectiveEditHistory;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.EffectiveLimits;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.EffectiveLogging;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.OnlinePlayer;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.Performance;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.PlayerSummary;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.WorldStatus;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Capability;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitServerStatusAccess implements PaperServerStatusService.PaperStatusAccess {
    private static final String FAWE_PLUGIN_NAME = "FastAsyncWorldEdit";

    private final JavaPlugin plugin;
    private final DirtConfig config;

    public BukkitServerStatusAccess(JavaPlugin plugin, DirtConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Runnable prepareHealthCheck() throws OperationException {
        Server server = this.plugin.getServer();
        if (!this.plugin.isEnabled()) {
            throw new OperationException(
                    OperationFailure.UNHEALTHY, "The Dirt MCP plugin is not enabled");
        }
        requireFawe(server, OperationFailure.UNHEALTHY);
        List<World> worlds = server.getWorlds();
        if (worlds.isEmpty()) {
            throw new OperationException(OperationFailure.UNHEALTHY, "Paper has no loaded worlds");
        }
        com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(worlds.getFirst());
        return () -> verifyFawe(world);
    }

    private static void verifyFawe(com.sk89q.worldedit.world.World world) {
        WorldEdit worldEdit = WorldEdit.getInstance();
        worldEdit.getPlatformManager().queryCapability(Capability.WORLD_EDITING);
        try (EditSession session =
                worldEdit
                        .newEditSessionBuilder()
                        .world(world)
                        .maxBlocks(1)
                        .allowedRegionsEverywhere()
                        .fastMode(true)
                        .changeSetNull()
                        .build()) {
            if (session.getWorld() == null) {
                throw new IllegalStateException("FAWE session has no Paper world");
            }
        }
    }

    @Override
    public GetServerStatus.Result captureStatus() throws OperationException {
        Server server = this.plugin.getServer();
        Plugin fawe = requireFawe(server, OperationFailure.SERVER_UNAVAILABLE);
        List<OnlinePlayer> players =
                server.getOnlinePlayers().stream()
                        .map(BukkitServerStatusAccess::onlinePlayer)
                        .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                        .toList();
        List<WorldStatus> worlds =
                server.getWorlds().stream().map(BukkitServerStatusAccess::worldStatus).toList();
        double[] tps = server.getTPS();
        DirtConfig.Limits limits = this.config.limits();
        DirtConfig.EditHistory editHistory = this.config.editHistory();
        DirtConfig.Defaults defaults = this.config.defaults();
        return new GetServerStatus.Result(
                new Builds(
                        server.getMinecraftVersion(),
                        server.getVersion(),
                        this.plugin.getPluginMeta().getVersion(),
                        fawe.getPluginMeta().getVersion()),
                new Performance(tps[0], server.getAverageTickTime()),
                new PlayerSummary(players.size(), server.getMaxPlayers(), players),
                worlds,
                this.config.tools().flags(),
                new EffectiveLogging(
                        this.config.logging().consoleLevel().configName(),
                        this.config.logging().detailFileMaxBytes(),
                        this.config.logging().detailFileRetainedFiles()),
                new EffectiveLimits(
                        limits.maxRequestBytes(),
                        limits.maxRegionVolume(),
                        limits.maxTouchedChunks(),
                        limits.maxInspectionTouchedChunks(),
                        limits.maxBlockStatePatterns(),
                        limits.maxChangedBlocks(),
                        limits.maxInspectionVolume(),
                        limits.defaultInspectionResultLimit(),
                        limits.maxInspectionResultLimit()),
                new EffectiveEditHistory(
                        editHistory.maxEntriesPerWorld(),
                        editHistory.maxEntriesTotal(),
                        editHistory.maxRetainedChangedBlocks()),
                new EffectiveDefaults(
                        defaults.regionBlocksIncludeAir(),
                        defaults.regionBlocksFormat(),
                        defaults.editDryRun()));
    }

    private static Plugin requireFawe(Server server, OperationFailure failure)
            throws OperationException {
        Plugin fawe = server.getPluginManager().getPlugin(FAWE_PLUGIN_NAME);
        if (fawe == null || !fawe.isEnabled()) {
            throw new OperationException(failure, "FastAsyncWorldEdit is not enabled");
        }
        return fawe;
    }

    static OnlinePlayer onlinePlayer(Player player) {
        return new OnlinePlayer(
                player.getName(),
                player.getWorld().getName(),
                player.getGameMode().name().toLowerCase(Locale.ROOT),
                player.getFacing().name().toLowerCase(Locale.ROOT),
                blockPosition(player.getLocation()));
    }

    private static WorldStatus worldStatus(World world) {
        return new WorldStatus(
                world.getName(),
                world.getEnvironment().name().toLowerCase(Locale.ROOT),
                world.getMinHeight(),
                world.getMaxHeight() - 1,
                blockPosition(world.getSpawnLocation()),
                world.getTime(),
                world.hasStorm(),
                world.isThundering(),
                world.getPlayers().size());
    }

    private static BlockPosition blockPosition(Location location) {
        return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
