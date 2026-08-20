package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.OrthographicViewAlgorithms.ViewGeometry;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.BlockSample;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.AxisVector;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.Direction;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.Request;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.ViewBasis;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.ViewBlock;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.ViewOffset;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class OrthographicViewAlgorithmsTest {
    @Test
    void mapsEveryDirectionToMinecraftWorldAxes() {
        BlockPosition origin = new BlockPosition(10, 20, 30);
        ViewBasis verticalBasis =
                new ViewBasis(
                        new AxisVector(0, 1, 0), new AxisVector(1, 0, 0), new AxisVector(0, 0, -1));

        assertAll(
                () ->
                        assertGeometry(
                                request(origin, Direction.NORTH, 1, 2, 3, 10),
                                new ViewBasis(
                                        new AxisVector(0, 0, -1),
                                        new AxisVector(1, 0, 0),
                                        new AxisVector(0, 1, 0)),
                                new BlockPosition(9, 18, 27),
                                new BlockPosition(11, 22, 29)),
                () ->
                        assertGeometry(
                                request(origin, Direction.EAST, 1, 2, 3, 10),
                                new ViewBasis(
                                        new AxisVector(1, 0, 0),
                                        new AxisVector(0, 0, 1),
                                        new AxisVector(0, 1, 0)),
                                new BlockPosition(11, 18, 29),
                                new BlockPosition(13, 22, 31)),
                () ->
                        assertGeometry(
                                request(origin, Direction.SOUTH, 1, 2, 3, 10),
                                new ViewBasis(
                                        new AxisVector(0, 0, 1),
                                        new AxisVector(-1, 0, 0),
                                        new AxisVector(0, 1, 0)),
                                new BlockPosition(9, 18, 31),
                                new BlockPosition(11, 22, 33)),
                () ->
                        assertGeometry(
                                request(origin, Direction.WEST, 1, 2, 3, 10),
                                new ViewBasis(
                                        new AxisVector(-1, 0, 0),
                                        new AxisVector(0, 0, -1),
                                        new AxisVector(0, 1, 0)),
                                new BlockPosition(7, 18, 29),
                                new BlockPosition(9, 22, 31)),
                () ->
                        assertGeometry(
                                request(origin, Direction.UP, 1, 2, 3, 10),
                                verticalBasis,
                                new BlockPosition(9, 21, 28),
                                new BlockPosition(11, 23, 32)),
                () ->
                        assertGeometry(
                                request(origin, Direction.DOWN, 1, 2, 3, 10),
                                new ViewBasis(
                                        new AxisVector(0, -1, 0),
                                        verticalBasis.horizontal(),
                                        verticalBasis.vertical()),
                                new BlockPosition(9, 17, 28),
                                new BlockPosition(11, 19, 32)));
    }

    @Test
    void returnsFirstNonAirBlockPerSightlineInViewOrder() throws Exception {
        Request request = request(new BlockPosition(0, 0, 0), Direction.NORTH, 1, 1, 3, 10);
        ViewGeometry geometry = OrthographicViewAlgorithms.geometry(request, 1_000);
        Map<BlockPosition, BlockSample> states =
                Map.of(
                        new BlockPosition(-1, 1, -2), solid("glass"),
                        new BlockPosition(-1, 1, -3), solid("stone"),
                        new BlockPosition(1, 1, -1), solid("stairs"),
                        new BlockPosition(0, 0, -3), solid("gold"));

        List<ViewBlock> blocks =
                OrthographicViewAlgorithms.collectVisibleBlocks(
                        request, geometry, viewCapture(states));

        assertEquals(
                List.of(
                        new ViewBlock(
                                new BlockPosition(-1, 1, -2), new ViewOffset(-1, 1, 2), "glass"),
                        new ViewBlock(
                                new BlockPosition(1, 1, -1), new ViewOffset(1, 1, 1), "stairs"),
                        new ViewBlock(
                                new BlockPosition(0, 0, -3), new ViewOffset(0, 0, 3), "gold")),
                blocks);
    }

    @Test
    void rejectsOversizedOverflowingAndInvalidViews() {
        Request oversized = request(new BlockPosition(0, 0, 0), Direction.NORTH, 100, 100, 1, 10);
        Request overflowing =
                request(new BlockPosition(Integer.MAX_VALUE, 0, 0), Direction.EAST, 0, 0, 1, 10);

        OperationException oversizedFailure =
                assertThrows(
                        OperationException.class,
                        () -> OrthographicViewAlgorithms.geometry(oversized, 32_768));
        OperationException overflowFailure =
                assertThrows(
                        OperationException.class,
                        () -> OrthographicViewAlgorithms.geometry(overflowing, 32_768));
        assertAll(
                () -> assertEquals(OperationFailure.REGION_TOO_LARGE, oversizedFailure.failure()),
                () ->
                        assertEquals(
                                "View scan volume 40401 exceeds the maximum of 32768 blocks",
                                oversizedFailure.getMessage()),
                () -> assertEquals(OperationFailure.INVALID_REQUEST, overflowFailure.failure()),
                () ->
                        assertEquals(
                                OperationFailure.INVALID_REQUEST,
                                assertThrows(
                                                OperationException.class,
                                                () ->
                                                        OrthographicViewAlgorithms.geometry(
                                                                request(
                                                                        new BlockPosition(0, 0, 0),
                                                                        null,
                                                                        0,
                                                                        0,
                                                                        1,
                                                                        1),
                                                                10))
                                        .failure()),
                () ->
                        assertEquals(
                                OperationFailure.INVALID_REQUEST,
                                assertThrows(
                                                OperationException.class,
                                                () ->
                                                        OrthographicViewAlgorithms.geometry(
                                                                request(
                                                                        new BlockPosition(0, 0, 0),
                                                                        Direction.NORTH,
                                                                        -1,
                                                                        0,
                                                                        1,
                                                                        1),
                                                                10))
                                        .failure()),
                () ->
                        assertEquals(
                                OperationFailure.INVALID_REQUEST,
                                assertThrows(
                                                OperationException.class,
                                                () ->
                                                        OrthographicViewAlgorithms.geometry(
                                                                request(
                                                                        new BlockPosition(0, 0, 0),
                                                                        Direction.NORTH,
                                                                        0,
                                                                        0,
                                                                        0,
                                                                        1),
                                                                10))
                                        .failure()));
    }

    @Test
    void rejectsVisibleResultsOverCapWithoutTruncating() throws Exception {
        Request request = request(new BlockPosition(0, 0, 0), Direction.NORTH, 1, 0, 1, 1);
        ViewGeometry geometry = OrthographicViewAlgorithms.geometry(request, 100);

        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () ->
                                OrthographicViewAlgorithms.collectVisibleBlocks(
                                        request,
                                        geometry,
                                        new CapturedRegion() {
                                            @Override
                                            public String worldName() {
                                                return "world";
                                            }

                                            @Override
                                            public BlockSample sample(BlockPosition position) {
                                                return solid("stone");
                                            }
                                        }));

        assertEquals(OperationFailure.RESULT_TOO_LARGE, exception.failure());
    }

    private static void assertGeometry(
            Request request, ViewBasis basis, BlockPosition min, BlockPosition max)
            throws OperationException {
        ViewGeometry geometry = OrthographicViewAlgorithms.geometry(request, 1_000);
        assertEquals(basis, geometry.basis());
        assertEquals(min, geometry.region().min());
        assertEquals(max, geometry.region().max());
        assertEquals(45, geometry.scannedVolume());
    }

    private static Request request(
            BlockPosition origin,
            Direction direction,
            int horizontalRadius,
            int verticalRadius,
            int maxDistance,
            int maxResults) {
        return new Request(
                "world",
                origin,
                direction,
                horizontalRadius,
                verticalRadius,
                maxDistance,
                maxResults);
    }

    private static CapturedRegion viewCapture(Map<BlockPosition, BlockSample> states) {
        return new CapturedRegion() {
            @Override
            public String worldName() {
                return "world";
            }

            @Override
            public BlockSample sample(BlockPosition position) {
                return states.getOrDefault(position, new BlockSample("air", true, true));
            }
        };
    }

    private static BlockSample solid(String state) {
        return new BlockSample(state, false, true);
    }
}
