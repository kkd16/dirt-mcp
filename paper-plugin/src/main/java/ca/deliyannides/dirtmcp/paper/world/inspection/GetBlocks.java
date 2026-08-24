package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface GetBlocks {
    ExactBlockStructure getBlocks(Request request) throws OperationException;

    record Request(
            String world,
            BlockPosition min,
            BlockPosition max,
            List<String> includeBlockStatePatterns,
            List<String> excludeBlockStatePatterns,
            boolean includeAir,
            int maxResults) {
        public Request {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            includeBlockStatePatterns = List.copyOf(includeBlockStatePatterns);
            excludeBlockStatePatterns = List.copyOf(excludeBlockStatePatterns);
        }
    }
}
