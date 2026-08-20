package ca.deliyannides.dirtmcp.paper.world.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.BlockRun;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.Format;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.BlockSample;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
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
                RegionBlockAlgorithms.collectBlocks(
                        region, captured(samples), false, 2, Format.BLOCKS);

        assertEquals(
                List.of(
                        new InspectedBlock(new BlockPosition(-1, 0, -1), "minecraft:stone"),
                        new InspectedBlock(new BlockPosition(0, 1, -1), "minecraft:glass")),
                blocks);
    }

    @Test
    void appliesTheBlockResultLimitWithoutTruncating() throws Exception {
        Cuboid region = cuboid(new BlockPosition(0, 0, 0), new BlockPosition(1, 0, 0));

        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () ->
                                RegionBlockAlgorithms.collectBlocks(
                                        region,
                                        captured(
                                                Map.of(
                                                        new BlockPosition(0, 0, 0), sample("a"),
                                                        new BlockPosition(1, 0, 0), sample("b"))),
                                        true,
                                        1,
                                        Format.BLOCKS));

        assertEquals(OperationFailure.RESULT_TOO_LARGE, exception.failure());
    }

    @Test
    void groupsIdenticalBlocksAlongTheirLongestAxis() throws Exception {
        List<InspectedBlock> blocks =
                List.of(
                        block(0, 0, 0, "stone"),
                        block(1, 0, 0, "glass"),
                        block(2, 0, 0, "glass"),
                        block(0, 1, 0, "stone"),
                        block(0, 2, 0, "stone"));

        List<BlockRun> runs = RegionBlockAlgorithms.groupSortedRuns(blocks, 2);

        assertEquals(
                List.of(
                        new BlockRun(
                                "stone", new BlockPosition(0, 0, 0), new BlockPosition(0, 2, 0)),
                        new BlockRun(
                                "glass", new BlockPosition(1, 0, 0), new BlockPosition(2, 0, 0))),
                runs);
    }

    @Test
    void rejectsRunResultsOverTheRequestedCap() {
        List<InspectedBlock> blocks = List.of(block(0, 0, 0, "stone"), block(2, 0, 0, "stone"));

        OperationException exception =
                assertThrows(
                        OperationException.class,
                        () -> RegionBlockAlgorithms.groupSortedRuns(blocks, 1));

        assertEquals(OperationFailure.RESULT_TOO_LARGE, exception.failure());
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
