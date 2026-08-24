package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;

@FunctionalInterface
public interface ScanOrthographicView {
    ExactBlockStructure scanOrthographicView(Request request) throws OperationException;

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
            int depth,
            int maxResults) {}
}
