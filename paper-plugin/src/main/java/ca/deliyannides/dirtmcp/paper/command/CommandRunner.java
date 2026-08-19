package ca.deliyannides.dirtmcp.paper.command;

import com.google.gson.annotations.SerializedName;
import java.io.Serial;
import java.util.List;

public interface CommandRunner {
    RunCommandsResult runCommands(RunCommandsRequest request) throws CommandRunnerException;

    record RunCommandsRequest(List<String> commands) {}

    record RunCommandsResult(
            Sender sender,
            boolean feedbackTruncated,
            List<CommandResult> results) {}

    record Sender(String name, boolean isOperator, boolean isPlayer) {}

    record CommandResult(
            String command,
            CommandOutcome outcome,
            List<String> feedback,
            String message) {}

    enum CommandOutcome {
        @SerializedName("dispatched")
        DISPATCHED,
        @SerializedName("not_found")
        NOT_FOUND,
        @SerializedName("dispatch_failed")
        DISPATCH_FAILED,
        @SerializedName("skipped")
        SKIPPED
    }

    enum Failure {
        INVALID_REQUEST,
        SERVER_UNAVAILABLE
    }

    final class CommandRunnerException extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Failure failure;

        public CommandRunnerException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public CommandRunnerException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        public Failure failure() {
            return this.failure;
        }
    }
}
