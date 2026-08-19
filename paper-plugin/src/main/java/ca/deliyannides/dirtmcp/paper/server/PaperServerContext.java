package ca.deliyannides.dirtmcp.paper.server;

import ca.deliyannides.dirtmcp.paper.PluginSettings;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.BlockPosition;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.Builds;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.OnlinePlayer;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.Performance;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.PingResult;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.PlayerSummary;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.ServerContextException;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.ServerStatus;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.WorldStatus;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Capability;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperServerContext implements ServerContext {
    private static final String FAWE_PLUGIN_NAME = "FastAsyncWorldEdit";

    private final JavaPlugin plugin;
    private final PluginSettings settings;

    public PaperServerContext(JavaPlugin plugin, PluginSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public PingResult ping() throws ServerContextException {
        com.sk89q.worldedit.world.World world = onMainThread(this::prepareHealthCheck);
        try {
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
                // Opening a Paper-backed FAWE session validates the integration without mutating
                // the world.
                if (session.getWorld() == null) {
                    throw new IllegalStateException("FAWE session has no Paper world");
                }
            }
            return new PingResult("ok");
        } catch (RuntimeException exception) {
            throw new ServerContextException(
                    "Dirt MCP could not open a Paper-backed FAWE session", exception);
        }
    }

    @Override
    public ServerStatus getStatus() throws ServerContextException {
        return onMainThread(this::captureStatus);
    }

    private com.sk89q.worldedit.world.World prepareHealthCheck() throws ServerContextException {
        Server server = this.plugin.getServer();
        if (!this.plugin.isEnabled()) {
            throw new ServerContextException("The Dirt MCP plugin is not enabled");
        }
        Plugin fawe = server.getPluginManager().getPlugin(FAWE_PLUGIN_NAME);
        if (fawe == null || !fawe.isEnabled()) {
            throw new ServerContextException("FastAsyncWorldEdit is not enabled");
        }
        List<World> worlds = server.getWorlds();
        if (worlds.isEmpty()) {
            throw new ServerContextException("Paper has no loaded worlds");
        }
        return BukkitAdapter.adapt(worlds.getFirst());
    }

    private ServerStatus captureStatus() throws ServerContextException {
        Server server = this.plugin.getServer();
        Plugin fawe = server.getPluginManager().getPlugin(FAWE_PLUGIN_NAME);
        if (fawe == null || !fawe.isEnabled()) {
            throw new ServerContextException("FastAsyncWorldEdit is not enabled");
        }

        List<OnlinePlayer> players =
                server.getOnlinePlayers().stream()
                        .map(PaperServerContext::onlinePlayer)
                        .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                        .toList();
        List<WorldStatus> worlds =
                server.getWorlds().stream().map(PaperServerContext::worldStatus).toList();
        double[] tps = server.getTPS();
        return new ServerStatus(
                new Builds(
                        server.getMinecraftVersion(),
                        server.getVersion(),
                        this.plugin.getPluginMeta().getVersion(),
                        fawe.getPluginMeta().getVersion()),
                new Performance(tps[0], server.getAverageTickTime()),
                new PlayerSummary(players.size(), server.getMaxPlayers(), players),
                worlds,
                this.settings.limits(),
                this.settings.defaults());
    }

    private static OnlinePlayer onlinePlayer(Player player) {
        Location location = player.getLocation();
        return new OnlinePlayer(
                player.getName(),
                player.getWorld().getName(),
                player.getGameMode().name().toLowerCase(Locale.ROOT),
                blockPosition(location));
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

    private <T> T onMainThread(CheckedSupplier<T> action) throws ServerContextException {
        Future<T> result =
                this.plugin.getServer().getScheduler().callSyncMethod(this.plugin, action::get);
        try {
            return result.get();
        } catch (InterruptedException exception) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw new ServerContextException("Server context request was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ServerContextException contextException) {
                throw contextException;
            }
            throw new ServerContextException(
                    "Could not read Paper server context", exception.getCause());
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws ServerContextException;
    }
}
