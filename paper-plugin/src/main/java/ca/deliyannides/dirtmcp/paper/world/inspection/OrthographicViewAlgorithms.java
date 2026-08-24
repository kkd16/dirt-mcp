package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.BlockSample;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.AxisVector;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.Direction;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.Request;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.ViewBasis;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class OrthographicViewAlgorithms {
    private OrthographicViewAlgorithms() {}

    static ViewGeometry geometry(Request request, int maximumVolume) throws OperationException {
        if (request.direction() == null) {
            throw invalid(
                    "direction is required", new ErrorDetails.InvalidRequest.Missing("direction"));
        }
        if (request.horizontalRadius() < 0 || request.verticalRadius() < 0) {
            boolean horizontalInvalid = request.horizontalRadius() < 0;
            String field = horizontalInvalid ? "horizontalRadius" : "verticalRadius";
            int value = horizontalInvalid ? request.horizontalRadius() : request.verticalRadius();
            throw invalid(
                    "horizontalRadius and verticalRadius must be non-negative",
                    new ErrorDetails.InvalidRequest.OutOfRange(field, value, 0, Integer.MAX_VALUE));
        }
        if (request.maxDistance() < 1) {
            throw invalid(
                    "maxDistance must be positive",
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            "maxDistance", request.maxDistance(), 1, Integer.MAX_VALUE));
        }
        if (request.depth() < 0) {
            throw invalid(
                    "depth must be non-negative",
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            "depth", request.depth(), 0, Integer.MAX_VALUE));
        }

        ViewBasis basis = viewBasis(request.direction());
        BlockPosition nearCorner =
                viewPosition(
                        request.origin(),
                        basis,
                        -request.horizontalRadius(),
                        -request.verticalRadius(),
                        1);
        BlockPosition farCorner =
                viewPosition(
                        request.origin(),
                        basis,
                        request.horizontalRadius(),
                        request.verticalRadius(),
                        request.maxDistance());
        return new ViewGeometry(
                basis, RegionGeometry.normalize(nearCorner, farCorner, maximumVolume));
    }

    static ViewGrid collectGrid(Request request, ViewGeometry geometry, CapturedRegion capture)
            throws OperationException {
        int width = Math.toIntExact((long) request.horizontalRadius() * 2 + 1);
        int height = Math.toIntExact((long) request.verticalRadius() * 2 + 1);
        List<String> blockStatePalette = new ArrayList<>();
        Map<String, Integer> paletteIndexes = new HashMap<>();
        List<List<Integer>> blockStateIndexRows = new ArrayList<>(height);
        List<List<Integer>> distanceRows = new ArrayList<>(height);
        long visibleCellCount = 0;
        for (int vertical = request.verticalRadius();
                vertical >= -request.verticalRadius();
                vertical--) {
            List<Integer> blockStateIndexRow = new ArrayList<>(width);
            List<Integer> distanceRow = new ArrayList<>(width);
            for (int horizontal = -request.horizontalRadius();
                    horizontal <= request.horizontalRadius();
                    horizontal++) {
                int remainingDepth = request.depth();
                int blockStateIndex = 0;
                int hitDistance = 0;
                for (int distance = 1; ; distance++) {
                    BlockPosition position =
                            viewPosition(
                                    request.origin(),
                                    geometry.basis(),
                                    horizontal,
                                    vertical,
                                    distance);
                    BlockSample sample = capture.sample(position);
                    if (!sample.air()) {
                        if (remainingDepth == 0) {
                            visibleCellCount++;
                            if (visibleCellCount > request.maxResults()) {
                                throw new OperationException(
                                        OperationFailure.RESULT_TOO_LARGE,
                                        "View result exceeds maxResults of "
                                                + request.maxResults()
                                                + " visible cells",
                                        new ErrorDetails.ResultTooLarge.VisibleCells(
                                                (long) request.maxResults() + 1,
                                                request.maxResults()));
                            }
                            Integer paletteIndex = paletteIndexes.get(sample.blockState());
                            if (paletteIndex == null) {
                                blockStatePalette.add(sample.blockState());
                                paletteIndex = blockStatePalette.size();
                                paletteIndexes.put(sample.blockState(), paletteIndex);
                            }
                            blockStateIndex = paletteIndex;
                            hitDistance = distance;
                            break;
                        }
                        remainingDepth--;
                    }
                    if (distance == request.maxDistance()) {
                        break;
                    }
                }
                blockStateIndexRow.add(blockStateIndex);
                distanceRow.add(hitDistance);
            }
            blockStateIndexRows.add(List.copyOf(blockStateIndexRow));
            distanceRows.add(List.copyOf(distanceRow));
        }
        return new ViewGrid(
                visibleCellCount,
                List.copyOf(blockStatePalette),
                List.copyOf(blockStateIndexRows),
                List.copyOf(distanceRows));
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
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) {
            throw viewOutOfRange("view.x", x);
        }
        if (y < Integer.MIN_VALUE || y > Integer.MAX_VALUE) {
            throw viewOutOfRange("view.y", y);
        }
        if (z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            throw viewOutOfRange("view.z", z);
        }
        return new BlockPosition((int) x, (int) y, (int) z);
    }

    private static OperationException viewOutOfRange(String field, long value) {
        return invalid(
                "View extends beyond signed 32-bit block coordinates",
                new ErrorDetails.InvalidRequest.OutOfRange(
                        field, value, Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    private static OperationException invalid(String message, ErrorDetails.InvalidRequest details) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message, details);
    }

    record ViewGeometry(ViewBasis basis, Cuboid region) {}

    record ViewGrid(
            long visibleCellCount,
            List<String> blockStatePalette,
            List<List<Integer>> blockStateIndexRows,
            List<List<Integer>> distanceRows) {}
}
