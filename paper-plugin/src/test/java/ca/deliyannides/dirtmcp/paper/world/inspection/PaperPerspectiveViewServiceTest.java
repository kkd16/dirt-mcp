package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.FluidCollision;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.LocationSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.PlayerSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewRequest;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class PaperPerspectiveViewServiceTest {
    private static final ViewRequest VIEW =
            new ViewRequest(3, 3, 70, 32, FluidCollision.NEVER, false);

    @Test
    void capturesPlayerAndLocationSourcesThroughTheMainThreadBoundary() throws Exception {
        GetPerspectiveView.Result expected = result();
        DirectMainThread mainThread = new DirectMainThread();
        PaperPerspectiveViewService service =
                new PaperPerspectiveViewService(
                        mainThread, ignored -> expected, new InspectionAdmission(1));

        assertEquals(
                expected,
                service.getPerspectiveView(
                        new GetPerspectiveView.Request(new PlayerSource("builder"), VIEW)));
        assertEquals(
                expected,
                service.getPerspectiveView(
                        new GetPerspectiveView.Request(
                                new LocationSource(
                                        "world",
                                        new ExactPosition(1.25, 65.62, -2.5),
                                        new Rotation(90, -10)),
                                VIEW)));
        assertEquals(2, mainThread.calls);
    }

    @Test
    void validatesBothSourceShapesBeforeCallingPaper() {
        AtomicBoolean captured = new AtomicBoolean();
        PaperPerspectiveViewService service =
                new PaperPerspectiveViewService(
                        new DirectMainThread(),
                        ignored -> {
                            captured.set(true);
                            return result();
                        },
                        new InspectionAdmission(1));

        OperationException blankPlayer =
                assertThrows(
                        OperationException.class,
                        () ->
                                service.getPerspectiveView(
                                        new GetPerspectiveView.Request(
                                                new PlayerSource(" "), VIEW)));
        assertEquals(
                new ErrorDetails.InvalidRequest.InvalidValue("source.player"),
                blankPlayer.details().orElseThrow());

        OperationException invalidPitch =
                assertThrows(
                        OperationException.class,
                        () ->
                                service.getPerspectiveView(
                                        new GetPerspectiveView.Request(
                                                new LocationSource(
                                                        "world",
                                                        new ExactPosition(0, 64, 0),
                                                        new Rotation(0, 91)),
                                                VIEW)));
        assertEquals(
                new ErrorDetails.InvalidRequest.InvalidValue("source.rotation.pitch"),
                invalidPitch.details().orElseThrow());

        OperationException evenViewport =
                assertThrows(
                        OperationException.class,
                        () ->
                                service.getPerspectiveView(
                                        new GetPerspectiveView.Request(
                                                new PlayerSource("Builder"),
                                                new ViewRequest(
                                                        2,
                                                        3,
                                                        70,
                                                        32,
                                                        FluidCollision.NEVER,
                                                        false))));
        assertEquals(OperationFailure.INVALID_REQUEST, evenViewport.failure());
        assertFalse(captured.get());
    }

    @Test
    void preservesTypedAccessFailuresAndMapsSchedulerFailure() {
        OperationException expected =
                new OperationException(
                        OperationFailure.PLAYER_NOT_FOUND,
                        "Player is not online: builder",
                        new ErrorDetails.PlayerNotFound("builder"));
        PaperPerspectiveViewService accessFailure =
                new PaperPerspectiveViewService(
                        new DirectMainThread(),
                        ignored -> {
                            throw expected;
                        },
                        new InspectionAdmission(1));
        assertEquals(
                expected,
                assertThrows(
                        OperationException.class,
                        () ->
                                accessFailure.getPerspectiveView(
                                        new GetPerspectiveView.Request(
                                                new PlayerSource("builder"), VIEW))));

        PaperPerspectiveViewService schedulerFailure =
                new PaperPerspectiveViewService(
                        new FailingMainThread(), ignored -> result(), new InspectionAdmission(1));
        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () ->
                                schedulerFailure.getPerspectiveView(
                                        new GetPerspectiveView.Request(
                                                new PlayerSource("Builder"), VIEW)));
        assertEquals(OperationFailure.SERVER_UNAVAILABLE, failure.failure());
        assertEquals(
                new ErrorDetails.ServerUnavailable.PaperUnavailable(),
                failure.details().orElseThrow());
    }

    private static GetPerspectiveView.Result result() {
        Vector3 forward = new Vector3(-1, 0, 0);
        return new GetPerspectiveView.Result(
                Instant.parse("2026-08-20T12:00:00Z"),
                new GetPerspectiveView.ResolvedLocationSource(),
                "world",
                UUID.fromString("223e4567-e89b-42d3-a456-426614174000"),
                new ExactPosition(1.25, 65.62, -2.5),
                new Rotation(90, -10),
                forward,
                new GetPerspectiveView.ViewBasis(
                        forward, new Vector3(0, 0, -1), new Vector3(0, 1, 0)),
                new GetPerspectiveView.Viewport(3, 3, 70, 70, 32, "never", false),
                1,
                List.of(),
                List.of(),
                null);
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
            throw new PaperMainThreadException("stopping");
        }

        @Override
        public void close() {}
    }
}
