package ca.deliyannides.dirtmcp.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeOperation;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

final class DirtAdminCommandTest {
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    @Test
    void rootAndHelpRenderOnlyPaperOwnedCommands() throws Exception {
        var fixture = fixture(true, status());

        assertEquals(1, fixture.execute("dirt"));
        assertEquals(1, fixture.execute("dirt help"));

        for (Component message : fixture.messages) {
            String plain = PLAIN.serialize(message);
            assertTrue(plain.contains("/dirt status"));
            assertTrue(plain.contains("/dirt config"));
            assertTrue(plain.contains("/dirt version"));
        }
    }

    @Test
    void statusRequestsOnlyTheSectionsItRenders() throws Exception {
        AtomicReference<GetServerStatus.Request> captured = new AtomicReference<>();
        GetServerStatus delegate = status();
        var fixture =
                fixture(
                        true,
                        request -> {
                            captured.set(request);
                            return delegate.getStatus(request);
                        });

        assertEquals(1, fixture.execute("dirt status"));

        assertEquals(new GetServerStatus.Request(true, true, false), captured.get());
        String plain = PLAIN.serialize(fixture.messages.getFirst());
        assertTrue(plain.contains("Bridge  127.0.0.1:8765"));
        assertTrue(plain.contains("Dirt MCP  0.1.0-SNAPSHOT"));
        assertTrue(plain.contains("Players  1 / 20 online"));
        assertTrue(plain.contains("Worlds  1 loaded"));
    }

    @Test
    void statusReportsExpectedOperationalFailureWithoutThrowing() throws Exception {
        OperationException expected =
                new OperationException(
                        OperationFailure.SERVER_UNAVAILABLE,
                        "FAWE is not available",
                        new ErrorDetails.ServerUnavailable.DependencyUnavailable());
        var fixture =
                fixture(
                        true,
                        request -> {
                            throw expected;
                        });

        assertEquals(0, fixture.execute("dirt status"));
        assertTrue(PLAIN.serialize(fixture.messages.getFirst()).contains("FAWE is not available"));
    }

    @Test
    void configRendersBridgeOperationsAndPaperSafetyLimits() throws Exception {
        var fixture = fixture(true, status());

        assertEquals(1, fixture.execute("dirt config"));

        String plain = PLAIN.serialize(fixture.messages.getFirst());
        assertTrue(plain.contains("max-request-bytes  262144"));
        assertTrue(plain.contains("allowed-operations  [pingServer, setBlocks]"));
        assertTrue(plain.contains("max-edit-touched-chunks  128"));
        assertTrue(plain.contains("max-inspection-touched-chunks  16"));
        assertTrue(plain.contains("max-inspection-results  1024"));
    }

    @Test
    void permissionControlsVisibilityAndSuggestions() throws Exception {
        var allowed = fixture(true, status());
        var denied = fixture(false, status());

        assertEquals(Set.of("config", "help", "status", "version"), allowed.suggestions("dirt "));
        assertFalse(denied.canUseRoot());
        assertThrows(CommandSyntaxException.class, () -> denied.execute("dirt"));
    }

    @Test
    void pluginMetadataDescribesThePaperAdminSurface() {
        YamlConfiguration metadata = pluginMetadata();

        assertEquals(
                "op",
                metadata.getString("permissions." + DirtAdminCommand.PERMISSION + ".default"));
        assertEquals(
                "View Dirt MCP status and active Paper bridge configuration",
                metadata.getString("permissions." + DirtAdminCommand.PERMISSION + ".description"));
    }

    private static CommandFixture fixture(boolean allowed, GetServerStatus status) {
        DirtLog log = DirtLog.consoleOnly(NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR);
        return new CommandFixture(
                allowed, new DirtAdminCommand("DirtMCP", "0.1.0-SNAPSHOT", config(), status, log));
    }

