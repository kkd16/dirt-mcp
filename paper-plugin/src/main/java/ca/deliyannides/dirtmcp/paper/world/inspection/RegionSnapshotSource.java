package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface RegionSnapshotSource {
    CapturedRegion capture(
            String world,
            Cuboid region,
            List<String> includeBlockStatePatterns,
            List<String> excludeBlockStatePatterns)
            throws OperationException;

    interface CapturedRegion {
        String worldName();

        BlockSample sample(BlockPosition position);
    }

    record BlockSample(String blockState, boolean air, boolean selectedByPatterns) {
        public BlockSample {
            Objects.requireNonNull(blockState, "blockState");
        }
    }
}
