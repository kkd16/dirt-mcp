package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Effect;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Enchantment;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Equipment;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.InventoryContents;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.InventorySlot;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.ItemSummary;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.PlayerClient;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.PlayerMovement;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.PlayerVitals;
import ca.deliyannides.dirtmcp.paper.world.inspection.PaperPlayerContextService.PlayerContextAccess;
import ca.deliyannides.dirtmcp.paper.world.model.BlockCoordinates;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;

/** Paper adapter for one tick-consistent player snapshot. */
public final class BukkitPlayerContextAccess implements PlayerContextAccess {
    private final Server server;

    public BukkitPlayerContextAccess(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public GetPlayerContext.Result capture(GetPlayerContext.Request request)
            throws OperationException {
        Player player = BukkitPlayerResolver.resolve(this.server, request.player());

        Instant capturedAt = Instant.now();
        Location feet = player.getLocation();
        Location eye = player.getEyeLocation();
        ExactPosition feetPosition = finitePosition(request.player(), "feetPosition", feet);
        requireBlockPosition(request.player(), "feetPosition", feetPosition);
        ExactPosition eyePosition = finitePosition(request.player(), "eyePosition", eye);
        Rotation rotation =
                new Rotation(
                        finiteState(request.player(), "rotation.yaw", eye.getYaw()),
                        finiteState(request.player(), "rotation.pitch", eye.getPitch()));
        World world = player.getWorld();
        Vector3 lookDirection = vector(eye.getDirection());
        PlayerInventory playerInventory =
                request.include().equipment() || request.include().inventory()
                        ? player.getInventory()
                        : null;
        Equipment equipment = request.include().equipment() ? equipment(playerInventory) : null;
        InventoryContents inventory =
                request.include().inventory() ? inventory(playerInventory) : null;
        InventoryContents enderChest =
                request.include().enderChest() ? inventory(player.getEnderChest()) : null;
        PlayerVitals vitals = request.include().vitals() ? vitals(player, request.player()) : null;
        PlayerMovement movement =
                request.include().movement() ? movement(player, request.player()) : null;
        PlayerClient client = request.include().client() ? client(player) : null;
        List<Effect> effects = request.include().effects() ? effects(player) : null;

        return new GetPlayerContext.Result(
                capturedAt,
                new PlayerIdentity(player.getName(), player.getUniqueId()),
                world.getName(),
                world.getUID(),
                feetPosition,
                new BlockPosition(feet.getBlockX(), feet.getBlockY(), feet.getBlockZ()),
                eyePosition,
                rotation,
                lookDirection,
                player.getGameMode().name().toLowerCase(Locale.ROOT),
                player.getPose().name().toLowerCase(Locale.ROOT),
                ((Entity) player).isOnGround(),
                equipment,
                inventory,
                enderChest,
                vitals,
                movement,
                client,
                effects);
    }

    private static Equipment equipment(PlayerInventory inventory) {
        return new Equipment(
                inventory.getHeldItemSlot(),
                item(inventory.getItemInMainHand()),
                item(inventory.getItemInOffHand()),
                item(inventory.getHelmet()),
                item(inventory.getChestplate()),
                item(inventory.getLeggings()),
                item(inventory.getBoots()));
    }

    private static InventoryContents inventory(Inventory inventory) {
        ItemStack[] contents = inventory.getStorageContents();
        List<InventorySlot> slots = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemSummary item = item(contents[slot]);
            if (item != null) {
                slots.add(new InventorySlot(slot, item));
            }
        }
        return new InventoryContents(contents.length, slots);
    }

