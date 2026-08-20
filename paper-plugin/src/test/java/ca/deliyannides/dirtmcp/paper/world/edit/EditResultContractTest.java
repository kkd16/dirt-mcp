package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EditResultContractTest {
    private static final BlockBounds BOUNDS =
            new BlockBounds(new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0));
    private static final List<DestinationPaletteEntry> PALETTE =
            List.of(new DestinationPaletteEntry("minecraft:stone", null));

    @Test
    void rejectsImpossibleEditResultCounts() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ReplaceRegionBlocks.Result(
                                "world",
                                BOUNDS,
                                List.of("minecraft:dirt"),
                                PALETTE,
                                0,
                                EditOutcome.PREVIEW,
                                1,
                                2,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FillRegion.Result(
                                "world", BOUNDS, PALETTE, 0, EditOutcome.PREVIEW, 1, 2, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SetBlocks.Result(
                                "world",
                                BOUNDS,
                                List.of(PALETTE),
                                0,
                                EditOutcome.PREVIEW,
                                2,
                                1,
                                2,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FillRegion.Result(
                                "world", BOUNDS, PALETTE, 0, EditOutcome.NO_CHANGE, 1, 1, null));
    }

    @Test
    void rejectsInconsistentHistoryAndUndoCorrelation() {
        EditRecord edit =
                new EditRecord(
                        UUID.fromString("11111111-1111-4111-8111-111111111111"),
                        UUID.fromString("22222222-2222-4222-8222-222222222222"),
                        EditOperation.FILL_REGION,
                        "world",
                        UUID.fromString("33333333-3333-4333-8333-333333333333"),
                        BOUNDS,
                        1,
                        "2026-08-20T00:00:00Z",
                        EditStatus.COMMITTED);

        assertThrows(
                IllegalArgumentException.class,
                () -> new GetEditHistory.Result("other_world", List.of(edit)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GetEditHistory.Result("world", List.of(edit, edit)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UndoEdit.Result(
                                edit,
                                UUID.fromString("44444444-4444-1444-8444-444444444444"),
                                "2026-08-20T00:00:01Z"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UndoEdit.Request(
                                "world", UUID.fromString("77777777-7777-1777-8777-777777777777")));
    }

    @Test
    void normalizesEditAndUndoInstants() {
        EditRecord edit =
                new EditRecord(
                        UUID.fromString("11111111-1111-4111-8111-111111111111"),
                        UUID.fromString("22222222-2222-4222-8222-222222222222"),
                        EditOperation.FILL_REGION,
                        "world",
                        UUID.fromString("33333333-3333-4333-8333-333333333333"),
                        BOUNDS,
                        1,
                        "2026-08-20T00:00:00.000000Z",
                        EditStatus.COMMITTED);
        UndoEdit.Result undo =
                new UndoEdit.Result(
                        edit,
                        UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        "2026-08-20T00:00:01.120000000Z");

        assertEquals("2026-08-20T00:00:00Z", edit.completedAt());
        assertEquals("2026-08-20T00:00:01.120Z", undo.undoneAt());
    }
}
