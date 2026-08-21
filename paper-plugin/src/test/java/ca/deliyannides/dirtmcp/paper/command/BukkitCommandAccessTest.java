package ca.deliyannides.dirtmcp.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class BukkitCommandAccessTest {
    @Test
    void preservesOrderAndDuplicatesButStopsDispatchAfterANotFoundResult() throws Exception {
        ScriptedServer scripted = new ScriptedServer();
        BukkitCommandAccess access = new BukkitCommandAccess(scripted.proxy(), () -> true);

        RunMinecraftCommands.Result result =
                access.dispatch(List.of("first", "first", "missing", "broken", "last"), 3);

        assertEquals(List.of("first", "first", "missing"), scripted.dispatchedCommands);
        assertEquals(new RunMinecraftCommands.Sender("DirtMCP", true, false), result.sender());
        assertTrue(result.feedbackTruncated());
        assertEquals(3, result.results().size());

        RunMinecraftCommands.CommandResult first = result.results().get(0);
        assertEquals(RunMinecraftCommands.Outcome.DISPATCHED, first.outcome());
        assertEquals(List.of("🙂a"), first.feedback());

        assertEquals(List.of("b"), result.results().get(1).feedback());
        RunMinecraftCommands.CommandResult missing = result.results().get(2);
        assertEquals(RunMinecraftCommands.Outcome.NOT_FOUND, missing.outcome());
        assertEquals("Paper found no target for this command", missing.message());
        assertNull(missing.rawMessage());
        assertFalse(
                result.results().stream()
                        .flatMap(command -> command.feedback().stream())
                        .anyMatch("late feedback"::equals));
    }

    @Test
    void includesTheFirstDispatchFailureAndNeverDispatchesLaterCommands() throws Exception {
        ScriptedServer scripted = new ScriptedServer();
        BukkitCommandAccess access = new BukkitCommandAccess(scripted.proxy(), () -> true);

        RunMinecraftCommands.Result result =
                access.dispatch(List.of("help", "broken", "last"), 100);

        assertEquals(List.of("help", "broken"), scripted.dispatchedCommands);
        assertEquals(2, result.results().size());
        RunMinecraftCommands.CommandResult broken = result.results().get(1);
        assertEquals(RunMinecraftCommands.Outcome.DISPATCH_FAILED, broken.outcome());
        assertEquals("actionable detail", broken.message());
        assertEquals("raw wrapper", broken.rawMessage());
    }

    @Test
    void rejectsDisabledPluginsAndInvalidPaperSenders() {
        BukkitCommandAccess disabled =
                new BukkitCommandAccess(new ScriptedServer().proxy(), () -> false);
        assertPaperUnavailable(
                assertThrows(
                        OperationException.class, () -> disabled.dispatch(List.of("help"), 10)));

        ScriptedServer invalid = new ScriptedServer();
        invalid.operator = false;
        assertPaperUnavailable(
                assertThrows(
                        OperationException.class,
                        () ->
                                new BukkitCommandAccess(invalid.proxy(), () -> true)
                                        .dispatch(List.of("help"), 10)));

        ScriptedServer blankName = new ScriptedServer();
        blankName.senderName = " ";
        assertPaperUnavailable(
                assertThrows(
                        OperationException.class,
                        () ->
                                new BukkitCommandAccess(blankName.proxy(), () -> true)
                                        .dispatch(List.of("help"), 10)));

        ScriptedServer player = new ScriptedServer();
        player.player = true;
        assertPaperUnavailable(
                assertThrows(
                        OperationException.class,
                        () ->
                                new BukkitCommandAccess(player.proxy(), () -> true)
                                        .dispatch(List.of("help"), 10)));

        ScriptedServer inconsistent = new ScriptedServer();
        inconsistent.inconsistentNames = true;
        assertPaperUnavailable(
                assertThrows(
                        OperationException.class,
                        () ->
                                new BukkitCommandAccess(inconsistent.proxy(), () -> true)
                                        .dispatch(List.of("help", "help"), 10)));
        assertEquals(List.of(), inconsistent.dispatchedCommands);
    }

    @Test
    void selectsTheDeepestBoundedCauseAndFallsBackForBlankMessages() throws Exception {
        ScriptedServer scripted = new ScriptedServer();
        BukkitCommandAccess access = new BukkitCommandAccess(scripted.proxy(), () -> true);

        RunMinecraftCommands.CommandResult deep = failure(access, "deep");
        RunMinecraftCommands.CommandResult blank = failure(access, "blank");
        RunMinecraftCommands.CommandResult bounded = failure(access, "bounded");
        RunMinecraftCommands.CommandResult cycle = failure(access, "cycle");

        assertEquals("deepest detail", deep.message());
        assertEquals("deep raw", deep.rawMessage());
        assertEquals("Paper command dispatch failed", blank.message());
        assertEquals("Paper command dispatch failed", blank.rawMessage());
        assertEquals("cause-15", bounded.message());
        assertEquals("cycle-b", cycle.message());
        assertEquals(List.of("deep", "blank", "bounded", "cycle"), scripted.dispatchedCommands);
    }

    @Test
    void requiresAPositiveFeedbackLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BukkitCommandAccess(new ScriptedServer().proxy(), () -> true)
                                .dispatch(List.of("help"), 0));
    }

    @Test
    void retainsWhitespaceOnlyFeedbackAndWhitespaceTruncatedPrefixes() throws Exception {
        BukkitCommandAccess access =
                new BukkitCommandAccess(new ScriptedServer().proxy(), () -> true);

        RunMinecraftCommands.Result result = access.dispatch(List.of("whitespace"), 1);

        assertEquals(List.of(" "), result.results().getFirst().feedback());
        assertTrue(result.feedbackTruncated());
    }

    private static void assertPaperUnavailable(OperationException failure) {
        assertEquals(OperationFailure.SERVER_UNAVAILABLE, failure.failure());
        assertEquals(
                new ErrorDetails.ServerUnavailable.PaperUnavailable(),
                failure.details().orElseThrow());
    }

    private static RunMinecraftCommands.CommandResult failure(
            BukkitCommandAccess access, String command) throws OperationException {
        RunMinecraftCommands.Result result = access.dispatch(List.of(command, "must-not-run"), 100);
        assertEquals(1, result.results().size());
        assertEquals(
                RunMinecraftCommands.Outcome.DISPATCH_FAILED,
                result.results().getFirst().outcome());
        return result.results().getFirst();
    }

    private static final class ScriptedServer {
        private final List<String> dispatchedCommands = new ArrayList<>();
        private final List<Consumer<Component>> feedbackConsumers = new ArrayList<>();
        private final Map<CommandSender, Consumer<Component>> feedbackBySender =
                new IdentityHashMap<>();
        private boolean operator = true;
        private String senderName = "DirtMCP";
        private boolean player;
        private boolean inconsistentNames;

        @SuppressWarnings("unchecked")
        private Server proxy() {
            return (Server)
                    Proxy.newProxyInstance(
                            Server.class.getClassLoader(),
                            new Class<?>[] {Server.class},
                            (proxy, method, arguments) -> {
                                if (method.getName().equals("createCommandSender")) {
                                    Consumer<Component> feedback =
                                            (Consumer<Component>) arguments[0];
                                    this.feedbackConsumers.add(feedback);
                                    CommandSender sender = sender(this.feedbackConsumers.size());
                                    this.feedbackBySender.put(sender, feedback);
                                    return sender;
                                }
                                if (method.getName().equals("dispatchCommand")) {
                                    String command = (String) arguments[1];
                                    this.dispatchedCommands.add(command);
                                    Consumer<Component> current =
                                            this.feedbackBySender.get((CommandSender) arguments[0]);
                                    if (command.equals("first")) {
                                        if (this.dispatchedCommands.size() == 1) {
                                            current.accept(Component.text("🙂a"));
                                        } else {
                                            this.feedbackConsumers
                                                    .get(0)
                                                    .accept(Component.text("late feedback"));
                                            current.accept(Component.text("bc"));
                                        }
                                        return true;
                                    }
                                    if (command.equals("missing")) {
                                        return false;
                                    }
                                    if (command.equals("broken")) {
                                        throw new CommandException(
                                                "raw wrapper",
                                                new IllegalStateException("actionable detail"));
                                    }
                                    if (command.equals("deep")) {
                                        throw new CommandException(
                                                "deep raw",
                                                new IllegalStateException(
                                                        "outer detail",
                                                        new IllegalArgumentException(
                                                                "deepest detail")));
                                    }
                                    if (command.equals("blank")) {
                                        throw new CommandException(
                                                " ", new IllegalStateException(" "));
                                    }
                                    if (command.equals("bounded")) {
                                        Throwable cause = new IllegalStateException("cause-20");
                                        for (int index = 19; index >= 0; index--) {
                                            cause =
                                                    new IllegalStateException(
                                                            "cause-" + index, cause);
                                        }
                                        throw new CommandException("bounded raw", cause);
                                    }
                                    if (command.equals("cycle")) {
                                        Throwable first = new Exception("cycle-a");
                                        Throwable second = new Exception("cycle-b");
                                        first.initCause(second);
                                        second.initCause(first);
                                        throw new CommandException("cycle raw", first);
                                    }
                                    if (command.equals("whitespace")) {
                                        current.accept(Component.text("  x"));
                                        return true;
                                    }
                                    current.accept(Component.empty());
                                    return true;
                                }
                                if (method.getName().equals("toString")) {
                                    return "test server";
                                }
                                throw new AssertionError("Unexpected Server method: " + method);
                            });
        }

        private CommandSender sender(int sequence) {
            Class<?> senderType = this.player ? Player.class : CommandSender.class;
            String name =
                    this.inconsistentNames ? this.senderName + '-' + sequence : this.senderName;
            return (CommandSender)
                    Proxy.newProxyInstance(
                            senderType.getClassLoader(),
                            new Class<?>[] {senderType},
                            (proxy, method, arguments) ->
                                    switch (method.getName()) {
                                        case "getName" -> name;
                                        case "isOp" -> this.operator;
                                        case "toString" -> "test command sender";
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected CommandSender method: "
                                                                + method);
                                    });
        }
    }
}
