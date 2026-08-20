package ca.deliyannides.dirtmcp.paper.status;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.lang.reflect.Proxy;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class BukkitServerStatusAccessTest {
    @Test
    void capturesThePlayersCardinalFacingDirection() {
        World world = world("world");
        Player player = player(world);

        assertEquals(
                new GetServerStatus.OnlinePlayer(
                        "Builder", "world", "creative", "west", new BlockPosition(12, 70, -5)),
                BukkitServerStatusAccess.onlinePlayer(player));
    }

    private static Player player(World world) {
        return (Player)
                Proxy.newProxyInstance(
                        BukkitServerStatusAccessTest.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (proxy, method, arguments) ->
                                switch (method.getName()) {
                                    case "getName" -> "Builder";
                                    case "getWorld" -> world;
                                    case "getGameMode" -> GameMode.CREATIVE;
                                    case "getFacing" -> BlockFace.WEST;
                                    case "getLocation" -> new Location(null, 12.8, 70.1, -4.2);
                                    case "toString" -> "TestPlayer";
                                    default ->
                                            throw new AssertionError(
                                                    "Unexpected Player method: " + method);
                                });
    }

    private static World world(String name) {
        return (World)
                Proxy.newProxyInstance(
                        BukkitServerStatusAccessTest.class.getClassLoader(),
                        new Class<?>[] {World.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("getName")) {
                                return name;
                            }
                            if (method.getName().equals("toString")) {
                                return "TestWorld";
                            }
                            throw new AssertionError("Unexpected World method: " + method);
                        });
    }
}
