package ca.deliyannides.dirtmcp.paper;

import ca.deliyannides.dirtmcp.paper.api.ApiServer;
import ca.deliyannides.dirtmcp.paper.world.FaweRegionEditor;
import ca.deliyannides.dirtmcp.paper.world.PaperRegionInspector;
import java.io.IOException;
import org.bukkit.plugin.java.JavaPlugin;

public final class DirtMcpPlugin extends JavaPlugin {
    private static final String PORT_ENVIRONMENT_VARIABLE = "DIRT_MCP_BRIDGE_PORT";
    private static final String TOKEN_ENVIRONMENT_VARIABLE = "DIRT_MCP_BRIDGE_TOKEN";

    private ApiServer apiServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        int port = bridgePort();
        this.apiServer = new ApiServer(
                port,
                getPluginMeta().getVersion(),
                getServer().getMinecraftVersion(),
                bridgeToken(),
                new PaperRegionInspector(this, maxRegionVolume()),
                new FaweRegionEditor(this, maxRegionVolume(), maxChangedBlocks(), maxUndoEntries()),
                getLogger());

        try {
            this.apiServer.start();
        } catch (IOException exception) {
            this.apiServer = null;
            throw new IllegalStateException("Could not start the Dirt MCP bridge on 127.0.0.1:" + port, exception);
        }

        getLogger().info("Dirt MCP bridge listening on http://127.0.0.1:" + port);
    }

    @Override
    public void onDisable() {
        if (this.apiServer != null) {
            this.apiServer.close();
            this.apiServer = null;
        }
    }

    private int bridgePort() {
        String override = System.getenv(PORT_ENVIRONMENT_VARIABLE);
        int port;

        try {
            port = override == null || override.isBlank()
                    ? getConfig().getInt("bridge.port", 8765)
                    : Integer.parseInt(override.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(PORT_ENVIRONMENT_VARIABLE + " must be an integer", exception);
        }

        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Dirt MCP bridge port must be between 1 and 65535");
        }

        return port;
    }

    private static String bridgeToken() {
        String token = System.getenv(TOKEN_ENVIRONMENT_VARIABLE);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(TOKEN_ENVIRONMENT_VARIABLE + " is required");
        }
        return token;
    }

    private long maxRegionVolume() {
        long maximum = getConfig().getLong("limits.max-region-volume", 1_000_000L);
        if (maximum < 1) {
            throw new IllegalArgumentException("limits.max-region-volume must be positive");
        }
        return maximum;
    }

    private int maxChangedBlocks() {
        int maximum = getConfig().getInt("limits.max-changed-blocks", 250_000);
        if (maximum < 1) {
            throw new IllegalArgumentException("limits.max-changed-blocks must be positive");
        }
        return maximum;
    }

    private int maxUndoEntries() {
        int maximum = getConfig().getInt("undo.max-entries-per-world", 20);
        if (maximum < 1) {
            throw new IllegalArgumentException("undo.max-entries-per-world must be positive");
        }
        return maximum;
    }
}
