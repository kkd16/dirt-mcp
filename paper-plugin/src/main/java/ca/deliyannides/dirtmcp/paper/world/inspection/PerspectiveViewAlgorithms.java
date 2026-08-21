package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewRequest;
import ca.deliyannides.dirtmcp.paper.world.model.BlockCoordinates;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PerspectiveViewAlgorithms {
    private static final int MAX_DIMENSION = 255;
    private static final int MIN_FIELD_OF_VIEW = 1;
    private static final int MAX_FIELD_OF_VIEW = 170;
    private static final int MAX_DISTANCE = 128;
    // BlockGetter.traverseBlocks expands both endpoints by this fraction of the ray.
    private static final double PAPER_TRAVERSAL_EPSILON = 1.0E-7;

    private PerspectiveViewAlgorithms() {}

    static Geometry geometry(
            ViewRequest request,
            Vector3 lookDirection,
            double yaw,
            int maximumRays,
            int maximumRayDistanceBudget)
            throws OperationException {
        validate(request);
        if (maximumRays < 1 || maximumRayDistanceBudget < 1) {
            throw new IllegalArgumentException("Perspective view limits must be positive");
        }

        long rayCount = (long) request.width() * request.height();
        if (rayCount > maximumRays) {
            throw new OperationException(
                    OperationFailure.RESULT_TOO_LARGE,
                    "Perspective view requires "
                            + rayCount
                            + " rays, exceeding the maximum of "
                            + maximumRays,
                    new ErrorDetails.ResultTooLarge.PerspectiveRays(rayCount, maximumRays));
        }
        long rayDistanceBudget = rayCount * request.maxDistance();
        if (rayDistanceBudget > maximumRayDistanceBudget) {
            throw new OperationException(
                    OperationFailure.RESULT_TOO_LARGE,
                    "Perspective view requires "
                            + rayDistanceBudget
                            + " ray-distance units, exceeding the maximum of "
                            + maximumRayDistanceBudget,
                    new ErrorDetails.ResultTooLarge.PerspectiveRayDistance(
                            rayDistanceBudget, maximumRayDistanceBudget));
        }

        Vector3 forward = normalize(lookDirection);
        double yawRadians = Math.toRadians(yaw);
        Vector3 right = new Vector3(-Math.cos(yawRadians), 0, -Math.sin(yawRadians));
        Vector3 up = normalize(cross(right, forward));
        double verticalScale = Math.tan(Math.toRadians(request.verticalFieldOfViewDegrees()) / 2.0);
        double aspectRatio = (double) request.width() / request.height();
        double horizontalScale = verticalScale * aspectRatio;
        double horizontalFieldOfViewDegrees = Math.toDegrees(2.0 * Math.atan(horizontalScale));

        List<Vector3> directions = new ArrayList<>((int) rayCount);
        for (int row = 0; row < request.height(); row++) {
            double vertical = (1.0 - (2.0 * (row + 0.5) / request.height())) * verticalScale;
            for (int column = 0; column < request.width(); column++) {
                double horizontal =
                        ((2.0 * (column + 0.5) / request.width()) - 1.0) * horizontalScale;
                directions.add(
                        normalize(add(forward, scale(right, horizontal), scale(up, vertical))));
            }
        }
        return new Geometry(forward, right, up, horizontalFieldOfViewDegrees, directions);
    }

    static void validate(ViewRequest request) throws OperationException {
        if (request == null) {
            throw invalid(
                    "Perspective view options are required",
                    new ErrorDetails.InvalidRequest.Missing("options"));
        }
        requireRange("width", request.width(), 1, MAX_DIMENSION);
        requireRange("height", request.height(), 1, MAX_DIMENSION);
        if ((request.width() & 1) == 0) {
            throw invalid(
                    "width must be odd so the raster has a center ray",
                    new ErrorDetails.InvalidRequest.InvalidValue("width"));
        }
        if ((request.height() & 1) == 0) {
            throw invalid(
                    "height must be odd so the raster has a center ray",
                    new ErrorDetails.InvalidRequest.InvalidValue("height"));
        }
        requireRange(
                "verticalFieldOfViewDegrees",
                request.verticalFieldOfViewDegrees(),
                MIN_FIELD_OF_VIEW,
                MAX_FIELD_OF_VIEW);
        requireRange("maxDistance", request.maxDistance(), 1, MAX_DISTANCE);
        if (request.fluidCollision() == null) {
            throw invalid(
                    "fluidCollision is required",
                    new ErrorDetails.InvalidRequest.Missing("fluidCollision"));
        }
    }

    static Set<ChunkCoordinate> requiredChunks(
            double originX,
            double originY,
            double originZ,
            int minimumHeight,
            int maximumHeight,
            int maxDistance,
            List<Vector3> directions) {
        if (!Double.isFinite(originX)
                || !Double.isFinite(originY)
                || !Double.isFinite(originZ)
                || minimumHeight >= maximumHeight
                || maxDistance < 1) {
            throw new IllegalArgumentException("Ray chunk traversal inputs are invalid");
        }
        Set<ChunkCoordinate> chunks = new LinkedHashSet<>();
        for (Vector3 direction : directions) {
            Vector3 normalized = normalize(direction);
            double endX = originX + normalized.x() * maxDistance;
            double endY = originY + normalized.y() * maxDistance;
            double endZ = originZ + normalized.z() * maxDistance;
            addTraversedChunks(
                    chunks,
                    lerp(-PAPER_TRAVERSAL_EPSILON, originX, endX),
                    lerp(-PAPER_TRAVERSAL_EPSILON, originY, endY),
                    lerp(-PAPER_TRAVERSAL_EPSILON, originZ, endZ),
                    lerp(-PAPER_TRAVERSAL_EPSILON, endX, originX),
                    lerp(-PAPER_TRAVERSAL_EPSILON, endY, originY),
                    lerp(-PAPER_TRAVERSAL_EPSILON, endZ, originZ),
                    minimumHeight,
                    maximumHeight);
        }
        return Collections.unmodifiableSet(chunks);
    }

    static String firstOutOfRangeEndpointAxis(
            double originX,
            double originY,
            double originZ,
            int maxDistance,
            List<Vector3> directions) {
        for (Vector3 direction : directions) {
            Vector3 normalized = normalize(direction);
            double endX = originX + normalized.x() * maxDistance;
            double endY = originY + normalized.y() * maxDistance;
            double endZ = originZ + normalized.z() * maxDistance;
            if (!BlockCoordinates.contains(lerp(-PAPER_TRAVERSAL_EPSILON, originX, endX))
                    || !BlockCoordinates.contains(lerp(-PAPER_TRAVERSAL_EPSILON, endX, originX))) {
                return "x";
            }
            if (!BlockCoordinates.contains(lerp(-PAPER_TRAVERSAL_EPSILON, originY, endY))
                    || !BlockCoordinates.contains(lerp(-PAPER_TRAVERSAL_EPSILON, endY, originY))) {
                return "y";
            }
            if (!BlockCoordinates.contains(lerp(-PAPER_TRAVERSAL_EPSILON, originZ, endZ))
                    || !BlockCoordinates.contains(lerp(-PAPER_TRAVERSAL_EPSILON, endZ, originZ))) {
                return "z";
            }
        }
        return null;
    }

    private static void addTraversedChunks(
            Set<ChunkCoordinate> chunks,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ,
            int minimumHeight,
            int maximumHeight) {
        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double deltaZ = endZ - startZ;
        int blockX = floor(startX);
        int blockY = floor(startY);
        int blockZ = floor(startZ);
        addChunkIfLoadedHeight(chunks, blockX, blockY, blockZ, minimumHeight, maximumHeight);

        int stepX = sign(deltaX);
        int stepY = sign(deltaY);
        int stepZ = sign(deltaZ);
        double incrementX = stepX == 0 ? Double.MAX_VALUE : stepX / deltaX;
        double incrementY = stepY == 0 ? Double.MAX_VALUE : stepY / deltaY;
        double incrementZ = stepZ == 0 ? Double.MAX_VALUE : stepZ / deltaZ;
        double nextX = incrementX * (stepX > 0 ? 1.0 - fraction(startX) : fraction(startX));
        double nextY = incrementY * (stepY > 0 ? 1.0 - fraction(startY) : fraction(startY));
        double nextZ = incrementZ * (stepZ > 0 ? 1.0 - fraction(startZ) : fraction(startZ));

        while (nextX <= 1.0 || nextY <= 1.0 || nextZ <= 1.0) {
            if (nextX < nextY) {
                if (nextX < nextZ) {
                    blockX += stepX;
                    nextX += incrementX;
                } else {
                    blockZ += stepZ;
                    nextZ += incrementZ;
                }
            } else if (nextY < nextZ) {
                blockY += stepY;
                nextY += incrementY;
            } else {
                blockZ += stepZ;
                nextZ += incrementZ;
            }
            addChunkIfLoadedHeight(chunks, blockX, blockY, blockZ, minimumHeight, maximumHeight);
        }
    }

    private static void addChunkIfLoadedHeight(
            Set<ChunkCoordinate> chunks,
            int blockX,
            int blockY,
            int blockZ,
            int minimumHeight,
            int maximumHeight) {
        if (blockY >= minimumHeight && blockY < maximumHeight) {
            chunks.add(new ChunkCoordinate(blockX >> 4, blockZ >> 4));
        }
    }

    private static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static int sign(double value) {
        return value == 0.0 ? 0 : value < 0.0 ? -1 : 1;
    }

    private static double fraction(double value) {
        return value - floor(value);
    }

    private static void requireRange(String target, int value, int minimum, int maximum)
            throws OperationException {
        if (value < minimum || value > maximum) {
            throw invalid(
                    target + " must be between " + minimum + " and " + maximum,
                    new ErrorDetails.InvalidRequest.OutOfRange(target, value, minimum, maximum));
        }
    }

    private static Vector3 normalize(Vector3 value) {
        double length =
                Math.sqrt(value.x() * value.x() + value.y() * value.y() + value.z() * value.z());
        if (!Double.isFinite(length) || length == 0) {
            throw new IllegalArgumentException("View direction must be a finite non-zero vector");
        }
        return new Vector3(value.x() / length, value.y() / length, value.z() / length);
    }

    private static Vector3 cross(Vector3 left, Vector3 right) {
        return new Vector3(
                left.y() * right.z() - left.z() * right.y(),
                left.z() * right.x() - left.x() * right.z(),
                left.x() * right.y() - left.y() * right.x());
    }

    private static Vector3 scale(Vector3 value, double factor) {
        return new Vector3(value.x() * factor, value.y() * factor, value.z() * factor);
    }

    private static Vector3 add(Vector3 first, Vector3 second, Vector3 third) {
        return new Vector3(
                first.x() + second.x() + third.x(),
                first.y() + second.y() + third.y(),
                first.z() + second.z() + third.z());
    }

    private static OperationException invalid(String message, ErrorDetails.InvalidRequest details) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message, details);
    }

    record Geometry(
            Vector3 forward,
            Vector3 right,
            Vector3 up,
            double horizontalFieldOfViewDegrees,
            List<Vector3> directions) {
        Geometry {
            directions = List.copyOf(directions);
        }
    }

    record ChunkCoordinate(int x, int z) {}
}
