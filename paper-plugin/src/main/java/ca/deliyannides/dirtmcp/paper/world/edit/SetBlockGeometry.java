package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.List;
import java.util.Objects;

/** Absolute validated geometry that retains each wire run as one cuboid. */
record SetBlockGeometry(
        List<ResolvedPlacement> placements,
        List<ResolvedRun> runs,
        List<ChunkPosition> chunks,
        BlockBounds bounds,
        int blockCount) {
    SetBlockGeometry {
        placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
        runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        if (blockCount < 0) {
            throw new IllegalArgumentException("blockCount must be non-negative");
        }
        boolean empty = placements.isEmpty() && runs.isEmpty();
        boolean metadataEmpty = blockCount == 0 && bounds == null && chunks.isEmpty();
        boolean metadataComplete = blockCount > 0 && bounds != null && !chunks.isEmpty();
        if ((empty && !metadataEmpty) || (!empty && !metadataComplete)) {
            throw new IllegalArgumentException(
                    "Set-blocks bounds, chunks, and count must match its geometry");
        }
    }

    boolean isEmpty() {
        return this.blockCount == 0;
    }

    record ResolvedPlacement(int paletteIndex, BlockPosition position) {
        ResolvedPlacement {
            Objects.requireNonNull(position, "position");
        }
    }

    record ResolvedRun(int paletteIndex, Cuboid region) {
        ResolvedRun {
            Objects.requireNonNull(region, "region");
        }
    }
}
