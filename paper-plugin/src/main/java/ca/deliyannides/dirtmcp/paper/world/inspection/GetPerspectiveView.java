package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface GetPerspectiveView {
    Result getPerspectiveView(Request request) throws OperationException;

    record Request(Source source, ViewRequest options) {}

    sealed interface Source permits PlayerSource, LocationSource {}

    record PlayerSource(String player) implements Source {}

    record LocationSource(String world, ExactPosition cameraPosition, Rotation rotation)
            implements Source {}

    enum FluidCollision {
        NEVER,
        SOURCE_ONLY,
        ALWAYS
    }

    record ViewRequest(
            int width,
            int height,
            int verticalFieldOfViewDegrees,
            int maxDistance,
            FluidCollision fluidCollision,
            boolean ignorePassableBlocks) {}

    sealed interface ResolvedSource permits ResolvedPlayerSource, ResolvedLocationSource {}

    record ResolvedPlayerSource(String type, PlayerIdentity player) implements ResolvedSource {
        public ResolvedPlayerSource(PlayerIdentity player) {
            this("player", player);
        }

        public ResolvedPlayerSource {
            if (!"player".equals(type)) {
                throw new IllegalArgumentException("Resolved player source type must be player");
            }
            Objects.requireNonNull(player, "player");
        }
    }

    record ResolvedLocationSource(String type) implements ResolvedSource {
        public ResolvedLocationSource() {
            this("location");
        }

        public ResolvedLocationSource {
            if (!"location".equals(type)) {
                throw new IllegalArgumentException(
                        "Resolved location source type must be location");
            }
        }
    }

    record Result(
            Instant capturedAt,
            ResolvedSource source,
            String world,
            UUID worldId,
            ExactPosition cameraPosition,
            Rotation rotation,
            Vector3 lookDirection,
            ViewBasis basis,
            Viewport viewport,
            int checkedChunkCount,
            List<String> blockStatePalette,
            List<ViewHit> hits,
            Integer crosshairHitIndex) {
        public Result {
            Objects.requireNonNull(capturedAt, "capturedAt");
            Objects.requireNonNull(source, "source");
            world = requireText(world, "world");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(cameraPosition, "cameraPosition");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(lookDirection, "lookDirection");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(viewport, "viewport");
            if (checkedChunkCount < 0) {
                throw new IllegalArgumentException("checkedChunkCount must be non-negative");
            }
            blockStatePalette = List.copyOf(blockStatePalette);
            Set<String> uniqueBlockStates = new HashSet<>();
            for (String blockState : blockStatePalette) {
                if (!uniqueBlockStates.add(requireText(blockState, "blockStatePalette[]"))) {
                    throw new IllegalArgumentException("View palette entries must be unique");
                }
            }
            hits = List.copyOf(hits);
            if (hits.size() > (long) viewport.width() * viewport.height()) {
                throw new IllegalArgumentException("View hits must fit within the viewport");
            }
            int previousCell = -1;
            int discoveredPaletteEntries = 0;
            Integer actualCrosshairHitIndex = null;
            int centerRow = viewport.height() / 2;
            int centerColumn = viewport.width() / 2;
            for (int hitIndex = 0; hitIndex < hits.size(); hitIndex++) {
                ViewHit hit = hits.get(hitIndex);
                if (hit.row() >= viewport.height() || hit.column() >= viewport.width()) {
                    throw new IllegalArgumentException("View hit is outside the viewport");
                }
                int cell = hit.row() * viewport.width() + hit.column();
                if (cell <= previousCell) {
                    throw new IllegalArgumentException(
                            "View hits must be in strictly increasing row-major order");
                }
                previousCell = cell;
                if (hit.blockStateIndex() > blockStatePalette.size()) {
                    throw new IllegalArgumentException(
                            "View hit references a missing palette entry");
                }
                if (hit.blockStateIndex() > discoveredPaletteEntries + 1) {
                    throw new IllegalArgumentException(
                            "View palette entries must be indexed by first appearance");
                }
                discoveredPaletteEntries =
                        Math.max(discoveredPaletteEntries, hit.blockStateIndex());
                if (hit.row() == centerRow && hit.column() == centerColumn) {
                    actualCrosshairHitIndex = hitIndex;
                }
            }
            if (discoveredPaletteEntries != blockStatePalette.size()) {
                throw new IllegalArgumentException("View palette contains unused entries");
            }
            if (!Objects.equals(crosshairHitIndex, actualCrosshairHitIndex)) {
                throw new IllegalArgumentException(
                        "crosshairHitIndex must identify the center ray's hit");
            }
        }
    }

    record ViewBasis(Vector3 forward, Vector3 right, Vector3 up) {
        public ViewBasis {
            Objects.requireNonNull(forward, "forward");
            Objects.requireNonNull(right, "right");
            Objects.requireNonNull(up, "up");
        }
    }

    record Viewport(
            int width,
            int height,
            int verticalFieldOfViewDegrees,
            double horizontalFieldOfViewDegrees,
            int maxDistance,
            String fluidCollision,
            boolean ignorePassableBlocks) {
        public Viewport {
            if (width < 1
                    || width > 255
                    || height < 1
                    || height > 255
                    || (width & 1) == 0
                    || (height & 1) == 0) {
                throw new IllegalArgumentException("Viewport dimensions must be odd and bounded");
            }
            if (verticalFieldOfViewDegrees < 1 || verticalFieldOfViewDegrees > 170) {
                throw new IllegalArgumentException("Viewport vertical FOV is invalid");
            }
            if (maxDistance < 1 || maxDistance > 128) {
                throw new IllegalArgumentException("Viewport maxDistance is invalid");
            }
            requireFinite(horizontalFieldOfViewDegrees, "horizontalFieldOfViewDegrees");
            if (horizontalFieldOfViewDegrees <= 0 || horizontalFieldOfViewDegrees >= 180) {
                throw new IllegalArgumentException(
                        "horizontalFieldOfViewDegrees must be between 0 and 180");
            }
            fluidCollision = requireText(fluidCollision, "fluidCollision");
            if (!Set.of("never", "source_only", "always").contains(fluidCollision)) {
                throw new IllegalArgumentException("Viewport fluidCollision is invalid");
            }
        }
    }

    record ViewHit(
            int row,
            int column,
            int blockStateIndex,
            BlockPosition blockPosition,
            ExactPosition hitPosition,
            String face,
            double distance) {
        public ViewHit {
            if (row < 0 || column < 0 || blockStateIndex < 1) {
                throw new IllegalArgumentException("View hit indexes must be non-negative");
            }
            Objects.requireNonNull(blockPosition, "blockPosition");
            Objects.requireNonNull(hitPosition, "hitPosition");
            if (face != null) {
                face = requireText(face, "face");
                if (!Set.of("up", "down", "north", "east", "south", "west").contains(face)) {
                    throw new IllegalArgumentException("face must be a six-way block face");
                }
            }
            requireFinite(distance, "distance");
            if (distance < 0) {
                throw new IllegalArgumentException("distance must be non-negative");
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
