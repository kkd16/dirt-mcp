package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.ExactBlockStructure.ExactPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionBlockAlgorithms.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionBlockAlgorithms.PackedBlocks;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.BlockSample;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RegionBlockAlgorithmsTest {
    @Test
    void collectsSelectedBlocksInYZXOrder() throws Exception {
        Cuboid region = cuboid(new BlockPosition(-1, 0, -1), new BlockPosition(0, 1, -1));
        Map<BlockPosition, BlockSample> samples =
                Map.of(
                        new BlockPosition(-1, 0, -1),
                        sample("minecraft:stone"),
                        new BlockPosition(0, 0, -1),
                        new BlockSample("minecraft:air", true, true),
                        new BlockPosition(-1, 1, -1),
                        new BlockSample("minecraft:dirt", false, false),
                        new BlockPosition(0, 1, -1),
                        sample("minecraft:glass"));

        List<InspectedBlock> blocks =
                RegionBlockAlgorithms.collectBlocks(region, captured(samples), false);

        assertEquals(
                List.of(block(-1, 0, -1, "minecraft:stone"), block(0, 1, -1, "minecraft:glass")),
                blocks);
    }

    @Test
    void packsFirstSeenPalettesAndGreedyXZYCuboids() throws Exception {
        List<InspectedBlock> blocks =
                List.of(
                        block(3, 1, 1, "lantern"),
                        block(0, 1, 1, "stone"),
                        block(1, 1, 1, "stone"),
                        block(1, 1, 0, "stone"),
                        block(0, 1, 0, "stone"),
                        block(1, 0, 1, "stone"),
                        block(0, 0, 1, "stone"),
                        block(1, 0, 0, "stone"),
                        block(0, 0, 0, "stone"));

        PackedBlocks packed =
                RegionBlockAlgorithms.packBlocks(new BlockPosition(0, 0, 0), blocks, 2, 64);

        assertEquals(
                List.of(
                        List.of(new ExactPaletteEntry("stone")),
                        List.of(new ExactPaletteEntry("lantern"))),
                packed.palettes());
        assertEquals(List.of(new Placement(1, 3, 1, 1)), packed.placements());
        assertEquals(List.of(new Run(0, 0, 0, 0, 1, 1, 1)), packed.runs());
    }

    @Test
    void returnsOriginRelativeCoordinates() throws Exception {
        PackedBlocks packed =
                RegionBlockAlgorithms.packBlocks(
                        new BlockPosition(100, 64, 100),
                        List.of(
                                block(100, 64, 100, "stone"),
                                block(115, 64, 100, "stone"),
                                block(100, 65, 100, "lantern"),
                                block(100, 72, 100, "lantern")),
                        4,
                        64);

        assertEquals(
                List.of(
                        new Placement(0, 0, 0, 0),
                        new Placement(0, 15, 0, 0),
                        new Placement(1, 0, 1, 0),
                        new Placement(1, 0, 8, 0)),
                packed.placements());
        assertEquals(List.of(), packed.runs());
    }

    @Test
    void rejectsStructureEntriesOverTheRequestedCap() {
        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () ->
                                RegionBlockAlgorithms.packBlocks(
                                        new BlockPosition(0, 0, 0),
                                        List.of(block(0, 0, 0, "stone"), block(2, 0, 0, "stone")),
                                        1,
                                        64));

        assertEquals(OperationFailure.RESULT_TOO_LARGE, exception.failure());
        assertEquals(
                new ErrorDetails.ResultTooLarge.StructureEntries(2, 1),
                exception.details().orElseThrow());
    }

    @Test
    void rejectsPalettesOverTheConfiguredCap() {
        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () ->
                                RegionBlockAlgorithms.packBlocks(
                                        new BlockPosition(0, 0, 0),
                                        List.of(block(0, 0, 0, "stone"), block(1, 0, 0, "dirt")),
                                        2,
                                        1));

        assertEquals(
                new ErrorDetails.ResultTooLarge.Palettes(2, 1), exception.details().orElseThrow());
    }

    @Test
    void countsBlockStates() throws Exception {
        Cuboid region = cuboid(new BlockPosition(0, 0, 0), new BlockPosition(2, 0, 0));
        Map<String, Long> counts =
                RegionBlockAlgorithms.countBlockStates(
                        region,
                        captured(
                                Map.of(
                                        new BlockPosition(0, 0, 0), sample("stone"),
                                        new BlockPosition(1, 0, 0), sample("air"),
                                        new BlockPosition(2, 0, 0), sample("stone"))));

        assertEquals(Map.of("air", 1L, "stone", 2L), counts);
    }

    private static Cuboid cuboid(BlockPosition min, BlockPosition max) throws OperationException {
        return RegionGeometry.normalize(min, max, 100);
    }

    private static InspectedBlock block(int x, int y, int z, String state) {
        return new InspectedBlock(new BlockPosition(x, y, z), state);
    }

    private static BlockSample sample(String state) {
        return new BlockSample(state, false, true);
    }

    private static CapturedRegion captured(Map<BlockPosition, BlockSample> samples) {
        return new CapturedRegion() {
            @Override
            public String worldName() {
                return "world";
            }

            @Override
            public BlockSample sample(BlockPosition position) {
                return samples.get(position);
            }
        };
    }
}
