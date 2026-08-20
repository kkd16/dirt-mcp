package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.Format;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockDimensions;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InspectionContractsTest {
    @Test
    void requestPatternListsAreDefensivelyCopied() {
        List<String> patterns = new ArrayList<>(List.of("stone"));
        GetRegionBlocks.Request request =
                new GetRegionBlocks.Request(
                        "world",
                        new BlockPosition(0, 0, 0),
                        new BlockPosition(0, 0, 0),
                        patterns,
                        List.of(),
                        false,
                        1,
                        Format.BLOCKS);

        patterns.add("dirt");

        assertEquals(List.of("stone"), request.includeBlockStatePatterns());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.includeBlockStatePatterns().add("glass"));
    }

    @Test
    void countResultCopiesAndSortsItsMap() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("stone", 1L);
        counts.put("air", 2L);
        CountRegionBlockStates.Result result =
                new CountRegionBlockStates.Result(
                        "world",
                        new BlockBounds(new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0)),
                        new BlockDimensions(1, 1, 1),
                        1,
                        counts);

        counts.put("dirt", 3L);

        assertEquals(List.of("air", "stone"), List.copyOf(result.blockStateCounts().keySet()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.blockStateCounts().put("glass", 1L));
    }
}
