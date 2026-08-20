package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.Objects;

public final class PaperPlayerContextService implements GetPlayerContext {
    private final MainThread mainThread;
    private final PlayerContextAccess paperAccess;
    private final InspectionAdmission admission;

    public PaperPlayerContextService(
            MainThread mainThread, PlayerContextAccess paperAccess, InspectionAdmission admission) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.paperAccess = Objects.requireNonNull(paperAccess, "paperAccess");
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    @Override
    public Result getPlayerContext(Request request) throws OperationException {
        validate(request);
        return this.admission.execute(() -> captureOnMainThread(request));
    }

    private Result captureOnMainThread(Request request) throws OperationException {
        try {
            return this.mainThread.call(() -> this.paperAccess.capture(request));
        } catch (PaperMainThreadException exception) {
            if (exception.getCause() instanceof OperationException operationException) {
                throw operationException;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        "Player context capture was interrupted",
                        new ErrorDetails.WorldUnavailable.Interrupted(),
                        exception);
            }
            throw new OperationException(
                    OperationFailure.SERVER_UNAVAILABLE,
                    "Player context is unavailable",
                    new ErrorDetails.ServerUnavailable.PaperUnavailable(),
                    exception);
        }
    }

    private static void validate(Request request) throws OperationException {
        if (request == null) {
            throw invalid(
                    "player and include are required",
                    new ErrorDetails.InvalidRequest.Missing("request"));
        }
        if (request.player() == null || request.player().isBlank()) {
            throw invalid(
                    "player must be a non-empty exact online name or canonical UUID",
                    new ErrorDetails.InvalidRequest.InvalidValue("player"));
        }
        if (request.player().length() > MAX_PLAYER_SELECTOR_LENGTH) {
            throw invalid(
                    "player must contain at most " + MAX_PLAYER_SELECTOR_LENGTH + " characters",
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            "player.length",
                            request.player().length(),
                            1,
                            MAX_PLAYER_SELECTOR_LENGTH));
        }
        if (request.include() == null) {
            throw invalid(
                    "include is required", new ErrorDetails.InvalidRequest.Missing("include"));
        }
        if (!request.include().view()) {
            if (request.view() != null) {
                throw invalid(
                        "view must be omitted when include.view is false",
                        new ErrorDetails.InvalidRequest.InvalidValue("view"));
            }
            return;
        }
        PerspectiveViewAlgorithms.validate(request.view());
    }

    private static OperationException invalid(String message, ErrorDetails.InvalidRequest details) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message, details);
    }

    @FunctionalInterface
    public interface PlayerContextAccess {
        Result capture(Request request) throws OperationException;
    }
}
