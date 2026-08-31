package ca.deliyannides.dirtmcp.paper.world.inspection;

import static ca.deliyannides.dirtmcp.paper.world.inspection.TestProxies.defaultValue;
import static ca.deliyannides.dirtmcp.paper.world.inspection.TestProxies.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Includes;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

final class BukkitPlayerContextAccessTest {
    private static final UUID PLAYER_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final UUID WORLD_ID = UUID.fromString("223e4567-e89b-42d3-a456-426614174000");

    @Test
    void excludesOptionalPaperAccessAndReadsOnlyRequestedEnderChest() throws Exception {
        World world = world(null, new AtomicInteger());
        Inventory enderChest =
                proxy(
                        Inventory.class,
                        (ignored, method, arguments) ->
                                switch (method.getName()) {
                                    case "getStorageContents" -> new ItemStack[27];
                                    default -> defaultValue(method);
                                });
        List<String> calls = new ArrayList<>();
        Player player = player(world, null, enderChest, calls);
        BukkitPlayerContextAccess access = new BukkitPlayerContextAccess(server(player));

        GetPlayerContext.Result base = access.capture(request(baseOnly()));
        assertNull(base.enderChest());
        assertEquals(false, calls.contains("getInventory"));
        assertEquals(false, calls.contains("getEnderChest"));
        assertEquals(false, calls.contains("getActivePotionEffects"));
        assertEquals(false, calls.contains("getPing"));

        Includes includeEnderChest = new Includes(false, false, true, false, false, false, false);
        GetPlayerContext.Result selected = access.capture(request(includeEnderChest));
        assertEquals(27, selected.enderChest().size());
        assertTrue(calls.contains("getEnderChest"));
        assertEquals(false, calls.contains("getInventory"));
    }

    @Test
    void reportsTheExactNonFiniteOptionalStateField() throws Exception {
        World world = world(null, new AtomicInteger());
        Player invalidVitals =
                player(world, null, null, new ArrayList<>(), Map.of("getSaturation", Float.NaN));
        BukkitPlayerContextAccess vitalsAccess =
                new BukkitPlayerContextAccess(server(invalidVitals));

        assertNull(vitalsAccess.capture(request(baseOnly())).vitals());
        OperationException vitalsUnavailable =
                assertThrows(
                        OperationException.class,
                        () ->
                                vitalsAccess.capture(
                                        request(
                                                new Includes(
                                                        false, false, false, true, false, false,
                                                        false))));
        assertEquals(OperationFailure.PLAYER_UNAVAILABLE, vitalsUnavailable.failure());
        assertEquals(
                new ErrorDetails.PlayerUnavailable.NonFiniteState("Builder", "vitals.saturation"),
                vitalsUnavailable.details().orElseThrow());

        Player invalidMovement =
                player(
                        world,
                        null,
                        null,
                        new ArrayList<>(),
                        Map.of(
                                "getVelocity",
                                new Vector(),
                                "getFallDistance",
                                Float.POSITIVE_INFINITY));
        BukkitPlayerContextAccess movementAccess =
                new BukkitPlayerContextAccess(server(invalidMovement));

        OperationException movementUnavailable =
                assertThrows(
                        OperationException.class,
                        () ->
                                movementAccess.capture(
                                        request(
                                                new Includes(
                                                        false, false, false, false, true, false,
                                                        false))));
        assertEquals(
                new ErrorDetails.PlayerUnavailable.NonFiniteState(
                        "Builder", "movement.fallDistance"),
                movementUnavailable.details().orElseThrow());
    }

    @Test
    void reportsNonFiniteBasePose() {
        AtomicInteger rayCalls = new AtomicInteger();
        World world = world(null, rayCalls);
        Player invalidPose =
                player(
                        world,
                        null,
                        null,
                        new ArrayList<>(),
                        Map.of("getLocation", new Location(world, Double.NaN, 64, 0.5)));
        BukkitPlayerContextAccess access = new BukkitPlayerContextAccess(server(invalidPose));

        OperationException unavailable =
                assertThrows(OperationException.class, () -> access.capture(request(baseOnly())));

        assertEquals(OperationFailure.PLAYER_UNAVAILABLE, unavailable.failure());
        assertEquals(
                new ErrorDetails.PlayerUnavailable.NonFiniteState("Builder", "feetPosition.x"),
                unavailable.details().orElseThrow());
        assertEquals(0, rayCalls.get());
    }

