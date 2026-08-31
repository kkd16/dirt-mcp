package ca.deliyannides.dirtmcp.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PaperCommandServiceTest {
    @Test
    void normalizesTheWholeBatchAndCrossesTheMainThreadOnce() throws Exception {
        DirectMainThread mainThread = new DirectMainThread();
        RecordingAccess access = new RecordingAccess();
        PaperCommandService service = new PaperCommandService(mainThread, access, 10, 17);

        RunMinecraftCommands.Result result =
                service.runCommands(
                        new RunMinecraftCommands.Request(
                                List.of("  /say hello  ", "//help", "say hello")));

        assertEquals(List.of("say hello", "/help", "say hello"), access.commands);
        assertEquals(17, access.maximumFeedbackCodePoints);
        assertEquals(1, mainThread.calls);
        assertEquals(access.result, result);
    }

    @Test
    void validatesEveryEntryBeforeCrossingTheMainThread() {
        DirectMainThread mainThread = new DirectMainThread();
        RecordingAccess access = new RecordingAccess();
        PaperCommandService service = new PaperCommandService(mainThread, access, 10, 17);

        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () ->
                                service.runCommands(
                                        new RunMinecraftCommands.Request(
                                                List.of("say this must not run", "bad\ncommand"))));

        assertEquals(OperationFailure.INVALID_REQUEST, failure.failure());
        assertEquals(
                new ErrorDetails.InvalidRequest.InvalidValue("commands[1]"),
                failure.details().orElseThrow());
        assertEquals(0, mainThread.calls);
        assertNull(access.commands);
    }

    @Test
    void rejectsInvalidBatchesAtTheirOwningBoundary() {
        PaperCommandService service =
                new PaperCommandService(new DirectMainThread(), new RecordingAccess(), 2, 17);

        assertDetails(
                new ErrorDetails.InvalidRequest.InvalidValue("commands"),
                assertThrows(
                        OperationException.class,
                        () -> service.runCommands(new RunMinecraftCommands.Request(List.of()))));
        assertDetails(
                new ErrorDetails.InvalidRequest.TooManyItems(List.of("commands"), 2),
                assertThrows(
                        OperationException.class,
                        () ->
                                service.runCommands(
                                        new RunMinecraftCommands.Request(
                                                List.of("one", "two", "three")))));
        assertDetails(
                new ErrorDetails.InvalidRequest.InvalidValue("commands[0]"),
                assertThrows(
                        OperationException.class,
                        () ->
                                service.runCommands(
                                        new RunMinecraftCommands.Request(List.of(" / ")))));
    }

    @Test
    void preservesTypedPaperFailuresAndMapsSchedulerFailures() {
        OperationException expected =
                new OperationException(
                        OperationFailure.SERVER_UNAVAILABLE,
                        "Paper is stopping",
                        new ErrorDetails.ServerUnavailable.PaperUnavailable());
        PaperCommandService typed =
                new PaperCommandService(
                        new DirectMainThread(),
                        (commands, maximum) -> {
                            throw expected;
                        },
                        10,
                        17);
        assertSame(
                expected,
                assertThrows(
                        OperationException.class,
                        () ->
                                typed.runCommands(
                                        new RunMinecraftCommands.Request(List.of("help")))));

        PaperCommandService unavailable =
                new PaperCommandService(new FailingMainThread(), new RecordingAccess(), 10, 17);
        OperationException mapped =
                assertThrows(
                        OperationException.class,
                        () ->
                                unavailable.runCommands(
                                        new RunMinecraftCommands.Request(List.of("help"))));
        assertEquals(OperationFailure.SERVER_UNAVAILABLE, mapped.failure());
        assertEquals(
                new ErrorDetails.ServerUnavailable.PaperUnavailable(),
                mapped.details().orElseThrow());
    }

    @Test
    void requiresPositiveLimits() {
        DirectMainThread mainThread = new DirectMainThread();
        RecordingAccess access = new RecordingAccess();

        assertThrows(
                IllegalArgumentException.class,
                () -> new PaperCommandService(mainThread, access, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaperCommandService(mainThread, access, 1, 0));
    }

    private static void assertDetails(
            ErrorDetails.InvalidRequest expected, OperationException actual) {
        assertEquals(OperationFailure.INVALID_REQUEST, actual.failure());
        assertEquals(expected, actual.details().orElseThrow());
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

    private static final class FailingMainThread implements MainThread {
        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            throw new PaperMainThreadException("Paper is stopping");
        }

        @Override
        public void close() {}
    }

    private static final class RecordingAccess implements PaperCommandService.CommandAccess {
        private RunMinecraftCommands.Result result;
        private List<String> commands;
        private int maximumFeedbackCodePoints;

        @Override
        public RunMinecraftCommands.Result dispatch(
                List<String> commands, int maximumFeedbackCodePoints) {
            this.commands = commands;
            this.maximumFeedbackCodePoints = maximumFeedbackCodePoints;
            this.result =
                    new RunMinecraftCommands.Result(
                            new RunMinecraftCommands.Sender("DirtMCP", true, false),
                            false,
                            commands.stream()
                                    .map(
                                            command ->
                                                    new RunMinecraftCommands.CommandResult(
                                                            command,
                                                            RunMinecraftCommands.Outcome.DISPATCHED,
                                                            List.of(),
                                                            null,
                                                            null))
                                    .toList());
            return this.result;
        }
    }
}
