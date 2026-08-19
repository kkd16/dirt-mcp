package ca.deliyannides.dirtmcp.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.command.CommandRunner.CommandOutcome;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.CommandRunnerException;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.RunCommandsRequest;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.RunCommandsResult;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.Sender;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandException;
import org.junit.jupiter.api.Test;

final class PaperCommandRunnerTest {
    private static final Sender SENDER = new Sender("FeedbackForwardingSender", true, false);

    @Test
    void normalizesInGameSlashesAndRejectsInvalidBatches() throws Exception {
        assertEquals(
                List.of("say hello", "/set stone"),
                PaperCommandRunner.normalize(
                        new RunCommandsRequest(List.of(" /say hello ", " //set stone ")),
                        2));

        assertThrows(
                CommandRunnerException.class,
                () -> PaperCommandRunner.normalize(new RunCommandsRequest(List.of()), 2));
        assertThrows(
                CommandRunnerException.class,
                () -> PaperCommandRunner.normalize(
                        new RunCommandsRequest(List.of("say one", "say two", "say three")),
                        2));
        assertThrows(
                CommandRunnerException.class,
                () -> PaperCommandRunner.normalize(
                        new RunCommandsRequest(List.of("say one\nsay two")),
                        2));
    }

    @Test
    void dispatchesEveryCommandInOrderAfterANotFoundCommand() {
        List<String> attempted = new ArrayList<>();

        RunCommandsResult result = PaperCommandRunner.runBatch(
                List.of("say first", "missing", "say third"),
                100,
                SENDER,
                (command, feedback) -> {
                    attempted.add(command);
                    feedback.accept(Component.text("feedback " + command));
                    return !command.equals("missing");
                });

        assertEquals(List.of("say first", "missing", "say third"), attempted);
        assertEquals(
                List.of(
                        CommandOutcome.DISPATCHED,
                        CommandOutcome.NOT_FOUND,
                        CommandOutcome.DISPATCHED),
                result.results().stream().map(CommandRunner.CommandResult::outcome).toList());
        assertEquals(List.of("feedback say first"), result.results().getFirst().feedback());
        assertEquals(List.of("feedback say third"), result.results().getLast().feedback());
    }

    @Test
    void capturesBoundedFeedbackAndContinuesAfterDispatchExceptions() {
        RunCommandsResult result = PaperCommandRunner.runBatch(
                List.of("first", "broken", "last"),
                5,
                SENDER,
                (command, feedback) -> {
                    feedback.accept(Component.text("abcdef"));
                    if (command.equals("broken")) {
                        throw new CommandException("boom");
                    }
                    return true;
                });

        assertEquals(true, result.feedbackTruncated());
        assertEquals(List.of("abcde"), result.results().getFirst().feedback());
        assertEquals(CommandOutcome.DISPATCH_FAILED, result.results().get(1).outcome());
        assertEquals("boom", result.results().get(1).message());
        assertEquals(CommandOutcome.DISPATCHED, result.results().getLast().outcome());
    }
}
