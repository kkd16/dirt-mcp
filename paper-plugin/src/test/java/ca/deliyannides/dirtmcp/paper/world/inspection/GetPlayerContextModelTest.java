package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Enchantment;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.InventoryContents;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.InventorySlot;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.ItemSummary;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.PerspectiveView;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Position;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Vector3;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.ViewBasis;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.ViewHit;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Viewport;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GetPlayerContextModelTest {
    @Test
    void acceptsAConsistentFirstAppearancePaletteAndCrosshair() {
        PerspectiveView view = validView();

        assertEquals(2, view.hits().size());
        assertEquals(1, view.crosshairHitIndex());
    }

    @Test
    void rejectsInvalidCheckedChunkCountAndCrosshair() {
        Viewport viewport = viewport();
        assertDoesNotThrow(
                () -> new PerspectiveView(basis(), viewport, 0, List.of(), List.of(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PerspectiveView(basis(), viewport, -1, List.of(), List.of(), null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PerspectiveView(
                                basis(),
                                viewport,
                                1,
                                List.of("minecraft:stone"),
                                List.of(hit(1, 1, 1)),
                                null));
    }

    @Test
    void rejectsInvalidSparseHitAndPaletteRelationships() {
        Viewport viewport = viewport();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PerspectiveView(
                                basis(),
                                viewport,
                                1,
                                List.of("minecraft:stone"),
                                List.of(hit(3, 0, 1)),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PerspectiveView(
                                basis(),
                                viewport,
                                1,
                                List.of("minecraft:stone"),
                                List.of(hit(1, 0, 1), hit(0, 0, 1)),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PerspectiveView(
                                basis(),
                                viewport,
                                1,
                                List.of("minecraft:stone", "minecraft:dirt"),
                                List.of(hit(0, 0, 2)),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PerspectiveView(
                                basis(),
                                viewport,
                                1,
                                List.of("minecraft:stone", "minecraft:dirt"),
                                List.of(hit(0, 0, 1)),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PerspectiveView(
                                basis(),
                                viewport,
                                1,
                                List.of("minecraft:stone", "minecraft:stone"),
                                List.of(hit(0, 0, 1)),
                                null));
    }

    @Test
    void validatesResolvedViewportSemantics() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Viewport(2, 3, 70, 70, 32, "never", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Viewport(3, 3, 171, 70, 32, "never", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Viewport(3, 3, 70, 180, 32, "never", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Viewport(3, 3, 70, 70, 129, "never", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Viewport(3, 3, 70, 70, 32, "sometimes", false));
        assertDoesNotThrow(() -> new Viewport(3, 3, 70, 70, 32, "always", true));
    }

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

    private static PerspectiveView validView() {
        return new PerspectiveView(
                basis(),
                viewport(),
                1,
                List.of("minecraft:stone", "minecraft:dirt"),
                List.of(hit(0, 0, 1), hit(1, 1, 2)),
                1);
    }

    private static ViewBasis basis() {
        return new ViewBasis(new Vector3(0, 0, 1), new Vector3(-1, 0, 0), new Vector3(0, 1, 0));
    }

    private static Viewport viewport() {
        return new Viewport(3, 3, 70, 70, 32, "never", false);
    }

    private static ViewHit hit(int row, int column, int paletteIndex) {
        return new ViewHit(
                row,
                column,
                paletteIndex,
                new BlockPosition(column, 64, row),
                new Position(column + 0.5, 64.5, row + 0.5),
                null,
                1);
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
