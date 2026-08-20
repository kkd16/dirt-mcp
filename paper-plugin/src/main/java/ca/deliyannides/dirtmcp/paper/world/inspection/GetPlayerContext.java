package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface GetPlayerContext {
    int MAX_PLAYER_SELECTOR_LENGTH = 36;

    Result getPlayerContext(Request request) throws OperationException;

    record Request(String player, Includes include, ViewRequest view) {}

    record Includes(
            boolean view,
            boolean equipment,
            boolean inventory,
            boolean enderChest,
            boolean vitals,
            boolean movement,
            boolean client,
            boolean effects) {}

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

    record Result(
            Instant capturedAt,
            PlayerIdentity player,
            String world,
            UUID worldId,
            Position feetPosition,
            BlockPosition blockPosition,
            Position eyePosition,
            Rotation rotation,
            Vector3 lookDirection,
            String gameMode,
            String pose,
            boolean onGround,
            PerspectiveView view,
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

    record PlayerIdentity(String name, UUID uuid) {
        public PlayerIdentity {
            name = requireText(name, "name");
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    record Position(double x, double y, double z) {
        public Position {
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
        }
    }

    record Rotation(double yaw, double pitch) {
        public Rotation {
            requireFinite(yaw, "yaw");
            requireFinite(pitch, "pitch");
        }
    }

    record Vector3(double x, double y, double z) {
        public Vector3 {
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            x = canonicalZero(x);
            y = canonicalZero(y);
            z = canonicalZero(z);
        }
    }

    record PerspectiveView(
            ViewBasis basis,
            Viewport viewport,
            int checkedChunkCount,
            List<String> blockStatePalette,
            List<ViewHit> hits,
            Integer crosshairHitIndex) {
        public PerspectiveView {
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
            Position hitPosition,
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

    private static double canonicalZero(double value) {
        return value == 0 ? 0 : value;
    }
}