    private static DirtConfig config() {
        return new DirtConfig(
                new DirtConfig.Bridge(
                        8_765,
                        5,
                        6,
                        30,
                        2,
                        262_144,
                        List.of(BridgeOperation.PING_SERVER, BridgeOperation.SET_BLOCKS)),
                new DirtConfig.Logging(DirtConfig.ConsoleLogLevel.WARNING, 2_000_000, 7),
                new DirtConfig.Limits(
                        131_072, 128, 16, 32, 32, 64, 65_536, 8_192, 16_384, 1_024, 512, 10, 8_192),
                new DirtConfig.EditHistory(10, 50, 655_360));
    }

    private static GetServerStatus status() {
        GetServerStatus.Result result =
                new GetServerStatus.Result(
                        new GetServerStatus.Builds(
                                "26.2", "Paper build 116", "0.1.0-SNAPSHOT", "2.15.4"),
                        new GetServerStatus.Performance(19.95, 4.25),
                        new GetServerStatus.PlayerSummary(
                                1,
                                20,
                                List.of(
                                        new GetServerStatus.OnlinePlayer(
                                                "Builder",
                                                "world",
                                                "creative",
                                                "north",
                                                new BlockPosition(1, 64, 2)))),
                        List.of(
                                new GetServerStatus.WorldStatus(
                                        "world",
                                        "normal",
                                        -64,
                                        319,
                                        new BlockPosition(0, 64, 0),
                                        6_000,
                                        false,
                                        false,
                                        1)),
                        null);
        return request -> result;
    }

    private static YamlConfiguration pluginMetadata() {
        var stream = DirtAdminCommandTest.class.getResourceAsStream("/plugin.yml");
        if (stream == null) {
            throw new IllegalStateException("Packaged plugin.yml is unavailable");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read packaged plugin.yml", exception);
        }
    }

    private static final class CommandFixture {
        private final List<Component> messages = new ArrayList<>();
        private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        private final CommandSourceStack source;

        private CommandFixture(boolean allowed, DirtAdminCommand command) {
            this.source = new TestSource(sender(allowed, this.messages));
            this.dispatcher.getRoot().addChild(command.command());
        }

        private int execute(String input) throws CommandSyntaxException {
            return this.dispatcher.execute(input, this.source);
        }

        private Set<String> suggestions(String input) {
            return this.dispatcher
                    .getCompletionSuggestions(this.dispatcher.parse(input, this.source))
                    .join()
                    .getList()
                    .stream()
                    .map(suggestion -> suggestion.getText())
                    .collect(HashSet::new, Set::add, Set::addAll);
        }

        private boolean canUseRoot() {
            return this.dispatcher.getRoot().getChild("dirt").canUse(this.source);
        }
    }

    private record TestSource(CommandSender sender) implements CommandSourceStack {
        @Override
        public Location getLocation() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandSender getSender() {
            return this.sender;
        }

        @Override
        public Entity getExecutor() {
            return null;
        }

        @Override
        public Player getPlayerOrThrow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entity getEntityOrThrow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandSourceStack withLocation(Location location) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandSourceStack withExecutor(Entity executor) {
            throw new UnsupportedOperationException();
        }
    }

    private static CommandSender sender(boolean allowed, List<Component> messages) {
        return (CommandSender)
                Proxy.newProxyInstance(
                        DirtAdminCommandTest.class.getClassLoader(),
                        new Class<?>[] {CommandSender.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("hasPermission")) {
                                return allowed;
                            }
                            if (method.getName().equals("sendMessage")
                                    && arguments != null
                                    && arguments.length == 1
                                    && arguments[0] instanceof Component component) {
                                messages.add(component);
                                return null;
                            }
                            if (method.getName().equals("getName")) {
                                return "Test sender";
                            }
                            if (method.getName().equals("isOp")) {
                                return allowed;
                            }
                            if (method.getName().equals("toString")) {
                                return "TestCommandSender";
                            }
                            if (method.getReturnType().equals(boolean.class)) {
                                return false;
                            }
                            if (method.getReturnType().equals(int.class)) {
                                return 0;
                            }
                            return null;
                        });
    }
}
