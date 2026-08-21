package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Enchantment;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.InventoryContents;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.InventorySlot;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.ItemSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GetPlayerContextModelTest {
    @Test
    void requiresSparseInventorySlotsToBeOrderedAndBounded() {
        ItemSummary item = item();
        assertDoesNotThrow(
                () ->
                        new InventoryContents(
                                3,
                                List.of(new InventorySlot(0, item), new InventorySlot(2, item))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InventoryContents(
                                3,
                                List.of(new InventorySlot(2, item), new InventorySlot(1, item))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryContents(3, List.of(new InventorySlot(3, item))));
    }

    @Test
    void permitsIndependentDamageAndSignedUnsafeEnchantmentLevels() {
        ItemSummary item = item();
        assertEquals(7, item.damage());
        assertEquals(4, item.maxDamage());
        assertEquals(-3, item.enchantments().getFirst().level());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemSummary("minecraft:stone", 1, 64, -1, null, false, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemSummary("minecraft:stone", 1, 64, null, 0, false, List.of()));
    }

    private static ItemSummary item() {
        return new ItemSummary(
                "minecraft:diamond_pickaxe",
                1,
                1,
                7,
                4,
                false,
                List.of(new Enchantment("minecraft:efficiency", -3)));
    }
}
