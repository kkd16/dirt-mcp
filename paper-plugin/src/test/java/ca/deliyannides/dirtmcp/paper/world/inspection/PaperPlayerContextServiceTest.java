package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Includes;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class PaperPlayerContextServiceTest {
    private static final Includes BASE_ONLY =
            new Includes(false, false, false, false, false, false, false);

    @Test
    void capturesExactlyOnceThroughTheMainThreadBoundary() throws Exception {
        GetPlayerContext.Result expected = result();
        DirectMainThread mainThread = new DirectMainThread();
        PaperPlayerContextService service =
                new PaperPlayerContextService(
                        mainThread, ignored -> expected, new InspectionAdmission(1));

        assertEquals(expected, service.getPlayerContext(request("Builder", BASE_ONLY)));
        assertEquals(1, mainThread.calls);
    }

    @Test
    void validatesSelectorsBeforeCallingPaper() {
        AtomicBoolean captured = new AtomicBoolean();
        PaperPlayerContextService service =
                new PaperPlayerContextService(
                        new DirectMainThread(),
                        ignored -> {
                            captured.set(true);
                            return result();
                        },
                        new InspectionAdmission(1));

        OperationException selector =
                assertThrows(
                        OperationException.class,
                        () -> service.getPlayerContext(request(" ", BASE_ONLY)));
        assertEquals(OperationFailure.INVALID_REQUEST, selector.failure());
        assertEquals(
                new ErrorDetails.InvalidRequest.InvalidValue("player"),
                selector.details().orElseThrow());

        String oversizedPlayer = "x".repeat(GetPlayerContext.MAX_PLAYER_SELECTOR_LENGTH + 1);
        OperationException oversizedSelector =
                assertThrows(
                        OperationException.class,
                        () -> service.getPlayerContext(request(oversizedPlayer, BASE_ONLY)));
        assertEquals("player must contain at most 36 characters", oversizedSelector.getMessage());
        assertEquals(
                new ErrorDetails.InvalidRequest.OutOfRange(
                        "player.length", oversizedPlayer.length(), 1, 36),
                oversizedSelector.details().orElseThrow());

        assertEquals(false, captured.get());
    }

    @Test
    void measuresPlayerSelectorLengthInUnicodeCharacters() throws Exception {
        GetPlayerContext.Result expected = result();
        PaperPlayerContextService service =
                new PaperPlayerContextService(
                        new DirectMainThread(), ignored -> expected, new InspectionAdmission(1));

        assertEquals(
                expected, service.getPlayerContext(request("\ud83d\udee0".repeat(36), BASE_ONLY)));
    }

    @Test
    void preservesTypedPaperFailures() {
        OperationException expected =
                new OperationException(
                        OperationFailure.PLAYER_UNAVAILABLE,
                        "Player is spectating another entity: Builder",
                        new ErrorDetails.PlayerUnavailable.SpectatingEntity("Builder"));
        PaperPlayerContextService service =
                new PaperPlayerContextService(
                        new DirectMainThread(),
                        ignored -> {
                            throw expected;
                        },
                        new InspectionAdmission(1));

        assertEquals(
                expected,
                assertThrows(
                        OperationException.class,
                        () -> service.getPlayerContext(request("Builder", BASE_ONLY))));
    }

    @Test
    void mapsSchedulerFailureWithoutLeakingItsCause() {
        PaperPlayerContextService service =
                new PaperPlayerContextService(
                        new FailingMainThread(), ignored -> result(), new InspectionAdmission(1));

        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () -> service.getPlayerContext(request("Builder", BASE_ONLY)));

        assertEquals(OperationFailure.SERVER_UNAVAILABLE, failure.failure());
        assertEquals(
                new ErrorDetails.ServerUnavailable.PaperUnavailable(),
                failure.details().orElseThrow());
    }

    @Test
    void distinguishesAnInterruptedSchedulerWait() {
        PaperPlayerContextService service =
                new PaperPlayerContextService(
                        new InterruptedMainThread(),
                        ignored -> result(),
                        new InspectionAdmission(1));

        try {
            OperationException failure =
                    assertThrows(
                            OperationException.class,
                            () -> service.getPlayerContext(request("Builder", BASE_ONLY)));
            assertEquals(OperationFailure.WORLD_UNAVAILABLE, failure.failure());
            assertEquals(
                    new ErrorDetails.WorldUnavailable.Interrupted(),
                    failure.details().orElseThrow());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sharesInspectionAdmissionWithoutCallingPaperWhenSaturated() throws Exception {
        InspectionAdmission admission = new InspectionAdmission(1);
        AtomicBoolean captured = new AtomicBoolean();
        PaperPlayerContextService service =
                new PaperPlayerContextService(
                        new DirectMainThread(),
                        ignored -> {
                            captured.set(true);
                            return result();
                        },
                        admission);

        OperationException failure =
                admission.execute(
                        () ->
                                assertThrows(
                                        OperationException.class,
                                        () ->
                                                service.getPlayerContext(
                                                        request("Builder", BASE_ONLY))));

        assertEquals(OperationFailure.SERVER_UNAVAILABLE, failure.failure());
        assertEquals(
                new ErrorDetails.ServerUnavailable.InspectionBusy(1),
                failure.details().orElseThrow());
        assertEquals(false, captured.get());
    }

    @Test
    void requiresAllCollaborators() {
        InspectionAdmission admission = new InspectionAdmission(1);

        assertThrows(
                NullPointerException.class,
                () -> new PaperPlayerContextService(null, ignored -> result(), admission));
        assertThrows(
                NullPointerException.class,
                () -> new PaperPlayerContextService(new DirectMainThread(), null, admission));
        assertThrows(
                NullPointerException.class,
                () ->
                        new PaperPlayerContextService(
                                new DirectMainThread(), ignored -> result(), null));
    }

    private static GetPlayerContext.Request request(String player, Includes includes) {
        return new GetPlayerContext.Request(player, includes);
    }

    private static GetPlayerContext.Result result() {
        return new GetPlayerContext.Result(
                Instant.parse("2026-08-20T12:00:00Z"),
                new PlayerIdentity(
                        "Builder", UUID.fromString("123e4567-e89b-42d3-a456-426614174000")),
                "world",
                UUID.fromString("223e4567-e89b-42d3-a456-426614174000"),
                new ExactPosition(1.25, 64, -2.5),
                new BlockPosition(1, 64, -3),
                new ExactPosition(1.25, 65.62, -2.5),
                new Rotation(90, -10),
                new Vector3(-1, 0, 0),
                "creative",
                "standing",
                true,
                null,
                null,
                null,
                null,
                null,
                null,
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

    private static final class InterruptedMainThread implements MainThread {
        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            Thread.currentThread().interrupt();
            throw new PaperMainThreadException("interrupted");
        }

        @Override
        public void close() {}
    }
}
