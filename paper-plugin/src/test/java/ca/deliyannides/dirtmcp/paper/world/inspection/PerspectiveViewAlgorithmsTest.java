package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.FluidCollision;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewRequest;
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAlgorithms.ChunkCoordinate;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PerspectiveViewAlgorithmsTest {
    @Test
    void buildsAnOddRowMajorPerspectiveWithAnExactCenterRay() throws Exception {
        Vector3 forward = new Vector3(0, 0, 1);
        PerspectiveViewAlgorithms.Geometry geometry =
                PerspectiveViewAlgorithms.geometry(
                        view(3, 3, 70, 32, FluidCollision.NEVER, false), forward, 0, 9, 288);

        assertEquals(9, geometry.directions().size());
        assertEquals(forward, geometry.directions().get(4));
        assertEquals(new Vector3(-1, 0, 0), geometry.right());
        assertEquals(new Vector3(0, 1, 0), geometry.up());
        assertTrue(geometry.directions().getFirst().y() > 0);
        assertTrue(geometry.directions().getFirst().x() > 0);
    }

    @Test
    void keepsTheCameraBasisDefinedWhenLookingStraightUp() throws Exception {
        Vector3 forward = new Vector3(0, 1, 0);
        PerspectiveViewAlgorithms.Geometry geometry =
                PerspectiveViewAlgorithms.geometry(
                        view(1, 1, 70, 32, FluidCollision.NEVER, false), forward, 90, 1, 32);

        assertEquals(forward, geometry.directions().getFirst());
        assertEquals(1, length(geometry.right()), 1.0E-12);
        assertEquals(1, length(geometry.up()), 1.0E-12);
        assertEquals(0, dot(geometry.right(), geometry.up()), 1.0E-12);
    }

    @Test
    void enforcesRayAndDistanceBudgetsIndependently() {
        OperationException rays =
                assertThrows(
                        OperationException.class,
                        () ->
                                PerspectiveViewAlgorithms.geometry(
                                        view(3, 3, 1, 2, FluidCollision.NEVER, false),
                                        new Vector3(0, 0, 1),
                                        0,
                                        8,
                                        100));
        assertEquals(OperationFailure.RESULT_TOO_LARGE, rays.failure());
        assertInstanceOf(
                ErrorDetails.ResultTooLarge.PerspectiveRays.class, rays.details().orElseThrow());

        OperationException distance =
                assertThrows(
                        OperationException.class,
                        () ->
                                PerspectiveViewAlgorithms.geometry(
                                        view(3, 3, 1, 12, FluidCollision.NEVER, false),
                                        new Vector3(0, 0, 1),
                                        0,
                                        9,
                                        107));
        assertInstanceOf(
                ErrorDetails.ResultTooLarge.PerspectiveRayDistance.class,
                distance.details().orElseThrow());
    }

    @Test
    void rejectsInvalidDimensionsButKeepsFluidAndPassableOptionsIndependent() {
        OperationException even =
                assertThrows(
                        OperationException.class,
                        () ->
                                PerspectiveViewAlgorithms.validate(
                                        view(2, 3, 70, 32, FluidCollision.NEVER, false)));
        assertEquals(
                new ErrorDetails.InvalidRequest.InvalidValue("width"),
                even.details().orElseThrow());

        assertDoesNotThrow(
                () ->
                        PerspectiveViewAlgorithms.validate(
                                view(3, 3, 70, 32, FluidCollision.ALWAYS, true)));
    }

    @Test
    void traversesNegativeChunksWithoutPositiveCoordinateRounding() {
        Set<ChunkCoordinate> chunks =
                PerspectiveViewAlgorithms.requiredChunks(
                        -0.5, 64.5, -0.5, -64, 320, 32, List.of(new Vector3(-1, 0, 0)));

        assertEquals(
                Set.of(
                        new ChunkCoordinate(-1, -1),
                        new ChunkCoordinate(-2, -1),
                        new ChunkCoordinate(-3, -1)),
                chunks);
    }

    @Test
    void checksOnlyThePaperTraversalCellForAVerticalBoundaryRay() {
        Set<ChunkCoordinate> chunks =
                PerspectiveViewAlgorithms.requiredChunks(
                        16, 64.5, 8, -64, 320, 32, List.of(new Vector3(0, 1, 0)));

        assertEquals(Set.of(new ChunkCoordinate(1, 0)), chunks);
    }

    @Test
    void includesANearBoundaryChunkReachedByPapersEndpointExpansion() {
        Set<ChunkCoordinate> chunks =
                PerspectiveViewAlgorithms.requiredChunks(
                        16.000001, 64.5, 8, -64, 320, 32, List.of(new Vector3(1, 0, 0)));

        assertTrue(chunks.contains(new ChunkCoordinate(0, 0)));
    }

    @Test
    void followsPapersTieOrderAtAnExactCorner() {
        double component = Math.sqrt(0.5);
        Set<ChunkCoordinate> chunks =
                PerspectiveViewAlgorithms.requiredChunks(
                        8, 64.5, 8, -64, 320, 12, List.of(new Vector3(component, 0, component)));

        assertEquals(
                Set.of(
                        new ChunkCoordinate(0, 0),
                        new ChunkCoordinate(0, 1),
                        new ChunkCoordinate(1, 1)),
                chunks);
    }

    @Test
    void returnsTheRayUnionInsteadOfItsBoundingRectangle() {
        Set<ChunkCoordinate> chunks =
                PerspectiveViewAlgorithms.requiredChunks(
                        8,
                        64.5,
                        8,
                        -64,
                        320,
                        32,
                        List.of(new Vector3(1, 0, 0), new Vector3(0, 0, 1)));

        assertEquals(5, chunks.size());
        assertTrue(chunks.contains(new ChunkCoordinate(2, 0)));
        assertTrue(chunks.contains(new ChunkCoordinate(0, 2)));
        assertEquals(false, chunks.contains(new ChunkCoordinate(1, 1)));
        assertThrows(
                UnsupportedOperationException.class, () -> chunks.add(new ChunkCoordinate(5, 5)));
    }

    @Test
    void skipsTraversalCellsOutsideTheWorldBuildHeight() {
        Set<ChunkCoordinate> upward =
                PerspectiveViewAlgorithms.requiredChunks(
                        15.5, 319.5, 8, -64, 320, 32, List.of(new Vector3(1, 10, 0)));
        Set<ChunkCoordinate> downward =
                PerspectiveViewAlgorithms.requiredChunks(
                        15.5, -63.5, 8, -64, 320, 32, List.of(new Vector3(1, -10, 0)));
        Set<ChunkCoordinate> entirelyAbove =
                PerspectiveViewAlgorithms.requiredChunks(
                        15.5, 400, 8, -64, 320, 32, List.of(new Vector3(1, 0, 0)));

        assertEquals(Set.of(new ChunkCoordinate(0, 0)), upward);
        assertEquals(Set.of(new ChunkCoordinate(0, 0)), downward);
        assertEquals(Set.of(), entirelyAbove);
    }

    @Test
    void detectsOnlyTraversalEndpointsWhoseFloorsExceedSignedBlockCoordinates() {
        double nearMaximum = (double) Integer.MAX_VALUE + 0.5;

        assertEquals(
                "x",
                PerspectiveViewAlgorithms.firstOutOfRangeEndpointAxis(
                        nearMaximum, 64.5, 0.5, 1, List.of(new Vector3(1, 0, 0))));
        assertEquals(
                null,
                PerspectiveViewAlgorithms.firstOutOfRangeEndpointAxis(
                        nearMaximum, 64.5, 0.5, 1, List.of(new Vector3(-1, 0, 0))));
    }

    private static ViewRequest view(
            int width,
            int height,
            int verticalFov,
            int distance,
            FluidCollision fluidCollision,
            boolean ignorePassable) {
        return new ViewRequest(
                width, height, verticalFov, distance, fluidCollision, ignorePassable);
    }

    private static double length(Vector3 value) {
        return Math.sqrt(dot(value, value));
    }

    private static double dot(Vector3 left, Vector3 right) {
        return left.x() * right.x() + left.y() * right.y() + left.z() * right.z();
    }
}
