package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BlockPaletteTest {
    @Test
    void selectionIsStableForSeedAndCoordinates() {
        BlockPalette<String> first = palette(27);
        BlockPalette<String> second = palette(27);

        List<String> selections = new ArrayList<>();
        List<String> repeated = new ArrayList<>();
        for (int coordinate = -100; coordinate <= 100; coordinate++) {
            selections.add(first.at(coordinate, -64 + coordinate, coordinate * 17));
            repeated.add(second.at(coordinate, -64 + coordinate, coordinate * 17));
        }

        assertEquals(selections, repeated);
    }

    @Test
    void seedChangesCoordinateSelection() {
        BlockPalette<String> first = palette(1);
        BlockPalette<String> second = palette(2);

        List<String> firstSelections = new ArrayList<>();
        List<String> secondSelections = new ArrayList<>();
        for (int coordinate = 0; coordinate < 100; coordinate++) {
            firstSelections.add(first.at(coordinate, 10, -coordinate));
            secondSelections.add(second.at(coordinate, 10, -coordinate));
        }

        assertNotEquals(firstSelections, secondSelections);
    }

    @Test
    void selectionRespectsRelativeWeights() {
        BlockPalette<String> palette = palette(901);
        int stone = 0;
        for (int coordinate = 0; coordinate < 10_000; coordinate++) {
            if (palette.at(coordinate, 40, coordinate * 31).equals("stone")) {
                stone++;
            }
        }

        assertEquals(2_500, stone, 200);
    }

    @Test
    void rejectsEmptyNullAndNonPositiveEntries() {
        assertThrows(IllegalArgumentException.class, () -> new BlockPalette<>(List.of(), 0));
        assertThrows(
                NullPointerException.class,
                () -> new BlockPalette<String>(java.util.Collections.singletonList(null), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BlockPalette<>(List.of(new BlockPalette.WeightedValue<>("stone", 0)), 0));
    }

    private static BlockPalette<String> palette(int seed) {
        return new BlockPalette<>(
                List.of(
                        new BlockPalette.WeightedValue<>("stone", 25),
                        new BlockPalette.WeightedValue<>("dirt", 75)),
                seed);
    }
}
