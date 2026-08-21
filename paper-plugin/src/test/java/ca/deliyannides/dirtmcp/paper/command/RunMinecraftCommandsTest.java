package ca.deliyannides.dirtmcp.paper.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class RunMinecraftCommandsTest {
    @Test
    void acceptsAllDispatchedResultsAndOneFinalFailure() {
        assertDoesNotThrow(() -> result(dispatched("one"), dispatched("two"), notFound("three")));
        assertDoesNotThrow(() -> result(dispatched("one"), dispatchFailed("two")));
        assertDoesNotThrow(() -> result(dispatched("one"), dispatched("two")));
    }

    @Test
    void rejectsAnyFailureBeforeTheFinalResult() {
        assertThrows(
                IllegalArgumentException.class, () -> result(notFound("one"), dispatched("two")));
        assertThrows(
                IllegalArgumentException.class,
                () -> result(dispatchFailed("one"), notFound("two")));
    }

    @Test
    void requiresNormalizedControlFreeResultCommands() {
        assertDoesNotThrow(() -> dispatched("/help"));
        assertThrows(IllegalArgumentException.class, () -> dispatched(" help"));
        assertThrows(IllegalArgumentException.class, () -> dispatched("help "));
        assertThrows(IllegalArgumentException.class, () -> dispatched("say\nhello"));
    }

    private static RunMinecraftCommands.Result result(
            RunMinecraftCommands.CommandResult... results) {
        return new RunMinecraftCommands.Result(
                new RunMinecraftCommands.Sender("DirtMCP", true, false), false, List.of(results));
    }

    private static RunMinecraftCommands.CommandResult dispatched(String command) {
        return new RunMinecraftCommands.CommandResult(
                command, RunMinecraftCommands.Outcome.DISPATCHED, List.of(), null, null);
    }

    private static RunMinecraftCommands.CommandResult notFound(String command) {
        return new RunMinecraftCommands.CommandResult(
                command, RunMinecraftCommands.Outcome.NOT_FOUND, List.of(), "not found", null);
    }

    private static RunMinecraftCommands.CommandResult dispatchFailed(String command) {
        return new RunMinecraftCommands.CommandResult(
                command,
                RunMinecraftCommands.Outcome.DISPATCH_FAILED,
                List.of(),
                "failed",
                "raw failure");
    }
}
