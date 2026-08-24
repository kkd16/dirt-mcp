package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.FluidCollision;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.LocationSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.PlayerSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ResolvedLocationSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ResolvedPlayerSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ResolvedSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewHit;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewRequest;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.Viewport;
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAccess.CameraSnapshot;
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAccess.Chunk;
import ca.deliyannides.dirtmcp.paper.world.inspection.PerspectiveViewAccess.Projection;
import ca.deliyannides.dirtmcp.paper.world.model.BlockCoordinates;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

/** Paper adapter for one coherent real-player or synthetic perspective projection. */
public final class BukkitPerspectiveViewAccess implements PerspectiveViewAccess {
    private final Server server;

    public BukkitPerspectiveViewAccess(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public CameraSnapshot captureCamera(GetPerspectiveView.Source source)
            throws OperationException {
        Instant capturedAt = Instant.now();
        Camera camera = resolveCamera(source);
        return new CameraSnapshot(
                capturedAt,
                camera.source(),
                camera.world().getName(),
                camera.world().getUID(),
                camera.world().getMinHeight(),
                camera.world().getMaxHeight(),
                camera.position(),
                camera.rotation(),
                camera.lookDirection());
    }

    @Override
    public GetPerspectiveView.Result trace(
            ViewRequest request, CameraSnapshot camera, Projection projection)
            throws OperationException {
        World world = this.server.getWorld(camera.worldId());
        if (world == null || !camera.world().equals(world.getName())) {
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE,
                    "World was unloaded during perspective capture: " + camera.world(),
                    new ErrorDetails.WorldUnavailable.WorldUnloaded(camera.world()));
        }
        requireLoadedChunks(world, projection.requiredChunks());
        Location location =
                new Location(
                        world,
                        camera.position().x(),
                        camera.position().y(),
                        camera.position().z(),
                        (float) camera.rotation().yaw(),
                        (float) camera.rotation().pitch());

        Map<String, Integer> paletteIndexes = new LinkedHashMap<>();
        List<ViewHit> hits = new ArrayList<>();
        Integer crosshairHitIndex = null;
        int centerRow = request.height() / 2;
        int centerColumn = request.width() / 2;
        for (int rayIndex = 0; rayIndex < projection.directions().size(); rayIndex++) {
            int row = rayIndex / request.width();
            int column = rayIndex % request.width();
            Vector3 direction = projection.directions().get(rayIndex);
            RayTraceResult trace =
                    world.rayTraceBlocks(
                            location,
                            new org.bukkit.util.Vector(direction.x(), direction.y(), direction.z()),
                            request.maxDistance(),
                            fluidCollision(request.fluidCollision()),
                            request.ignorePassableBlocks());
            if (trace == null || trace.getHitBlock() == null) {
                continue;
            }
            Block block = trace.getHitBlock();
            String blockState = block.getBlockData().getAsString();
            int blockStateIndex =
                    paletteIndexes.computeIfAbsent(
                            blockState, ignored -> paletteIndexes.size() + 1);
            org.bukkit.util.Vector hit = trace.getHitPosition();
            double distance =
                    Math.sqrt(
                            square(hit.getX() - camera.position().x())
                                    + square(hit.getY() - camera.position().y())
                                    + square(hit.getZ() - camera.position().z()));
            ViewHit viewHit =
                    new ViewHit(
                            row,
                            column,
                            blockStateIndex,
                            new BlockPosition(block.getX(), block.getY(), block.getZ()),
                            new ExactPosition(hit.getX(), hit.getY(), hit.getZ()),
                            face(trace.getHitBlockFace()),
                            distance);
            if (row == centerRow && column == centerColumn) {
                crosshairHitIndex = hits.size();
            }
            hits.add(viewHit);
        }

        return new GetPerspectiveView.Result(
                camera.capturedAt(),
                camera.source(),
                camera.world(),
                camera.worldId(),
                camera.position(),
                camera.rotation(),
                camera.lookDirection(),
                projection.basis(),
                new Viewport(
                        request.width(),
                        request.height(),
                        request.verticalFieldOfViewDegrees(),
                        projection.horizontalFieldOfViewDegrees(),
                        request.maxDistance(),
                        request.fluidCollision().name().toLowerCase(Locale.ROOT),
                        request.ignorePassableBlocks()),
                projection.requiredChunks().size(),
                List.copyOf(paletteIndexes.keySet()),
                hits,
                crosshairHitIndex);
    }

