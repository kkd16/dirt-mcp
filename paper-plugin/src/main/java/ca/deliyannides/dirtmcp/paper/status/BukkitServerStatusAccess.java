package ca.deliyannides.dirtmcp.paper.status;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.Builds;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.EffectiveConfiguration;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.EffectiveEditHistory;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus.EffectiveLimits;
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
                    OperationFailure.UNHEALTHY,
                    "The Dirt MCP plugin is not enabled",
                    new ErrorDetails.Unhealthy.PluginDisabled());
        }
        requireFawe(server, OperationFailure.UNHEALTHY);
        List<World> worlds = server.getWorlds();
        if (worlds.isEmpty()) {
            throw new OperationException(
                    OperationFailure.UNHEALTHY,
                    "Paper has no loaded worlds",
                    new ErrorDetails.Unhealthy.NoLoadedWorlds());
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
    public GetServerStatus.Result captureStatus(GetServerStatus.Request request)
            throws OperationException {
        Objects.requireNonNull(request, "request");
        Server server = this.plugin.getServer();
        Plugin fawe = requireFawe(server, OperationFailure.SERVER_UNAVAILABLE);
        PlayerSummary players = null;
        if (request.includePlayers()) {
            List<OnlinePlayer> entries =
                    server.getOnlinePlayers().stream()
                            .map(BukkitServerStatusAccess::onlinePlayer)
                            .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                            .toList();
            players = new PlayerSummary(entries.size(), server.getMaxPlayers(), entries);
        }
        List<WorldStatus> worlds =
                request.includeWorlds()
                        ? server.getWorlds().stream()
                                .map(BukkitServerStatusAccess::worldStatus)
                                .toList()
                        : null;
        double[] tps = server.getTPS();
        return new GetServerStatus.Result(
                new Builds(
                        server.getMinecraftVersion(),
                        server.getVersion(),
                        this.plugin.getPluginMeta().getVersion(),
                        fawe.getPluginMeta().getVersion()),
                new Performance(tps[0], server.getAverageTickTime()),
                players,
                worlds,
                request.includeConfiguration() ? configuration() : null);
    }

    private EffectiveConfiguration configuration() {
        DirtConfig.Limits limits = this.config.limits();
        DirtConfig.EditHistory editHistory = this.config.editHistory();
        return new EffectiveConfiguration(
                new EffectiveLimits(
                        limits.maxRegionVolume(),
                        limits.maxEditTouchedChunks(),
                        limits.maxInspectionTouchedChunks(),
                        limits.maxPerspectiveTouchedChunks(),
                        limits.maxBlockStatePatterns(),
                        limits.maxPaletteEntries(),
                        limits.maxChangedBlocks(),
                        limits.maxInspectionVolume(),
                        limits.maxPerspectiveRayDistanceBudget(),
                        limits.maxInspectionResultLimit(),
                        limits.maxPerspectiveRays(),
                        limits.maxCommandsPerRequest(),
                        limits.maxCommandFeedbackCharacters()),
                new EffectiveEditHistory(
                        editHistory.maxEntriesPerWorld(),
                        editHistory.maxEntriesTotal(),
                        editHistory.maxRetainedChangedBlocks()));
    }

    private static Plugin requireFawe(Server server, OperationFailure failure)
            throws OperationException {
        Plugin fawe = server.getPluginManager().getPlugin(FAWE_PLUGIN_NAME);
        if (fawe == null || !fawe.isEnabled()) {
            ErrorDetails details =
                    switch (failure) {
                        case UNHEALTHY -> new ErrorDetails.Unhealthy.DependencyUnavailable();
                        case SERVER_UNAVAILABLE ->
                                new ErrorDetails.ServerUnavailable.DependencyUnavailable();
                        default ->
                                throw new IllegalArgumentException(
                                        "FAWE availability has an unsupported failure code");
                    };
            throw new OperationException(failure, "FastAsyncWorldEdit is not enabled", details);
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
