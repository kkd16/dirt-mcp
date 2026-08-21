package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.Result;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewBasis;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewHit;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.Viewport;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GetPerspectiveViewModelTest {
    @Test
    void acceptsAConsistentFirstAppearancePaletteAndCrosshair() {
        Result view =
                result(
                        1,
                        List.of("minecraft:stone", "minecraft:dirt"),
                        List.of(hit(0, 0, 1), hit(1, 1, 2)),
                        1);

        assertEquals(2, view.hits().size());
        assertEquals(1, view.crosshairHitIndex());
    }

    @Test
    void rejectsInvalidCheckedChunkCountAndCrosshair() {
        assertDoesNotThrow(() -> result(0, List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> result(-1, List.of(), List.of(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> result(1, List.of("minecraft:stone"), List.of(hit(1, 1, 1)), null));
    }

    @Test
    void rejectsInvalidSparseHitAndPaletteRelationships() {
        assertThrows(
                IllegalArgumentException.class,
                () -> result(1, List.of("minecraft:stone"), List.of(hit(3, 0, 1)), null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        result(
                                1,
                                List.of("minecraft:stone"),
                                List.of(hit(1, 0, 1), hit(0, 0, 1)),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        result(
                                1,
                                List.of("minecraft:stone", "minecraft:dirt"),
                                List.of(hit(0, 0, 2)),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        result(
                                1,
                                List.of("minecraft:stone", "minecraft:dirt"),
                                List.of(hit(0, 0, 1)),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        result(
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

    private static Result result(
            int checkedChunkCount, List<String> palette, List<ViewHit> hits, Integer crosshair) {
        return new Result(
                Instant.EPOCH,
                new GetPerspectiveView.ResolvedLocationSource(),
                "world",
                new UUID(0, 1),
                new ExactPosition(0.5, 64, 0.5),
                new Rotation(0, 0),
                new Vector3(0, 0, 1),
                basis(),
                viewport(),
                checkedChunkCount,
                palette,
                hits,
                crosshair);
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
                new ExactPosition(column + 0.5, 64.5, row + 0.5),
                null,
                1);
    }
}
