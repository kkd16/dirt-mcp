package ca.deliyannides.dirtmcp.paper;

import ca.deliyannides.dirtmcp.paper.api.ApiServer;
import java.io.IOException;
import java.util.Locale;
import org.bukkit.plugin.java.JavaPlugin;

public final class DirtMcpPlugin extends JavaPlugin {
    private static final String ENABLED_ENVIRONMENT_VARIABLE = "DIRT_MCP_BRIDGE_ENABLED";
    private static final String PORT_ENVIRONMENT_VARIABLE = "DIRT_MCP_BRIDGE_PORT";

    private ApiServer apiServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!bridgeEnabled()) {
            getLogger().info("Dirt MCP enabled; the local bridge is disabled.");
            return;
        }

        int port = bridgePort();
        this.apiServer = new ApiServer(
                port,
                getPluginMeta().getVersion(),
                getServer().getMinecraftVersion(),
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

    private boolean bridgeEnabled() {
        String override = System.getenv(ENABLED_ENVIRONMENT_VARIABLE);
        if (override == null || override.isBlank()) {
            return getConfig().getBoolean("bridge.enabled", false);
        }

        return switch (override.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw new IllegalArgumentException(
                    ENABLED_ENVIRONMENT_VARIABLE + " must be true/false, yes/no, on/off, or 1/0");
        };
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
}
