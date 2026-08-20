package ca.deliyannides.dirtmcp.paper.command;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@FunctionalInterface
public interface RunMinecraftCommands {
    Result runCommands(Request request) throws OperationException;

    record Request(List<String> commands) {
        public Request {
            commands =
                    commands == null
                            ? null
                            : Collections.unmodifiableList(new ArrayList<>(commands));
        }
    }

    record Result(Sender sender, boolean feedbackTruncated, List<CommandResult> results) {
        public Result {
            results = List.copyOf(results);
        }
    }

    record Sender(String name, boolean isOperator, boolean isPlayer) {}

    record CommandResult(
            String command,
            Outcome outcome,
            List<String> feedback,
            String message,
            String rawMessage) {
        public CommandResult {
            feedback = List.copyOf(feedback);
        }
    }

    enum Outcome {
        DISPATCHED("dispatched"),
        NOT_FOUND("not_found"),
        DISPATCH_FAILED("dispatch_failed");

        private final String wireName;

        Outcome(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return this.wireName;
        }
    }
}
