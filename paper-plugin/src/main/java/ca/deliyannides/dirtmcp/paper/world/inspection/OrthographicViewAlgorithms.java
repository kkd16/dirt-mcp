package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.BlockSample;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.AxisVector;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.Direction;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.Request;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.ViewBasis;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.ViewBlock;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.ViewOffset;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

final class OrthographicViewAlgorithms {
    private OrthographicViewAlgorithms() {}

    static ViewGeometry geometry(Request request, long maximumVolume) throws OperationException {
        if (request.direction() == null) {
            throw invalid("direction is required");
        }
        if (request.horizontalRadius() < 0 || request.verticalRadius() < 0) {
            throw invalid("horizontalRadius and verticalRadius must be non-negative");
        }
        if (request.maxDistance() < 1) {
            throw invalid("maxDistance must be positive");
        }

        long horizontalSize = 2L * request.horizontalRadius() + 1;
        long verticalSize = 2L * request.verticalRadius() + 1;
        BigInteger requestedVolume =
                BigInteger.valueOf(horizontalSize)
                        .multiply(BigInteger.valueOf(verticalSize))
                        .multiply(BigInteger.valueOf(request.maxDistance()));
        if (requestedVolume.compareTo(BigInteger.valueOf(maximumVolume)) > 0) {
            throw new OperationException(
                    OperationFailure.REGION_TOO_LARGE,
                    "View scan volume "
                            + requestedVolume
                            + " exceeds the maximum of "
                            + maximumVolume
                            + " blocks");
        }
        long scannedVolume = requestedVolume.longValueExact();

        ViewBasis basis = viewBasis(request.direction());
        BlockPosition firstCorner =
                viewPosition(
                        request.origin(),
                        basis,
                        -request.horizontalRadius(),
                        -request.verticalRadius(),
                        1);
        BlockPosition min = firstCorner;
        BlockPosition max = firstCorner;
        int[] horizontalOffsets = {-request.horizontalRadius(), request.horizontalRadius()};
        int[] verticalOffsets = {-request.verticalRadius(), request.verticalRadius()};
        int[] distances = {1, request.maxDistance()};
        for (int horizontal : horizontalOffsets) {
            for (int vertical : verticalOffsets) {
                for (int distance : distances) {
                    BlockPosition corner =
                            viewPosition(request.origin(), basis, horizontal, vertical, distance);
                    min = minimum(min, corner);
                    max = maximum(max, corner);
                }
            }
        }

        Cuboid region = RegionGeometry.normalize(min, max, maximumVolume);
        if (region.volume() != scannedVolume) {
            throw new IllegalStateException("View bounds do not match its scan volume");
        }
        return new ViewGeometry(basis, region, scannedVolume);
    }

    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
    static List<ViewBlock> collectVisibleBlocks(
            Request request, ViewGeometry geometry, CapturedRegion capture)
            throws OperationException {
        List<ViewBlock> blocks = new ArrayList<>();
        for (int vertical = request.verticalRadius();
                vertical >= -request.verticalRadius();
                vertical--) {
            for (int horizontal = -request.horizontalRadius();
                    horizontal <= request.horizontalRadius();
                    horizontal++) {
                for (int distance = 1; ; distance++) {
                    BlockPosition position =
                            viewPosition(
                                    request.origin(),
                                    geometry.basis(),
                                    horizontal,
                                    vertical,
                                    distance);
                    BlockSample sample = capture.sample(position);
                    if (sample.air()) {
                        if (distance == request.maxDistance()) {
                            break;
                        }
                        continue;
                    }
                    blocks.add(
                            new ViewBlock(
                                    position,
                                    new ViewOffset(horizontal, vertical, distance),
                                    sample.blockState()));
                    if (blocks.size() > request.maxResults()) {
                        throw new OperationException(
                                OperationFailure.RESULT_TOO_LARGE,
                                "View result exceeds maxResults of "
                                        + request.maxResults()
                                        + " visible blocks");
                    }
                    break;
                }
            }
        }
        return List.copyOf(blocks);
    }

    private static ViewBasis viewBasis(Direction direction) {
        AxisVector worldUp = new AxisVector(0, 1, 0);
        return switch (direction) {
            case NORTH -> new ViewBasis(new AxisVector(0, 0, -1), new AxisVector(1, 0, 0), worldUp);
            case EAST -> new ViewBasis(new AxisVector(1, 0, 0), new AxisVector(0, 0, 1), worldUp);
            case SOUTH -> new ViewBasis(new AxisVector(0, 0, 1), new AxisVector(-1, 0, 0), worldUp);
            case WEST -> new ViewBasis(new AxisVector(-1, 0, 0), new AxisVector(0, 0, -1), worldUp);
            case UP ->
                    new ViewBasis(
                            new AxisVector(0, 1, 0),
                            new AxisVector(1, 0, 0),
                            new AxisVector(0, 0, -1));
            case DOWN ->
                    new ViewBasis(
                            new AxisVector(0, -1, 0),
                            new AxisVector(1, 0, 0),
                            new AxisVector(0, 0, -1));
        };
    }

    private static BlockPosition viewPosition(
            BlockPosition origin, ViewBasis basis, int horizontal, int vertical, int distance)
            throws OperationException {
        long x =
                origin.x()
                        + (long) basis.horizontal().x() * horizontal
                        + (long) basis.vertical().x() * vertical
                        + (long) basis.forward().x() * distance;
        long y =
                origin.y()
                        + (long) basis.horizontal().y() * horizontal
                        + (long) basis.vertical().y() * vertical
                        + (long) basis.forward().y() * distance;
        long z =
                origin.z()
                        + (long) basis.horizontal().z() * horizontal
                        + (long) basis.vertical().z() * vertical
                        + (long) basis.forward().z() * distance;
        if (x < Integer.MIN_VALUE
                || x > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE
                || y > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE
                || z > Integer.MAX_VALUE) {
            throw invalid("View extends beyond signed 32-bit block coordinates");
        }
        return new BlockPosition((int) x, (int) y, (int) z);
    }

    private static BlockPosition minimum(BlockPosition first, BlockPosition second) {
        return new BlockPosition(
                Math.min(first.x(), second.x()),
                Math.min(first.y(), second.y()),
                Math.min(first.z(), second.z()));
    }

    private static BlockPosition maximum(BlockPosition first, BlockPosition second) {
        return new BlockPosition(
                Math.max(first.x(), second.x()),
                Math.max(first.y(), second.y()),
                Math.max(first.z(), second.z()));
    }

    private static OperationException invalid(String message) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message);
    }

    record ViewGeometry(ViewBasis basis, Cuboid region, long scannedVolume) {}
}
