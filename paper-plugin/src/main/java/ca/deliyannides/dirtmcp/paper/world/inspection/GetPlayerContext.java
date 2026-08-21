package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface GetPlayerContext {
    int MAX_PLAYER_SELECTOR_LENGTH = 36;

    Result getPlayerContext(Request request) throws OperationException;

    record Request(String player, Includes include) {}

    record Includes(
            boolean equipment,
            boolean inventory,
            boolean enderChest,
            boolean vitals,
            boolean movement,
            boolean client,
            boolean effects) {}

    record Result(
            Instant capturedAt,
            PlayerIdentity player,
            String world,
            UUID worldId,
            ExactPosition feetPosition,
            BlockPosition blockPosition,
            ExactPosition eyePosition,
            Rotation rotation,
            Vector3 lookDirection,
            String gameMode,
            String pose,
            boolean onGround,
            Equipment equipment,
            InventoryContents inventory,
            InventoryContents enderChest,
            PlayerVitals vitals,
            PlayerMovement movement,
            PlayerClient client,
            List<Effect> effects) {
        public Result {
            Objects.requireNonNull(capturedAt, "capturedAt");
            Objects.requireNonNull(player, "player");
            world = requireText(world, "world");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(feetPosition, "feetPosition");
            Objects.requireNonNull(blockPosition, "blockPosition");
            Objects.requireNonNull(eyePosition, "eyePosition");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(lookDirection, "lookDirection");
            gameMode = requireText(gameMode, "gameMode");
            pose = requireText(pose, "pose");
            if (effects != null) {
                effects = List.copyOf(effects);
                String previousEffectType = null;
                for (Effect effect : effects) {
                    if (previousEffectType != null
                            && previousEffectType.compareTo(effect.type()) >= 0) {
                        throw new IllegalArgumentException(
                                "Effects must be uniquely sorted by type");
                    }
                    previousEffectType = effect.type();
                }
            }
        }
    }

    record Equipment(
            int selectedHotbarSlot,
            ItemSummary mainHand,
            ItemSummary offHand,
            ItemSummary helmet,
            ItemSummary chestplate,
            ItemSummary leggings,
            ItemSummary boots) {
        public Equipment {
            if (selectedHotbarSlot < 0 || selectedHotbarSlot > 8) {
                throw new IllegalArgumentException("selectedHotbarSlot must be between 0 and 8");
            }
        }
    }

    record InventoryContents(int size, List<InventorySlot> slots) {
        public InventoryContents {
            if (size < 1) {
                throw new IllegalArgumentException("Inventory size must be positive");
            }
            slots = List.copyOf(slots);
            int previousSlot = -1;
            for (InventorySlot entry : slots) {
                if (entry.slot() >= size || entry.slot() <= previousSlot) {
                    throw new IllegalArgumentException(
                            "Inventory slots must be distinct, bounded, and ordered");
                }
                previousSlot = entry.slot();
            }
        }
    }

    record InventorySlot(int slot, ItemSummary item) {
        public InventorySlot {
            if (slot < 0) {
                throw new IllegalArgumentException("Inventory slot must be non-negative");
            }
            Objects.requireNonNull(item, "item");
        }
    }

    record ItemSummary(
            String type,
            int amount,
            int maxStackSize,
            Integer damage,
            Integer maxDamage,
            boolean unbreakable,
            List<Enchantment> enchantments) {
        public ItemSummary {
            type = requireText(type, "type");
            if (amount < 1 || maxStackSize < 1) {
                throw new IllegalArgumentException("Item counts must be positive");
            }
            if (damage != null && damage < 0) {
                throw new IllegalArgumentException("Item damage must be non-negative");
            }
            if (maxDamage != null && maxDamage < 1) {
                throw new IllegalArgumentException("Item maxDamage must be positive");
            }
            enchantments = List.copyOf(enchantments);
            String previousType = null;
            for (Enchantment enchantment : enchantments) {
                if (previousType != null && previousType.compareTo(enchantment.type()) >= 0) {
                    throw new IllegalArgumentException(
                            "Enchantments must be uniquely sorted by type");
                }
                previousType = enchantment.type();
            }
        }
    }

    record Enchantment(String type, int level) {
        public Enchantment {
            type = requireText(type, "type");
        }
    }

    record PlayerVitals(
            double health,
            double maxHealth,
            double absorptionAmount,
            int foodLevel,
            double saturation,
            double exhaustion,
            int remainingAir,
            int maximumAir,
            int experienceLevel,
            double experienceProgress,
            int calculatedExperiencePoints,
            int fireTicks,
            int freezeTicks) {
        public PlayerVitals {
            requireFinite(health, "health");
            requireFinite(maxHealth, "maxHealth");
            requireFinite(absorptionAmount, "absorptionAmount");
            requireFinite(saturation, "saturation");
            requireFinite(exhaustion, "exhaustion");
            requireFinite(experienceProgress, "experienceProgress");
        }
    }

    record PlayerMovement(
            Vector3 velocity,
            double fallDistance,
            boolean allowFlight,
            boolean flying,
            boolean sneaking,
            boolean sprinting,
            boolean swimming,
            boolean gliding,
            boolean sleeping,
            boolean blocking,
            boolean riptiding) {
        public PlayerMovement {
            Objects.requireNonNull(velocity, "velocity");
            requireFinite(fallDistance, "fallDistance");
        }
    }

    record PlayerClient(
            int pingMillis,
            String locale,
            int clientViewDistance,
            int viewDistance,
            int sendViewDistance) {
        public PlayerClient {
            locale = requireText(locale, "locale");
        }
    }

    record Effect(
            String type,
            int amplifier,
            int durationTicks,
            boolean ambient,
            boolean particles,
            boolean icon) {
        public Effect {
            type = requireText(type, "type");
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
