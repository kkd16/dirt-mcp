package ca.deliyannides.dirtmcp.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Outcome;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Request;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Sender;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandException;
import org.junit.jupiter.api.Test;

final class PaperCommandServiceTest {
    private static final Sender SENDER = new Sender("FeedbackForwardingSender", true, false);

    @Test
    void runsTheValidatedBatchThroughTheMainThreadBoundary() throws Exception {
        var mainThread = new DirectMainThread();
        var service =
                new PaperCommandService(
                        mainThread,
                        (commands, budget) ->
                                new RunMinecraftCommands.Result(SENDER, false, List.of()),
                        2,
                        100);

        assertEquals(SENDER, service.runCommands(new Request(List.of("/say hello"))).sender());
        assertEquals(1, mainThread.calls);
    }

    @Test
    void mapsSchedulerFailureAndPreservesOperationFailures() {
        var unavailable =
                new PaperCommandService(
                        new FailingMainThread(new IllegalStateException("stopping")),
                        (commands, budget) -> null,
                        2,
                        100);
        OperationException unavailableFailure =
                assertThrows(
                        OperationException.class,
                        () -> unavailable.runCommands(new Request(List.of("say hello"))));
        assertEquals(OperationFailure.SERVER_UNAVAILABLE, unavailableFailure.failure());

        OperationException expected =
                new OperationException(OperationFailure.SERVER_UNAVAILABLE, "disabled");
        var preserved =
                new PaperCommandService(
                        new FailingMainThread(expected), (commands, budget) -> null, 2, 100);
        assertEquals(
                expected,
                assertThrows(
                        OperationException.class,
                        () -> preserved.runCommands(new Request(List.of("say hello")))));
    }

    @Test
    void normalizesSlashesAndRejectsInvalidBatches() throws Exception {
        assertEquals(
                List.of("say hello", "time set day"),
                PaperCommandService.normalize(
                        new Request(List.of(" /say hello ", "time set day")), 2));
        assertThrows(
                OperationException.class,
                () -> PaperCommandService.normalize(new Request(List.of()), 2));
        assertThrows(
                OperationException.class,
                () -> PaperCommandService.normalize(new Request(List.of("one", "two")), 1));
        assertThrows(
                OperationException.class,
                () -> PaperCommandService.normalize(new Request(List.of("say\nhello")), 2));
        assertThrows(
                OperationException.class,
                () -> PaperCommandService.normalize(new Request(List.of("/")), 2));
        assertThrows(
                OperationException.class,
                () ->
                        PaperCommandService.normalize(
                                new Request(java.util.Arrays.asList("one", null)), 2));
    }

    @Test
    void dispatchesEveryCommandInOrderAfterANotFoundCommand() {
        var result =
                PaperCommandService.runBatch(
                        List.of("missing", "say done"),
                        100,
                        SENDER,
                        (command, feedback) -> !command.equals("missing"));

        assertEquals(
                List.of("missing", "say done"),
                result.results().stream()
                        .map(RunMinecraftCommands.CommandResult::command)
                        .toList());
        assertEquals(Outcome.NOT_FOUND, result.results().get(0).outcome());
        assertEquals(Outcome.DISPATCHED, result.results().get(1).outcome());
        assertFalse(result.feedbackTruncated());
    }

    @Test
    void capturesAUnicodeCodePointBudgetAndIgnoresEmptyFeedback() {
        var result =
                PaperCommandService.runBatch(
                        List.of("first", "second"),
                        3,
                        SENDER,
                        (command, feedback) -> {
                            feedback.accept(Component.empty());
                            feedback.accept(Component.text(command.equals("first") ? "🙂a" : "bc"));
                            return true;
                        });

        assertEquals(List.of("🙂a"), result.results().get(0).feedback());
        assertEquals(List.of("b"), result.results().get(1).feedback());
        assertTrue(result.feedbackTruncated());
    }

    @Test
    void reportsDeepestActionableCauseAndPreservesRawMessage() {
        var result =
                PaperCommandService.runBatch(
                        List.of("explode"),
                        100,
                        SENDER,
                        (command, feedback) -> {
                            throw new CommandException(
                                    "Paper wrapper", new IllegalStateException("actionable"));
                        });

        var command = result.results().getFirst();
        assertEquals(Outcome.DISPATCH_FAILED, command.outcome());
        assertEquals("actionable", command.message());
        assertEquals("Paper wrapper", command.rawMessage());
    }

    private static final class DirectMainThread implements MainThread {
        private int calls;

        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            this.calls++;
            try {
                return action.get();
            } catch (Exception exception) {
                throw new PaperMainThreadException("failed", exception);
            }
        }

        @Override
        public void close() {}
    }

    private record FailingMainThread(Throwable cause) implements MainThread {
        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            throw new PaperMainThreadException("failed", this.cause);
        }

        @Override
        public void close() {}
    }
}
