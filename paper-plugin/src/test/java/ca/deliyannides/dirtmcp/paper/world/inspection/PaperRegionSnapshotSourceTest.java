package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.BlockSample;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

final class PaperRegionSnapshotSourceTest {
    @Test
    void capturesLoadedChunksOnTheMainThreadAndEvaluatesPatternsOffThread() throws Exception {
        DirectMainThread mainThread = new DirectMainThread();
        BlockData pattern = blockData("minecraft:stone", Material.STONE, null);
        BlockData stone = blockData("minecraft:stone", Material.STONE, pattern);
        List<String> calls = new ArrayList<>();
        ChunkSnapshot snapshot =
                proxy(
                        ChunkSnapshot.class,
                        (ignored, method, arguments) -> {
                            if (method.getName().equals("getBlockData")) {
                                calls.add(
                                        "sample:"
                                                + arguments[0]
                                                + ","
                                                + arguments[1]
                                                + ","
                                                + arguments[2]);
                                return stone;
                            }
                            return defaultValue(method.getReturnType());
                        });
        Chunk chunk =
                proxy(
                        Chunk.class,
                        (ignored, method, arguments) ->
                                method.getName().equals("getChunkSnapshot")
                                        ? snapshot
                                        : defaultValue(method.getReturnType()));
        World world =
                world(
                        (ignored, method, arguments) -> {
                            switch (method.getName()) {
                                case "isChunkLoaded" -> {
                                    calls.add("loaded:" + arguments[0] + "," + arguments[1]);
                                    return true;
                                }
                                case "getChunkAt" -> {
                                    calls.add("chunk:" + arguments[0] + "," + arguments[1]);
                                    return chunk;
                                }
                                default -> {
                                    return defaultValue(method.getReturnType());
                                }
                            }
                        });
        Server server = server(world, pattern, false);
        PaperRegionSnapshotSource source =
                new PaperRegionSnapshotSource(server, mainThread, ignored -> false);
        Cuboid region = cuboid(new BlockPosition(-1, 0, -1), new BlockPosition(-1, 0, -1));

        CapturedRegion capture =
                source.capture("world", region, List.of("minecraft:stone"), List.of());
        BlockSample sample = capture.sample(new BlockPosition(-1, 0, -1));

        assertTrue(mainThread.called);
        assertEquals("canonical-world", capture.worldName());
        assertEquals(new BlockSample("minecraft:stone", false, true), sample);
        assertEquals(List.of("loaded:-1,-1", "chunk:-1,-1", "sample:15,0,15"), calls);
    }

    @Test
    void appliesExcludePatternsAfterCapture() throws Exception {
        BlockData pattern = blockData("minecraft:stone", Material.STONE, null);
        BlockData stone = blockData("minecraft:stone", Material.STONE, pattern);
        ChunkSnapshot snapshot =
                proxy(
                        ChunkSnapshot.class,
                        (ignored, method, arguments) ->
                                method.getName().equals("getBlockData")
                                        ? stone
                                        : defaultValue(method.getReturnType()));
        Chunk chunk =
                proxy(
                        Chunk.class,
                        (ignored, method, arguments) ->
                                method.getName().equals("getChunkSnapshot")
                                        ? snapshot
                                        : defaultValue(method.getReturnType()));
        World world =
                world(
                        (ignored, method, arguments) -> {
                            if (method.getName().equals("isChunkLoaded")) {
                                return true;
                            }
                            if (method.getName().equals("getChunkAt")) {
                                return chunk;
                            }
                            return defaultValue(method.getReturnType());
                        });
        PaperRegionSnapshotSource source =
                new PaperRegionSnapshotSource(
                        server(world, pattern, false), new DirectMainThread(), ignored -> false);
        Cuboid region = cuboid(new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0));

        CapturedRegion capture =
                source.capture("world", region, List.of(), List.of("minecraft:stone"));

        assertFalse(capture.sample(new BlockPosition(0, 0, 0)).selectedByPatterns());
    }

    @Test
    void reportsWorldHeightChunkAndPatternFailures() throws Exception {
        Cuboid valid = cuboid(new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0));
        Cuboid belowWorld = cuboid(new BlockPosition(0, -65, 0), new BlockPosition(0, -65, 0));
        World unloaded =
                world(
                        (ignored, method, arguments) ->
                                method.getName().equals("isChunkLoaded")
                                        ? false
                                        : defaultValue(method.getReturnType()));
        DirectMainThread mainThread = new DirectMainThread();

        OperationException missing =
                assertThrows(
                        OperationException.class,
                        () ->
                                new PaperRegionSnapshotSource(server(null, null, false), mainThread)
                                        .capture("missing", valid, List.of(), List.of()));
        OperationException height =
                assertThrows(
                        OperationException.class,
                        () ->
                                new PaperRegionSnapshotSource(
                                                server(unloaded, null, false), mainThread)
                                        .capture("world", belowWorld, List.of(), List.of()));
        OperationException chunk =
                assertThrows(
                        OperationException.class,
                        () ->
                                new PaperRegionSnapshotSource(
                                                server(unloaded, null, false), mainThread)
                                        .capture("world", valid, List.of(), List.of()));
        OperationException pattern =
                assertThrows(
                        OperationException.class,
                        () ->
                                new PaperRegionSnapshotSource(
                                                server(unloaded, null, true), mainThread)
                                        .capture(
                                                "world", valid, List.of("not a block"), List.of()));

        assertEquals(OperationFailure.WORLD_NOT_FOUND, missing.failure());
        assertEquals(OperationFailure.INVALID_REQUEST, height.failure());
        assertEquals(OperationFailure.WORLD_UNAVAILABLE, chunk.failure());
        assertEquals(OperationFailure.INVALID_REQUEST, pattern.failure());
    }

    private static World world(InvocationHandler additional) {
        return proxy(
                World.class,
                (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "getName" -> "canonical-world";
                        case "getMinHeight" -> -64;
                        case "getMaxHeight" -> 320;
                        default -> additional.invoke(proxy, method, arguments);
                    };
                });
    }

    private static Server server(World world, BlockData pattern, boolean rejectPattern) {
        return proxy(
                Server.class,
                (ignored, method, arguments) -> {
                    if (method.getName().equals("getWorld") && arguments[0] instanceof String) {
                        return world;
                    }
                    if (method.getName().equals("createBlockData")
                            && arguments.length == 1
                            && arguments[0] instanceof String) {
                        if (rejectPattern) {
                            throw new IllegalArgumentException("bad pattern");
                        }
                        return pattern;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static BlockData blockData(String state, Material material, BlockData matchingPattern) {
        return proxy(
                BlockData.class,
                (ignored, method, arguments) ->
                        switch (method.getName()) {
                            case "getAsString" -> state;
                            case "getMaterial" -> material;
                            case "matches" -> arguments[0] == matchingPattern;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    private static Cuboid cuboid(BlockPosition min, BlockPosition max) throws OperationException {
        return RegionGeometry.normalize(min, max, 10);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class DirectMainThread implements MainThread {
        private boolean called;

        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            this.called = true;
            try {
                return action.get();
            } catch (Exception exception) {
                return sneakyThrow(exception);
            }
        }

        @Override
        public void close() {}

        @SuppressWarnings("unchecked")
        private static <T, E extends Throwable> T sneakyThrow(Throwable failure) throws E {
            throw (E) failure;
        }
    }
}
