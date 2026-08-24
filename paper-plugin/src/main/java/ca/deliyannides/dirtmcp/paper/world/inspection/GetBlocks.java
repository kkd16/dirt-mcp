package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
            includeBlockStatePatterns = immutableCopy(includeBlockStatePatterns);
            excludeBlockStatePatterns = immutableCopy(excludeBlockStatePatterns);
        }
    }

    private static List<String> immutableCopy(List<String> values) {
        // Preserve malformed null entries for operation-layer validation.
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