    @Test
    void rejectsOutOfRangeFeetButPreservesExactEyePosition() throws Exception {
        World world = world(null, new AtomicInteger());
        Player invalidFeet =
                player(
                        world,
                        null,
                        null,
                        new ArrayList<>(),
                        Map.of("getLocation", new Location(world, 3_000_000_000D, 64, 0.5)));
        BukkitPlayerContextAccess feetAccess = new BukkitPlayerContextAccess(server(invalidFeet));

        OperationException feetUnavailable =
                assertThrows(
                        OperationException.class, () -> feetAccess.capture(request(baseOnly())));
        assertEquals(
                new ErrorDetails.PlayerUnavailable.PositionOutOfRange("Builder", "feetPosition.x"),
                feetUnavailable.details().orElseThrow());

        Player outOfRangeEye =
                player(
                        world,
                        null,
                        null,
                        new ArrayList<>(),
                        Map.of("getEyeLocation", new Location(world, 3_000_000_000D, 65.62, 0.5)));
        BukkitPlayerContextAccess stateOnlyAccess =
                new BukkitPlayerContextAccess(server(outOfRangeEye));
        assertEquals(
                3_000_000_000D, stateOnlyAccess.capture(request(baseOnly())).eyePosition().x());
    }

    @Test
    void mapsRequestedEquipmentInventoryMovementClientAndEmptyEffects() throws Exception {
        World world = world(null, new AtomicInteger());
        PlayerInventory inventory =
                proxy(
                        PlayerInventory.class,
                        (ignored, method, arguments) ->
                                switch (method.getName()) {
                                    case "getHeldItemSlot" -> 4;
                                    case "getStorageContents" -> new ItemStack[36];
                                    default -> defaultValue(method);
                                });
        Player player =
                player(
                        world,
                        null,
                        null,
                        new ArrayList<>(),
                        Map.ofEntries(
                                Map.entry("getInventory", inventory),
                                Map.entry("getVelocity", new Vector(1, 2, 3)),
                                Map.entry("getFallDistance", 4F),
                                Map.entry("getAllowFlight", true),
                                Map.entry("isFlying", true),
                                Map.entry("isSneaking", true),
                                Map.entry("getPing", 42),
                                Map.entry("locale", Locale.CANADA_FRENCH),
                                Map.entry("getClientViewDistance", 12),
                                Map.entry("getViewDistance", -1),
                                Map.entry("getSendViewDistance", 10),
                                Map.entry("getActivePotionEffects", List.of())));
        BukkitPlayerContextAccess access = new BukkitPlayerContextAccess(server(player));

        GetPlayerContext.Result result =
                access.capture(request(new Includes(true, true, false, false, true, true, true)));

        assertEquals(4, result.equipment().selectedHotbarSlot());
        assertNull(result.equipment().mainHand());
        assertEquals(36, result.inventory().size());
        assertEquals(List.of(), result.inventory().slots());
        assertEquals(new Vector3(1, 2, 3), result.movement().velocity());
        assertEquals(4, result.movement().fallDistance());
        assertTrue(result.movement().allowFlight());
        assertTrue(result.movement().flying());
        assertTrue(result.movement().sneaking());
        assertEquals(42, result.client().pingMillis());
        assertEquals("fr-CA", result.client().locale());
        assertEquals(12, result.client().clientViewDistance());
        assertEquals(-1, result.client().viewDistance());
        assertEquals(10, result.client().sendViewDistance());
        assertEquals(List.of(), result.effects());
    }

    @Test
    void reportsMissingPlayersWithoutBroadeningTheSelector() {
        BukkitPlayerContextAccess access = new BukkitPlayerContextAccess(server(null));

        OperationException missing =
                assertThrows(OperationException.class, () -> access.capture(request(baseOnly())));

        assertEquals(OperationFailure.PLAYER_NOT_FOUND, missing.failure());
        assertEquals("Player is not online: Builder", missing.getMessage());
        assertEquals(new ErrorDetails.PlayerNotFound("Builder"), missing.details().orElseThrow());
    }

    @Test
    void usesUuidLookupOnlyForCanonicalUuidShapes() throws Exception {
        World world = world(null, new AtomicInteger());
        Player player = player(world, null, null, new ArrayList<>());
        List<String> lookups = new ArrayList<>();
        Server trackingServer =
                proxy(
                        Server.class,
                        (ignored, method, arguments) -> {
                            if ("getPlayerExact".equals(method.getName())) {
                                lookups.add("name:" + arguments[0]);
                                return player;
                            }
                            if ("getPlayer".equals(method.getName())) {
                                lookups.add("uuid:" + arguments[0]);
                                return player;
                            }
                            return defaultValue(method);
                        });
        BukkitPlayerContextAccess access = new BukkitPlayerContextAccess(trackingServer);

        access.capture(
                new GetPlayerContext.Request(
                        PLAYER_ID.toString().toUpperCase(Locale.ROOT), baseOnly()));
        assertEquals(List.of("uuid:" + PLAYER_ID), lookups);

        lookups.clear();
        access.capture(new GetPlayerContext.Request("builder", baseOnly()));
        assertEquals(List.of("name:builder"), lookups);
    }

