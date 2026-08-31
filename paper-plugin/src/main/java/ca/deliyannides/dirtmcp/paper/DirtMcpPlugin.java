package ca.deliyannides.dirtmcp.paper;

import ca.deliyannides.dirtmcp.paper.bootstrap.DirtRuntime;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.config.DirtConfigLoader;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import java.io.IOException;
import java.util.regex.Pattern;
import org.bukkit.plugin.java.JavaPlugin;

public final class DirtMcpPlugin extends JavaPlugin {
    private static final String PORT_ENVIRONMENT_VARIABLE = "DIRT_BRIDGE_PORT";
    private static final String BRIDGE_TOKEN_ENVIRONMENT_VARIABLE = "DIRT_BRIDGE_TOKEN";
    private static final String CONTROL_TOKEN_ENVIRONMENT_VARIABLE = "DIRT_CONTROL_TOKEN";
    private static final String CONTROL_URL_ENVIRONMENT_VARIABLE = "DIRT_CONTROL_URL";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private DirtRuntime runtime;

    @Override
    public void onEnable() {
        requireOnlineMode(getServer().getOnlineMode());
        DirtConfig config = loadConfig();
        DirtLog log = DirtLog.open(this, config.logging());

        try {
            ControlCredentials credentials = controlCredentials();
            this.runtime =
                    DirtRuntime.start(
                            this,
                            config,
                            credentials.bridgeToken(),
                            credentials.controlToken(),
                            log);
        } catch (IOException exception) {
            reportStartupFailure(
                    log, config, "Dirt MCP could not start its loopback bridge", exception);
            closeAfterStartupFailure(log, exception);
            throw new IllegalStateException(
                    "Could not start the Dirt MCP bridge on 127.0.0.1:" + config.bridge().port(),
                    exception);
        } catch (RuntimeException | Error failure) {
            reportStartupFailure(log, config, "Dirt MCP could not start", failure);
            closeAfterStartupFailure(log, failure);
            throw failure;
        }
    }

    private static void reportStartupFailure(
            DirtLog log, DirtConfig config, String message, Throwable failure) {
        try {
            LogContext context = LogContext.of("port", config.bridge().port());
            log.error("runtime", "runtime.start_failed", message, context, failure);
        } catch (RuntimeException | Error loggingFailure) {
            failure.addSuppressed(loggingFailure);
        }
    }

    private static void closeAfterStartupFailure(DirtLog log, Throwable failure) {
        try {
            log.close();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private DirtConfig loadConfig() {
        DirtLog bootstrapLog =
                DirtLog.consoleOnly(getSLF4JLogger(), DirtConfig.ConsoleLogLevel.ERROR);
        try {
            saveDefaultConfig();
            DirtConfig config =
                    DirtConfigLoader.load(
                            getConfig(),
                            System.getenv(PORT_ENVIRONMENT_VARIABLE),
                            System.getenv(CONTROL_URL_ENVIRONMENT_VARIABLE));
            bootstrapLog.close();
            return config;
        } catch (RuntimeException | Error failure) {
            try {
                String detail = failure.getMessage() == null ? "" : ": " + failure.getMessage();
                String message = "Dirt MCP configuration could not be loaded" + detail;
                LogContext context = LogContext.empty();
                bootstrapLog.error(
                        "runtime", "runtime.configuration_failed", message, context, failure);
            } catch (RuntimeException | Error loggingFailure) {
                failure.addSuppressed(loggingFailure);
            }
            closeAfterStartupFailure(bootstrapLog, failure);
            throw failure;
        }
    }

    @Override
    public void onDisable() {
        DirtRuntime running = this.runtime;
        this.runtime = null;
        if (running != null) {
            running.close();
        }
    }

    static void requireOnlineMode(boolean onlineMode) {
        if (!onlineMode) {
            throw new IllegalStateException(
                    "Dirt MCP requires server.properties online-mode=true for secure account "
                            + "linking");
        }
    }

    static ControlCredentials validateControlCredentials(String bridgeToken, String controlToken) {
        requireToken(BRIDGE_TOKEN_ENVIRONMENT_VARIABLE, bridgeToken);
        requireToken(CONTROL_TOKEN_ENVIRONMENT_VARIABLE, controlToken);
        if (bridgeToken.equals(controlToken)) {
            throw new IllegalStateException(
                    "DIRT_BRIDGE_TOKEN and DIRT_CONTROL_TOKEN must be distinct");
        }
        return new ControlCredentials(bridgeToken, controlToken);
    }

    private static ControlCredentials controlCredentials() {
        return validateControlCredentials(
                System.getenv(BRIDGE_TOKEN_ENVIRONMENT_VARIABLE),
                System.getenv(CONTROL_TOKEN_ENVIRONMENT_VARIABLE));
    }

    private static void requireToken(String name, String token) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            throw new IllegalStateException(
                    name + " must contain exactly 64 lowercase hexadecimal characters");
        }
    }

    record ControlCredentials(String bridgeToken, String controlToken) {
        @Override
        public String toString() {
            return "ControlCredentials[redacted]";
        }
    }
}
