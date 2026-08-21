package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.Objects;

public final class PaperPerspectiveViewService implements GetPerspectiveView {
    private final MainThread mainThread;
    private final PerspectiveViewAccess paperAccess;
    private final InspectionAdmission admission;

    public PaperPerspectiveViewService(
            MainThread mainThread,
            PerspectiveViewAccess paperAccess,
            InspectionAdmission admission) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.paperAccess = Objects.requireNonNull(paperAccess, "paperAccess");
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    @Override
    public Result getPerspectiveView(Request request) throws OperationException {
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
                        "Perspective view capture was interrupted",
                        new ErrorDetails.WorldUnavailable.Interrupted(),
                        exception);
            }
            throw new OperationException(
                    OperationFailure.SERVER_UNAVAILABLE,
                    "Perspective view is unavailable",
                    new ErrorDetails.ServerUnavailable.PaperUnavailable(),
                    exception);
        }
    }

    private static void validate(Request request) throws OperationException {
        if (request == null) {
            throw invalid(
                    "source and perspective options are required",
                    new ErrorDetails.InvalidRequest.Missing("request"));
        }
        if (request.source() == null) {
            throw invalid("source is required", new ErrorDetails.InvalidRequest.Missing("source"));
        }
        switch (request.source()) {
            case PlayerSource playerSource -> validatePlayer(playerSource.player());
            case LocationSource locationSource -> validateLocation(locationSource);
        }
        PerspectiveViewAlgorithms.validate(request.options());
    }

    private static void validatePlayer(String player) throws OperationException {
        if (player == null || player.isBlank()) {
            throw invalid(
                    "source.player must be a non-empty case-insensitive exact online name or canonical UUID",
                    new ErrorDetails.InvalidRequest.InvalidValue("source.player"));
        }
        if (player.length() > GetPlayerContext.MAX_PLAYER_SELECTOR_LENGTH) {
            throw invalid(
                    "source.player must contain at most "
                            + GetPlayerContext.MAX_PLAYER_SELECTOR_LENGTH
                            + " characters",
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            "source.player.length",
                            player.length(),
                            1,
                            GetPlayerContext.MAX_PLAYER_SELECTOR_LENGTH));
        }
    }

    private static void validateLocation(LocationSource source) throws OperationException {
        if (source.world() == null || source.world().isBlank()) {
            throw invalid(
                    "source.world must be non-empty",
                    new ErrorDetails.InvalidRequest.InvalidValue("source.world"));
        }
        if (source.cameraPosition() == null) {
            throw invalid(
                    "source.cameraPosition is required",
                    new ErrorDetails.InvalidRequest.Missing("source.cameraPosition"));
        }
        if (source.rotation() == null) {
            throw invalid(
                    "source.rotation is required",
                    new ErrorDetails.InvalidRequest.Missing("source.rotation"));
        }
        if (source.rotation().pitch() < -90 || source.rotation().pitch() > 90) {
            throw invalid(
                    "source.rotation.pitch must be between -90 and 90",
                    new ErrorDetails.InvalidRequest.InvalidValue("source.rotation.pitch"));
        }
    }

    private static OperationException invalid(String message, ErrorDetails.InvalidRequest details) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message, details);
    }

    @FunctionalInterface
    public interface PerspectiveViewAccess {
        Result capture(Request request) throws OperationException;
    }
}
