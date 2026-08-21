package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.FluidCollision;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.LocationSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.PlayerSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewRequest;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

final class BukkitPerspectiveViewAccessTest {
    private static final UUID PLAYER_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final UUID WORLD_ID = UUID.fromString("223e4567-e89b-42d3-a456-426614174000");
    private static final ViewRequest VIEW =
            new ViewRequest(1, 1, 70, 1, FluidCollision.NEVER, false);

    @Test
    void capturesAPlayerPerspectiveWithCaseInsensitiveExactNameLookup() throws Exception {
        AtomicInteger rayCalls = new AtomicInteger();
        World world = world(hit(), rayCalls, true);
        Player player = player(world, null);
        List<String> lookups = new ArrayList<>();
        Server server =
                proxy(
                        Server.class,
                        (ignored, method, arguments) -> {
                            if ("getPlayerExact".equals(method.getName())) {
                                lookups.add((String) arguments[0]);
                                return player;
                            }
                            return defaultValue(method);
                        });
        BukkitPerspectiveViewAccess access = new BukkitPerspectiveViewAccess(server, 1, 1, 4);

        GetPerspectiveView.Result result =
                access.capture(new GetPerspectiveView.Request(new PlayerSource("builder"), VIEW));

        var source = (GetPerspectiveView.ResolvedPlayerSource) result.source();
        assertEquals("Builder", source.player().name());
        assertEquals(List.of("builder"), lookups);
        assertEquals(new ExactPosition(0.5, 65.62, 0.5), result.cameraPosition());
        assertEquals(List.of("minecraft:stone"), result.blockStatePalette());
        assertEquals(0, result.crosshairHitIndex());
        assertNull(result.hits().getFirst().face());
        assertEquals(1, rayCalls.get());
    }

    @Test
    void capturesASyntheticCameraWithoutResolvingAPlayer() throws Exception {
        AtomicInteger rayCalls = new AtomicInteger();
        World world = world(null, rayCalls, true);
        Server server =
                proxy(
                        Server.class,
                        (ignored, method, arguments) ->
                                switch (method.getName()) {
                                    case "getWorld" -> world;
                                    case "getPlayerExact", "getPlayer" ->
                                            throw new AssertionError(
                                                    "synthetic view resolved a player");
                                    default -> defaultValue(method);
                                });
        BukkitPerspectiveViewAccess access = new BukkitPerspectiveViewAccess(server, 1, 1, 4);
        ExactPosition camera = new ExactPosition(8.25, 70, -4.5);

        GetPerspectiveView.Result result =
                access.capture(
                        new GetPerspectiveView.Request(
                                new LocationSource("world", camera, new Rotation(405, -20)), VIEW));

        assertEquals(new GetPerspectiveView.ResolvedLocationSource(), result.source());
        assertEquals(camera, result.cameraPosition());
        assertEquals(45, result.rotation().yaw());
        assertEquals(-20, result.rotation().pitch());
        assertEquals(1, rayCalls.get());
    }

    @Test
    void rejectsSpectatorTargetsAndUnloadedSyntheticChunksBeforeTracing() {
        AtomicInteger playerRayCalls = new AtomicInteger();
        World loadedWorld = world(null, playerRayCalls, true);
        Entity target = proxy(Entity.class, BukkitPerspectiveViewAccessTest::defaultValue);
        BukkitPerspectiveViewAccess playerAccess =
                new BukkitPerspectiveViewAccess(
                        server(player(loadedWorld, target), loadedWorld), 1, 1, 4);

        OperationException spectator =
                assertThrows(
                        OperationException.class,
                        () ->
                                playerAccess.capture(
                                        new GetPerspectiveView.Request(
                                                new PlayerSource("builder"), VIEW)));
        assertEquals(OperationFailure.PLAYER_UNAVAILABLE, spectator.failure());
        assertEquals(
                new ErrorDetails.PlayerUnavailable.SpectatingEntity("builder"),
                spectator.details().orElseThrow());
        assertEquals(0, playerRayCalls.get());

        AtomicInteger locationRayCalls = new AtomicInteger();
        World unloadedWorld = world(null, locationRayCalls, false);
        BukkitPerspectiveViewAccess locationAccess =
                new BukkitPerspectiveViewAccess(server(null, unloadedWorld), 1, 1, 4);
        OperationException unloaded =
                assertThrows(
                        OperationException.class,
                        () ->
                                locationAccess.capture(
                                        new GetPerspectiveView.Request(
                                                new LocationSource(
                                                        "world",
                                                        new ExactPosition(0.5, 65, 0.5),
                                                        new Rotation(0, 0)),
                                                VIEW)));
        assertEquals(OperationFailure.WORLD_UNAVAILABLE, unloaded.failure());
        assertEquals(
                new ErrorDetails.WorldUnavailable.ChunkUnloaded(
                        "world", new ErrorDetails.Chunk(0, 0)),
                unloaded.details().orElseThrow());
        assertEquals(0, locationRayCalls.get());
    }

    private static RayTraceResult hit() {
        BlockData data = proxy(BlockData.class, (ignored, method, arguments) -> "minecraft:stone");
        Block block =
                proxy(
                        Block.class,
                        (ignored, method, arguments) ->
                                switch (method.getName()) {
                                    case "getBlockData" -> data;
                                    case "getX" -> 0;
                                    case "getY" -> 65;
                                    case "getZ" -> 1;
                                    default -> defaultValue(method);
                                });
        return new RayTraceResult(new Vector(0.5, 65.62, 1), block, null);
    }

    private static World world(RayTraceResult hit, AtomicInteger rayCalls, boolean chunksLoaded) {
        return proxy(
                World.class,
                (ignored, method, arguments) ->
                        switch (method.getName()) {
                            case "getName" -> "world";
                            case "getUID" -> WORLD_ID;
                            case "getMinHeight" -> -64;
                            case "getMaxHeight" -> 320;
                            case "isChunkLoaded" -> chunksLoaded;
                            case "rayTraceBlocks" -> {
                                rayCalls.incrementAndGet();
                                yield hit;
                            }
                            default -> defaultValue(method);
                        });
    }

    private static Player player(World world, Entity spectatorTarget) {
        return proxy(
                Player.class,
                (ignored, method, arguments) ->
                        switch (method.getName()) {
                            case "getName" -> "Builder";
                            case "getUniqueId" -> PLAYER_ID;
                            case "getWorld" -> world;
                            case "getEyeLocation" -> new Location(world, 0.5, 65.62, 0.5, 0, 0);
                            case "getSpectatorTarget" -> spectatorTarget;
                            default -> defaultValue(method);
                        });
    }

    private static Server server(Player player, World world) {
        return proxy(
                Server.class,
                (ignored, method, arguments) ->
                        switch (method.getName()) {
                            case "getPlayerExact", "getPlayer" -> player;
                            case "getWorld" -> world;
                            default -> defaultValue(method);
                        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(), new Class<?>[] {type}, objectMethods(handler));
    }

    private static InvocationHandler objectMethods(InvocationHandler delegate) {
        return (proxy, method, arguments) ->
                switch (method.getName()) {
                    case "toString" ->
                            proxy.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> delegate.invoke(proxy, method, arguments);
                };
    }

    private static Object defaultValue(Object proxy, Method method, Object[] arguments) {
        return defaultValue(method);
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }
}