    @Test
    void mapsDefaultCustomAndIndependentDurabilityComponents() {
        Damageable ordinary = damageable(false, 0, false, 0);
        BukkitPlayerContextAccess.ItemDurability defaultDurability =
                BukkitPlayerContextAccess.durability(ordinary, 1561);
        assertEquals(0, defaultDurability.damage());
        assertEquals(1561, defaultDurability.maxDamage());

        Damageable custom = damageable(true, 4, true, 7);
        BukkitPlayerContextAccess.ItemDurability customDurability =
                BukkitPlayerContextAccess.durability(custom, 0);
        assertEquals(7, customDurability.damage());
        assertEquals(4, customDurability.maxDamage());

        Damageable damageOnly = damageable(false, 0, true, 7);
        BukkitPlayerContextAccess.ItemDurability independentDamage =
                BukkitPlayerContextAccess.durability(damageOnly, 0);
        assertEquals(7, independentDamage.damage());
        assertNull(independentDamage.maxDamage());
        assertEquals(
                new BukkitPlayerContextAccess.ItemDurability(0, 10),
                BukkitPlayerContextAccess.durability(null, 10));
        assertEquals(
                new BukkitPlayerContextAccess.ItemDurability(null, null),
                BukkitPlayerContextAccess.durability(null, 0));
    }

    private static GetPlayerContext.Request request(Includes includes) {
        return new GetPlayerContext.Request("Builder", includes);
    }

    private static Includes baseOnly() {
        return new Includes(false, false, false, false, false, false, false);
    }

    private static Server server(Player player) {
        return proxy(
                Server.class,
                (ignored, method, arguments) ->
                        switch (method.getName()) {
                            case "getPlayerExact", "getPlayer" -> player;
                            default -> defaultValue(method);
                        });
    }

    private static World world(RayTraceResult hit, AtomicInteger rayCalls) {
        return proxy(
                World.class,
                (ignored, method, arguments) ->
                        switch (method.getName()) {
                            case "getName" -> "world";
                            case "getUID" -> WORLD_ID;
                            case "getMinHeight" -> -64;
                            case "getMaxHeight" -> 320;
                            case "isChunkLoaded" -> true;
                            case "rayTraceBlocks" -> {
                                rayCalls.incrementAndGet();
                                yield hit;
                            }
                            default -> defaultValue(method);
                        });
    }

    private static Player player(
            World world, Entity spectatorTarget, Inventory enderChest, List<String> calls) {
        return player(world, spectatorTarget, enderChest, calls, Map.of());
    }

    private static Player player(
            World world,
            Entity spectatorTarget,
            Inventory enderChest,
            List<String> calls,
            Map<String, Object> overrides) {
        Location feet = new Location(world, 0.5, 64, 0.5, 0, 0);
        Location eye = new Location(world, 0.5, 65.62, 0.5, 0, 0);
        return proxy(
                Player.class,
                (ignored, method, arguments) -> {
                    calls.add(method.getName());
                    if (overrides.containsKey(method.getName())) {
                        return overrides.get(method.getName());
                    }
                    return switch (method.getName()) {
                        case "getName" -> "Builder";
                        case "getUniqueId" -> PLAYER_ID;
                        case "getLocation" -> feet.clone();
                        case "getEyeLocation" -> eye.clone();
                        case "getWorld" -> world;
                        case "getGameMode" -> GameMode.CREATIVE;
                        case "getPose" -> Pose.STANDING;
                        case "isOnGround" -> true;
                        case "getSpectatorTarget" -> spectatorTarget;
                        case "getEnderChest" -> enderChest;
                        default -> defaultValue(method);
                    };
                });
    }

    private static Damageable damageable(
            boolean hasMaxDamage, int maxDamage, boolean hasDamage, int damage) {
        return proxy(
                Damageable.class,
                (ignored, method, arguments) ->
                        switch (method.getName()) {
                            case "hasMaxDamage" -> hasMaxDamage;
                            case "getMaxDamage" -> maxDamage;
                            case "hasDamageValue", "hasDamage" -> hasDamage;
                            case "getDamage" -> damage;
                            case "isUnbreakable" -> false;
                            default -> defaultValue(method);
                        });
    }
}
