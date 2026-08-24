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
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAccess.CameraSnapshot;
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAccess.Projection;
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
        TrackingAccess access = new TrackingAccess(mainThread, expected);
        PaperPerspectiveViewService service = service(mainThread, access);

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
        assertEquals(4, mainThread.calls);
        assertEquals(2, access.cameraCaptures);
        assertEquals(2, access.traces);
    }

    @Test
    void validatesBothSourceShapesBeforeCallingPaper() {
        AtomicBoolean captured = new AtomicBoolean();
        PaperPerspectiveViewService service =
                service(
                        new DirectMainThread(),
                        new PerspectiveViewAccess() {
                            @Override
                            public CameraSnapshot captureCamera(GetPerspectiveView.Source source) {
                                captured.set(true);
                                return camera();
                            }

                            @Override
                            public GetPerspectiveView.Result trace(
                                    ViewRequest request,
                                    CameraSnapshot camera,
                                    Projection projection) {
                                return result();
                            }
                        });

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
                service(
                        new DirectMainThread(),
                        new PerspectiveViewAccess() {
                            @Override
                            public CameraSnapshot captureCamera(GetPerspectiveView.Source source)
                                    throws OperationException {
                                throw expected;
                            }

                            @Override
                            public GetPerspectiveView.Result trace(
                                    ViewRequest request,
                                    CameraSnapshot camera,
                                    Projection projection) {
                                throw new AssertionError("trace must not run");
                            }
                        });
        assertEquals(
                expected,
                assertThrows(
                        OperationException.class,
                        () ->
                                accessFailure.getPerspectiveView(
                                        new GetPerspectiveView.Request(
                                                new PlayerSource("builder"), VIEW))));

        PaperPerspectiveViewService schedulerFailure =
                service(
                        new FailingMainThread(),
                        new TrackingAccess(new DirectMainThread(), result()));
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

    private static CameraSnapshot camera() {
        return new CameraSnapshot(
                Instant.parse("2026-08-20T12:00:00Z"),
                new GetPerspectiveView.ResolvedLocationSource(),
                "world",
                UUID.fromString("223e4567-e89b-42d3-a456-426614174000"),
                -64,
                320,
                new ExactPosition(1.25, 65.62, -2.5),
                new Rotation(90, -10),
                new Vector3(-1, 0, 0));
    }

    private static PaperPerspectiveViewService service(
            MainThread mainThread, PerspectiveViewAccess access) {
        return new PaperPerspectiveViewService(
                mainThread, access, new InspectionAdmission(1), 9, 288, 64);
    }

    private static final class TrackingAccess implements PerspectiveViewAccess {
        private final DirectMainThread mainThread;
        private final GetPerspectiveView.Result result;
        private int cameraCaptures;
        private int traces;

        private TrackingAccess(DirectMainThread mainThread, GetPerspectiveView.Result result) {
            this.mainThread = mainThread;
            this.result = result;
        }

        @Override
        public CameraSnapshot captureCamera(GetPerspectiveView.Source source) {
            if (!this.mainThread.insideCall) {
                throw new AssertionError("camera capture ran off the main-thread boundary");
            }
            this.cameraCaptures++;
            return camera();
        }

        @Override
        public GetPerspectiveView.Result trace(
                ViewRequest request, CameraSnapshot camera, Projection projection) {
            if (!this.mainThread.insideCall) {
                throw new AssertionError("ray tracing ran off the main-thread boundary");
            }
            this.traces++;
            assertEquals(9, projection.directions().size());
            return this.result;
        }
    }

    private static final class DirectMainThread implements MainThread {
        private int calls;
        private boolean insideCall;

        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            this.calls++;
            this.insideCall = true;
            try {
                return action.get();
            } catch (Exception exception) {
                throw new PaperMainThreadException("failed", exception);
            } finally {
                this.insideCall = false;
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