    private Camera resolveCamera(GetPerspectiveView.Source source) throws OperationException {
        if (source instanceof PlayerSource playerSource) {
            Player player = BukkitPlayerResolver.resolve(this.server, playerSource.player());
            if (player.getSpectatorTarget() != null) {
                throw new OperationException(
                        OperationFailure.PLAYER_UNAVAILABLE,
                        "Player is spectating another entity: " + playerSource.player(),
                        new ErrorDetails.PlayerUnavailable.SpectatingEntity(playerSource.player()));
            }
            Location eye = player.getEyeLocation();
            ExactPosition position =
                    finitePlayerPosition(playerSource.player(), "eyePosition", eye);
            requirePlayerBlockPosition(playerSource.player(), "eyePosition", position);
            Rotation rotation =
                    new Rotation(
                            finitePlayerState(playerSource.player(), "rotation.yaw", eye.getYaw()),
                            finitePlayerState(
                                    playerSource.player(), "rotation.pitch", eye.getPitch()));
            return new Camera(
                    new ResolvedPlayerSource(
                            new PlayerIdentity(player.getName(), player.getUniqueId())),
                    player.getWorld(),
                    position,
                    rotation,
                    vector(eye.getDirection()));
        }
        if (source instanceof LocationSource locationSource) {
            World world = this.server.getWorld(locationSource.world());
            if (world == null) {
                throw new OperationException(
                        OperationFailure.WORLD_NOT_FOUND,
                        "World is not loaded: " + locationSource.world(),
                        new ErrorDetails.WorldNotFound(locationSource.world()));
            }
            requireLocationBlockPosition(locationSource.cameraPosition());
            Rotation requestedRotation = locationSource.rotation();
            float resolvedYaw = normalizeYaw(requestedRotation.yaw());
            Location location =
                    new Location(
                            world,
                            locationSource.cameraPosition().x(),
                            locationSource.cameraPosition().y(),
                            locationSource.cameraPosition().z(),
                            resolvedYaw,
                            (float) requestedRotation.pitch());
            Rotation resolvedRotation = new Rotation(location.getYaw(), location.getPitch());
            return new Camera(
                    new ResolvedLocationSource(),
                    world,
                    locationSource.cameraPosition(),
                    resolvedRotation,
                    vector(location.getDirection()));
        }
        throw new IllegalArgumentException("Unsupported perspective source");
    }

    private static void requireLoadedChunks(World world, Set<Chunk> chunks)
            throws OperationException {
        for (Chunk chunk : chunks) {
            if (!world.isChunkLoaded(chunk.x(), chunk.z())) {
                throw new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        "Perspective view contains an unloaded chunk at "
                                + chunk.x()
                                + ","
                                + chunk.z(),
                        new ErrorDetails.WorldUnavailable.ChunkUnloaded(
                                world.getName(), new ErrorDetails.Chunk(chunk.x(), chunk.z())));
            }
        }
    }

    private static ExactPosition finitePlayerPosition(String player, String field, Location value)
            throws OperationException {
        return new ExactPosition(
                finitePlayerState(player, field + ".x", value.getX()),
                finitePlayerState(player, field + ".y", value.getY()),
                finitePlayerState(player, field + ".z", value.getZ()));
    }

    private static double finitePlayerState(String player, String field, double value)
            throws OperationException {
        if (!Double.isFinite(value)) {
            throw new OperationException(
                    OperationFailure.PLAYER_UNAVAILABLE,
                    "Player state is unavailable because " + field + " is not finite: " + player,
                    new ErrorDetails.PlayerUnavailable.NonFiniteState(player, field));
        }
        return value;
    }

    private static void requirePlayerBlockPosition(
            String player, String field, ExactPosition position) throws OperationException {
        for (Coordinate coordinate : coordinates(position)) {
            if (!BlockCoordinates.contains(coordinate.value())) {
                throw new OperationException(
                        OperationFailure.PLAYER_UNAVAILABLE,
                        "Player position is outside the signed block-coordinate range at "
                                + field
                                + "."
                                + coordinate.axis()
                                + ": "
                                + player,
                        new ErrorDetails.PlayerUnavailable.PositionOutOfRange(
                                player, field + "." + coordinate.axis()));
            }
        }
    }

    private static void requireLocationBlockPosition(ExactPosition position)
            throws OperationException {
        for (Coordinate coordinate : coordinates(position)) {
            if (!BlockCoordinates.contains(coordinate.value())) {
                throw invalidCoordinate("source.cameraPosition." + coordinate.axis());
            }
        }
    }

    private static OperationException invalidCoordinate(String field) {
        return new OperationException(
                OperationFailure.INVALID_REQUEST,
                field + " is outside the signed block-coordinate range",
                new ErrorDetails.InvalidRequest.InvalidValue(field));
    }

    private static List<Coordinate> coordinates(ExactPosition position) {
        return List.of(
                new Coordinate("x", position.x()),
                new Coordinate("y", position.y()),
                new Coordinate("z", position.z()));
    }

    private static FluidCollisionMode fluidCollision(FluidCollision value) {
        return switch (value) {
            case NEVER -> FluidCollisionMode.NEVER;
            case SOURCE_ONLY -> FluidCollisionMode.SOURCE_ONLY;
            case ALWAYS -> FluidCollisionMode.ALWAYS;
        };
    }

    private static String face(BlockFace face) {
        if (face == null) {
            return null;
        }
        return switch (face) {
            case DOWN, UP, NORTH, SOUTH, WEST, EAST -> face.name().toLowerCase(Locale.ROOT);
            default -> null;
        };
    }

    private static Vector3 vector(org.bukkit.util.Vector value) {
        return new Vector3(value.getX(), value.getY(), value.getZ());
    }

    private static double square(double value) {
        return value * value;
    }

    private static float normalizeYaw(double yaw) {
        double normalized = yaw % 360.0;
        if (normalized >= 180.0) {
            normalized -= 360.0;
        } else if (normalized < -180.0) {
            normalized += 360.0;
        }
        float resolved = (float) normalized;
        if (resolved >= 180) {
            return -180;
        }
        return resolved == 0 ? 0 : resolved;
    }

    private record Coordinate(String axis, double value) {}

    private record Camera(
            ResolvedSource source,
            World world,
            ExactPosition position,
            Rotation rotation,
            Vector3 lookDirection) {}
}
