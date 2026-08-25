package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.PlayerSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAccess.CameraSnapshot;
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAccess.Chunk;
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAccess.Projection;
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAlgorithms.ChunkCoordinate;
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAlgorithms.Geometry;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class PaperPerspectiveViewService implements GetPerspectiveView {
    private final MainThread mainThread;
    private final PerspectiveViewAccess paperAccess;
    private final InspectionAdmission admission;
    private final int maximumRays;
    private final int maximumRayDistanceBudget;
    private final int maximumCheckedChunks;

    public PaperPerspectiveViewService(
            MainThread mainThread,
            PerspectiveViewAccess paperAccess,
            InspectionAdmission admission,
            int maximumRays,
            int maximumRayDistanceBudget,
            int maximumCheckedChunks) {
        if (maximumRays < 1 || maximumRayDistanceBudget < 1 || maximumCheckedChunks < 1) {
            throw new IllegalArgumentException("Perspective view limits must be positive");
        }
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.paperAccess = Objects.requireNonNull(paperAccess, "paperAccess");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.maximumRays = maximumRays;
        this.maximumRayDistanceBudget = maximumRayDistanceBudget;
        this.maximumCheckedChunks = maximumCheckedChunks;
    }

    @Override
    public Result getPerspectiveView(Request request) throws OperationException {
        validate(request);
        return this.admission.execute(() -> capture(request));
    }

    private Result capture(Request request) throws OperationException {
        CameraSnapshot camera =
                callOnMainThread(() -> this.paperAccess.captureCamera(request.source()));
        Projection projection = project(request, camera);
        return callOnMainThread(
                () -> this.paperAccess.trace(request.options(), camera, projection));
    }

    private Projection project(Request request, CameraSnapshot camera) throws OperationException {
        Geometry geometry =
                PerspectiveViewAlgorithms.geometry(
                        request.options(),
                        camera.lookDirection(),
                        camera.rotation().yaw(),
                        this.maximumRays,
                        this.maximumRayDistanceBudget);
        String outOfRangeAxis =
                PerspectiveViewAlgorithms.firstOutOfRangeEndpointAxis(
                        camera.position().x(),
                        camera.position().y(),
                        camera.position().z(),
                        request.options().maxDistance(),
                        geometry.directions());
        if (outOfRangeAxis != null) {
            throw endpointOutOfRange(request.source(), outOfRangeAxis);
        }
        Set<ChunkCoordinate> requiredChunks =
                PerspectiveViewAlgorithms.requiredChunks(
                        camera.position().x(),
                        camera.position().y(),
                        camera.position().z(),
                        camera.minimumHeight(),
                        camera.maximumHeight(),
                        request.options().maxDistance(),
                        geometry.directions());
        if (requiredChunks.size() > this.maximumCheckedChunks) {
            throw new OperationException(
                    OperationFailure.REGION_TOO_LARGE,
                    "Perspective view requires checking "
                            + requiredChunks.size()
                            + " chunks, exceeding the maximum of "
                            + this.maximumCheckedChunks,
                    new ErrorDetails.RegionTooLarge.PerspectiveChunks(
                            requiredChunks.size(), this.maximumCheckedChunks));
        }
        Set<Chunk> chunks = new LinkedHashSet<>(requiredChunks.size());
        for (ChunkCoordinate chunk : requiredChunks) {
            chunks.add(new Chunk(chunk.x(), chunk.z()));
        }
        return new Projection(
                new GetPerspectiveView.ViewBasis(
                        geometry.forward(), geometry.right(), geometry.up()),
                geometry.horizontalFieldOfViewDegrees(),
                geometry.directions(),
                chunks);
    }

    private <T> T callOnMainThread(MainThread.CheckedSupplier<T> action) throws OperationException {
        try {
            return this.mainThread.call(action);
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

    private static OperationException endpointOutOfRange(Source source, String axis) {
        if (source instanceof PlayerSource playerSource) {
            return new OperationException(
                    OperationFailure.PLAYER_UNAVAILABLE,
                    "Player perspective endpoint is outside the signed block-coordinate range at "
                            + axis
                            + ": "
                            + playerSource.player(),
                    new ErrorDetails.PlayerUnavailable.PositionOutOfRange(
                            playerSource.player(), "perspectiveEndpoint." + axis));
        }
        return invalidCoordinate("perspectiveEndpoint." + axis);
    }

    private static OperationException invalidCoordinate(String field) {
        return new OperationException(
                OperationFailure.INVALID_REQUEST,
                field + " is outside the signed block-coordinate range",
                new ErrorDetails.InvalidRequest.InvalidValue(field));
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
        int playerLength = player.codePointCount(0, player.length());
        if (playerLength > GetPlayerContext.MAX_PLAYER_SELECTOR_LENGTH) {
            throw invalid(
                    "source.player must contain at most "
                            + GetPlayerContext.MAX_PLAYER_SELECTOR_LENGTH
                            + " characters",
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            "source.player.length",
                            playerLength,
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
}
