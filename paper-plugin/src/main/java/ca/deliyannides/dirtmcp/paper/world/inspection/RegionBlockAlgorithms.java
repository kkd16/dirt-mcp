package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.BlockRun;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.Format;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.BlockSample;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class RegionBlockAlgorithms {
    private static final Comparator<InspectedBlock> BLOCK_ORDER =
            Comparator.comparingInt((InspectedBlock block) -> block.position().y())
                    .thenComparingInt(block -> block.position().z())
                    .thenComparingInt(block -> block.position().x());

    private RegionBlockAlgorithms() {}

    static Map<String, Long> countBlockStates(Cuboid region, CapturedRegion capture) {
        Map<String, Long> counts = new TreeMap<>();
        forEachPosition(
                region,
                position -> counts.merge(capture.sample(position).blockState(), 1L, Long::sum));
        return counts;
    }

    static List<InspectedBlock> collectBlocks(
            Cuboid region,
            CapturedRegion capture,
            boolean includeAir,
            int maxResults,
            Format format)
            throws OperationException {
        List<InspectedBlock> blocks = new ArrayList<>();
        for (long y = region.min().y(); y <= region.max().y(); y++) {
            for (long z = region.min().z(); z <= region.max().z(); z++) {
                for (long x = region.min().x(); x <= region.max().x(); x++) {
                    BlockPosition position = new BlockPosition((int) x, (int) y, (int) z);
                    BlockSample sample = capture.sample(position);
                    if ((!includeAir && sample.air()) || !sample.selectedByPatterns()) {
                        continue;
                    }
                    blocks.add(new InspectedBlock(position, sample.blockState()));
                    if (format == Format.BLOCKS && blocks.size() > maxResults) {
                        throw resultTooLarge(maxResults);
                    }
                }
            }
        }
        blocks.sort(BLOCK_ORDER);
        return List.copyOf(blocks);
    }

    static List<BlockRun> groupSortedRuns(List<InspectedBlock> blocks, int maxResults)
            throws OperationException {
        Map<BlockPosition, String> remaining = new HashMap<>();
        for (InspectedBlock block : blocks) {
            remaining.put(block.position(), block.blockState());
        }

        List<BlockRun> runs = new ArrayList<>();
        for (InspectedBlock block : blocks) {
            String state = remaining.get(block.position());
            if (state == null) {
                continue;
            }

            Axis axis = Axis.X;
            int length = 0;
            for (Axis candidate : Axis.values()) {
                int candidateLength = runLength(block.position(), state, candidate, remaining);
                if (candidateLength > length) {
                    axis = candidate;
                    length = candidateLength;
                }
            }

            BlockPosition to = advance(block.position(), axis, length - 1);
            if (to == null) {
                throw new IllegalStateException("A bounded block run overflowed its region");
            }
            runs.add(new BlockRun(state, block.position(), to));
            if (runs.size() > maxResults) {
                throw resultTooLarge(maxResults);
            }
            for (int offset = 0; offset < length; offset++) {
                remaining.remove(advance(block.position(), axis, offset));
            }
        }
        return List.copyOf(runs);
    }

    private static void forEachPosition(Cuboid region, PositionConsumer consumer) {
        for (long y = region.min().y(); y <= region.max().y(); y++) {
            for (long z = region.min().z(); z <= region.max().z(); z++) {
                for (long x = region.min().x(); x <= region.max().x(); x++) {
                    consumer.accept(new BlockPosition((int) x, (int) y, (int) z));
                }
            }
        }
    }

    private static int runLength(
            BlockPosition from, String state, Axis axis, Map<BlockPosition, String> remaining) {
        int length = 1;
        while (state.equals(remaining.get(advance(from, axis, length)))) {
            length++;
        }
        return length;
    }

    private static BlockPosition advance(BlockPosition position, Axis axis, int distance) {
        long x = position.x() + (long) axis.x * distance;
        long y = position.y() + (long) axis.y * distance;
        long z = position.z() + (long) axis.z * distance;
        if (x < Integer.MIN_VALUE
                || x > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE
                || y > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE
                || z > Integer.MAX_VALUE) {
            return null;
        }
        return new BlockPosition((int) x, (int) y, (int) z);
    }

    private static OperationException resultTooLarge(int maxResults) {
        return new OperationException(
                OperationFailure.RESULT_TOO_LARGE,
                "Inspection result exceeds maxResults of " + maxResults + " entries");
    }

    @FunctionalInterface
    private interface PositionConsumer {
        void accept(BlockPosition position);
    }

    private enum Axis {
        X(1, 0, 0),
        Y(0, 1, 0),
        Z(0, 0, 1);

        private final int x;
        private final int y;
        private final int z;

        Axis(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
