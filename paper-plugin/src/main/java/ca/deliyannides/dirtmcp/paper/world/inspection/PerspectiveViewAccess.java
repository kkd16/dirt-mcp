package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ResolvedSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.Result;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.Source;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewBasis;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewRequest;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Main-thread Paper access required to capture and ray trace a perspective view. */
public interface PerspectiveViewAccess {
    CameraSnapshot captureCamera(Source source) throws OperationException;

    Result trace(ViewRequest request, CameraSnapshot camera, Projection projection)
            throws OperationException;

    record CameraSnapshot(
            Instant capturedAt,
            ResolvedSource source,
            String world,
            UUID worldId,
            int minimumHeight,
            int maximumHeight,
            ExactPosition position,
            Rotation rotation,
            Vector3 lookDirection) {
        public CameraSnapshot {
            Objects.requireNonNull(capturedAt, "capturedAt");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(worldId, "worldId");
            if (minimumHeight >= maximumHeight) {
                throw new IllegalArgumentException("World height range must not be empty");
            }
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(lookDirection, "lookDirection");
        }
    }

    record Projection(
            ViewBasis basis,
            double horizontalFieldOfViewDegrees,
            List<Vector3> directions,
            Set<Chunk> requiredChunks) {
        public Projection {
            Objects.requireNonNull(basis, "basis");
            directions = List.copyOf(directions);
            requiredChunks = Collections.unmodifiableSet(new LinkedHashSet<>(requiredChunks));
        }
    }

    record Chunk(int x, int z) {}
}
