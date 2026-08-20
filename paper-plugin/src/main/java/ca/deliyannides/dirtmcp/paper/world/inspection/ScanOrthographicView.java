package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface ScanOrthographicView {
    Result scanOrthographicView(Request request) throws OperationException;

    enum Direction {
        NORTH,
        EAST,
        SOUTH,
        WEST,
        UP,
        DOWN
    }

    record Request(
            String world,
            BlockPosition origin,
            Direction direction,
            int horizontalRadius,
            int verticalRadius,
            int maxDistance,
            int maxResults) {}

    record AxisVector(int x, int y, int z) {}

    record ViewBasis(AxisVector forward, AxisVector horizontal, AxisVector vertical) {
        public ViewBasis {
            Objects.requireNonNull(forward, "forward");
            Objects.requireNonNull(horizontal, "horizontal");
            Objects.requireNonNull(vertical, "vertical");
        }
    }

    record Viewport(int horizontalRadius, int verticalRadius, int maxDistance) {}

    record ViewOffset(int horizontal, int vertical, int distance) {}

    record ViewBlock(BlockPosition position, ViewOffset offset, String blockState) {
        public ViewBlock {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(offset, "offset");
            Objects.requireNonNull(blockState, "blockState");
        }
    }

    record Result(
            String world,
            BlockPosition origin,
            String direction,
            String format,
            ViewBasis basis,
            Viewport viewport,
            BlockBounds bounds,
            long scannedVolume,
            long visibleBlockCount,
            List<ViewBlock> blocks) {
        public Result {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(viewport, "viewport");
            Objects.requireNonNull(bounds, "bounds");
            if (scannedVolume < 1) {
                throw new IllegalArgumentException("Scanned volume must be positive");
            }
            if (visibleBlockCount < 0) {
                throw new IllegalArgumentException("Visible block count must be non-negative");
            }
            blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        }
    }
}
