package ca.deliyannides.dirtmcp.paper;

import ca.deliyannides.dirtmcp.paper.bootstrap.DirtRuntime;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.config.DirtConfigLoader;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.validation.ServiceToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.plugin.java.JavaPlugin;

public final class DirtMcpPlugin extends JavaPlugin {
    private static final Path BRIDGE_TOKEN_FILE = Path.of("secrets", "bridge-token");
    private static final Path CONTROL_TOKEN_FILE = Path.of("secrets", "control-token");
    private static final String BRIDGE_TOKEN_NAME = "bridge token file";
    private static final String CONTROL_TOKEN_NAME = "control token file";

    private DirtRuntime runtime;

    @Override
    public void onEnable() {
        requireOnlineMode(getServer().getOnlineMode());
        DirtConfig config = loadConfig();
        DirtLog log = DirtLog.open(this, config.logging());

        try {
            ControlCredentials credentials = readControlCredentials(getDataFolder().toPath());
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
            DirtConfig config = DirtConfigLoader.load(getConfig());
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
        requireToken(BRIDGE_TOKEN_NAME, bridgeToken);
        requireToken(CONTROL_TOKEN_NAME, controlToken);
        if (bridgeToken.equals(controlToken)) {
            throw new IllegalStateException(
                    "Bridge and control token file contents must be distinct");
        }
        return new ControlCredentials(bridgeToken, controlToken);
    }

    static ControlCredentials readControlCredentials(Path dataDirectory) {
        return validateControlCredentials(
                readTokenFile(BRIDGE_TOKEN_NAME, dataDirectory.resolve(BRIDGE_TOKEN_FILE)),
                readTokenFile(CONTROL_TOKEN_NAME, dataDirectory.resolve(CONTROL_TOKEN_FILE)));
    }

    private static String readTokenFile(String name, Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(
                        name + " must reference a readable UTF-8 regular file");
            }
            return stripFinalLineEnding(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException(
                    name + " must reference a readable UTF-8 regular file", exception);
        }
    }

    private static String stripFinalLineEnding(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static void requireToken(String name, String token) {
        if (!ServiceToken.isValid(token)) {
            throw new IllegalStateException(
                    name + " contents must contain exactly 64 lowercase hexadecimal characters");
        }
    }

    record ControlCredentials(String bridgeToken, String controlToken) {
        @Override
        public String toString() {
            return "ControlCredentials[redacted]";
        }
    }
}