    private static ItemSummary item(ItemStack stack) {
        if (stack == null || stack.getAmount() < 1 || stack.getType().isAir()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        ItemDurability durability =
                durability(
                        meta instanceof Damageable damageable ? damageable : null,
                        stack.getType().getMaxDurability());
        List<Enchantment> enchantments =
                stack.getEnchantments().entrySet().stream()
                        .map(
                                entry ->
                                        new Enchantment(
                                                entry.getKey().getKey().toString(),
                                                entry.getValue()))
                        .sorted((left, right) -> left.type().compareTo(right.type()))
                        .toList();
        return new ItemSummary(
                stack.getType().getKey().toString(),
                stack.getAmount(),
                stack.getMaxStackSize(),
                durability.damage(),
                durability.maxDamage(),
                meta != null && meta.isUnbreakable(),
                enchantments);
    }

    static ItemDurability durability(Damageable damageable, int materialMaxDamage) {
        Integer maxDamage = materialMaxDamage > 0 ? materialMaxDamage : null;
        if (damageable != null && damageable.hasMaxDamage()) {
            maxDamage = damageable.getMaxDamage();
        }
        Integer damage = null;
        if (damageable != null && damageable.hasDamageValue()) {
            damage = damageable.getDamage();
        } else if (maxDamage != null) {
            damage = 0;
        }
        return new ItemDurability(damage, maxDamage);
    }

    private static PlayerVitals vitals(Player player, String selector) throws OperationException {
        double health = finiteState(selector, "vitals.health", player.getHealth());
        double absorptionAmount =
                finiteState(selector, "vitals.absorptionAmount", player.getAbsorptionAmount());
        double saturation = finiteState(selector, "vitals.saturation", player.getSaturation());
        double exhaustion = finiteState(selector, "vitals.exhaustion", player.getExhaustion());
        double experienceProgress =
                finiteState(selector, "vitals.experienceProgress", player.getExp());
        AttributeInstance maxHealth =
                Objects.requireNonNull(
                        player.getAttribute(Attribute.MAX_HEALTH),
                        "Player is missing its max-health attribute");
        return new PlayerVitals(
                health,
                finiteState(selector, "vitals.maxHealth", maxHealth.getValue()),
                absorptionAmount,
                player.getFoodLevel(),
                saturation,
                exhaustion,
                player.getRemainingAir(),
                player.getMaximumAir(),
                player.getLevel(),
                experienceProgress,
                player.calculateTotalExperiencePoints(),
                player.getFireTicks(),
                player.getFreezeTicks());
    }

    private static PlayerMovement movement(Player player, String selector)
            throws OperationException {
        org.bukkit.util.Vector velocity = player.getVelocity();
        return new PlayerMovement(
                new Vector3(
                        finiteState(selector, "movement.velocity.x", velocity.getX()),
                        finiteState(selector, "movement.velocity.y", velocity.getY()),
                        finiteState(selector, "movement.velocity.z", velocity.getZ())),
                finiteState(selector, "movement.fallDistance", player.getFallDistance()),
                player.getAllowFlight(),
                player.isFlying(),
                player.isSneaking(),
                player.isSprinting(),
                player.isSwimming(),
                player.isGliding(),
                player.isSleeping(),
                player.isBlocking(),
                player.isRiptiding());
    }

    private static double finiteState(String selector, String field, double value)
            throws OperationException {
        if (!Double.isFinite(value)) {
            throw new OperationException(
                    OperationFailure.PLAYER_UNAVAILABLE,
                    "Player state is unavailable because " + field + " is not finite: " + selector,
                    new ErrorDetails.PlayerUnavailable.NonFiniteState(selector, field));
        }
        return value;
    }

    private static PlayerClient client(Player player) {
        return new PlayerClient(
                player.getPing(),
                player.locale().toLanguageTag(),
                player.getClientViewDistance(),
                player.getViewDistance(),
                player.getSendViewDistance());
    }

    private static List<Effect> effects(Player player) {
        return player.getActivePotionEffects().stream()
                .map(BukkitPlayerContextAccess::effect)
                .sorted((left, right) -> left.type().compareTo(right.type()))
                .toList();
    }

    private static Effect effect(PotionEffect effect) {
        return new Effect(
                effect.getType().getKey().toString(),
                effect.getAmplifier(),
                effect.getDuration(),
                effect.isAmbient(),
                effect.hasParticles(),
                effect.hasIcon());
    }

    private static ExactPosition finitePosition(String selector, String field, Location value)
            throws OperationException {
        return new ExactPosition(
                finiteState(selector, field + ".x", value.getX()),
                finiteState(selector, field + ".y", value.getY()),
                finiteState(selector, field + ".z", value.getZ()));
    }

    private static void requireBlockPosition(String selector, String field, ExactPosition value)
            throws OperationException {
        requireBlockCoordinate(selector, field + ".x", value.x());
        requireBlockCoordinate(selector, field + ".y", value.y());
        requireBlockCoordinate(selector, field + ".z", value.z());
    }

    private static void requireBlockCoordinate(String selector, String field, double value)
            throws OperationException {
        if (!BlockCoordinates.contains(value)) {
            throw positionOutOfRange(selector, field);
        }
    }

    private static OperationException positionOutOfRange(String selector, String field) {
        return new OperationException(
                OperationFailure.PLAYER_UNAVAILABLE,
                "Player position is outside the signed block-coordinate range at "
                        + field
                        + ": "
                        + selector,
                new ErrorDetails.PlayerUnavailable.PositionOutOfRange(selector, field));
    }

    private static Vector3 vector(org.bukkit.util.Vector value) {
        return new Vector3(value.getX(), value.getY(), value.getZ());
    }

    record ItemDurability(Integer damage, Integer maxDamage) {}
}
