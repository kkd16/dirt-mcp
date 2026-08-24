package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.ExactBlockStructure.ExactPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.BlockSample;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class RegionBlockAlgorithms {
    private static final Comparator<InspectedBlock> BLOCK_ORDER =
            Comparator.comparingInt((InspectedBlock block) -> block.position().y())
                    .thenComparingInt(block -> block.position().z())
                    .thenComparingInt(block -> block.position().x());

    private RegionBlockAlgorithms() {}

    static Map<String, Long> countBlockStates(Cuboid region, CapturedRegion capture) {
        Map<String, Long> counts = new HashMap<>();
        for (long y = region.min().y(); y <= region.max().y(); y++) {
            for (long z = region.min().z(); z <= region.max().z(); z++) {
                for (long x = region.min().x(); x <= region.max().x(); x++) {
                    BlockPosition position = new BlockPosition((int) x, (int) y, (int) z);
                    counts.merge(capture.sample(position).blockState(), 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    static List<InspectedBlock> collectBlocks(
            Cuboid region, CapturedRegion capture, boolean includeAir) {
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
                }
            }
        }
        return List.copyOf(blocks);
    }

    static PackedBlocks packBlocks(
            BlockPosition origin, List<InspectedBlock> blocks, int maxResults, int maxPalettes)
            throws OperationException {
        Map<String, Integer> paletteIndexes = new HashMap<>();
        List<List<ExactPaletteEntry>> palettes = new ArrayList<>();
        Map<BlockPosition, Integer> remaining = new HashMap<>();
        List<InspectedBlock> orderedBlocks = blocks.stream().sorted(BLOCK_ORDER).toList();
        for (InspectedBlock block : orderedBlocks) {
            Integer paletteIndex = paletteIndexes.get(block.blockState());
            if (paletteIndex == null) {
                if (palettes.size() >= maxPalettes) {
                    throw resultTooLarge(
                            "Inspection result requires more than " + maxPalettes + " palettes",
                            new ErrorDetails.ResultTooLarge.Palettes(
                                    (long) palettes.size() + 1, maxPalettes));
                }
                paletteIndex = palettes.size();
                paletteIndexes.put(block.blockState(), paletteIndex);
                palettes.add(List.of(new ExactPaletteEntry(block.blockState())));
            }
            remaining.put(block.position(), paletteIndex);
        }

        List<Placement> placements = new ArrayList<>();
        List<Run> runs = new ArrayList<>();
        int entryCount = 0;
        for (InspectedBlock block : orderedBlocks) {
            Integer paletteIndex = remaining.get(block.position());
            if (paletteIndex == null) {
                continue;
            }

            BlockPosition from = block.position();
            int toX = expandX(from, paletteIndex, remaining);
            int toZ = expandZ(from, toX, paletteIndex, remaining);
            int toY = expandY(from, toX, toZ, paletteIndex, remaining);
            removeCuboid(from, toX, toY, toZ, remaining);

            int x = relative(from.x(), origin.x());
            int y = relative(from.y(), origin.y());
            int z = relative(from.z(), origin.z());
            if (from.x() == toX && from.y() == toY && from.z() == toZ) {
                placements.add(new Placement(paletteIndex, x, y, z));
            } else {
                runs.add(
                        new Run(
                                paletteIndex,
                                x,
                                y,
                                z,
                                relative(toX, origin.x()),
                                relative(toY, origin.y()),
                                relative(toZ, origin.z())));
            }
            entryCount++;
            if (entryCount > maxResults) {
                throw resultTooLarge(
                        "Inspection result exceeds maxResults of " + maxResults + " entries",
                        new ErrorDetails.ResultTooLarge.StructureEntries(
                                (long) maxResults + 1, maxResults));
            }
        }
        return new PackedBlocks(palettes, placements, runs);
    }

    private static int expandX(
            BlockPosition from, int paletteIndex, Map<BlockPosition, Integer> remaining) {
        int toX = from.x();
        while (toX < Integer.MAX_VALUE
                && matches(toX + 1, from.y(), from.z(), paletteIndex, remaining)) {
            toX++;
        }
        return toX;
    }

    private static int expandZ(
            BlockPosition from, int toX, int paletteIndex, Map<BlockPosition, Integer> remaining) {
        int toZ = from.z();
        while (toZ < Integer.MAX_VALUE
                && rowMatches(from.x(), toX, from.y(), toZ + 1, paletteIndex, remaining)) {
            toZ++;
        }
        return toZ;
    }

    private static int expandY(
            BlockPosition from,
            int toX,
            int toZ,
            int paletteIndex,
            Map<BlockPosition, Integer> remaining) {
        int toY = from.y();
        while (toY < Integer.MAX_VALUE
                && planeMatches(from.x(), toX, toY + 1, from.z(), toZ, paletteIndex, remaining)) {
            toY++;
        }
        return toY;
    }

    private static boolean rowMatches(
            int fromX,
            int toX,
            int y,
            int z,
            int paletteIndex,
            Map<BlockPosition, Integer> remaining) {
        for (long x = fromX; x <= toX; x++) {
            if (!matches((int) x, y, z, paletteIndex, remaining)) {
                return false;
            }
        }
        return true;
    }

    private static boolean planeMatches(
            int fromX,
            int toX,
            int y,
            int fromZ,
            int toZ,
            int paletteIndex,
            Map<BlockPosition, Integer> remaining) {
        for (long z = fromZ; z <= toZ; z++) {
            if (!rowMatches(fromX, toX, y, (int) z, paletteIndex, remaining)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(
            int x, int y, int z, int paletteIndex, Map<BlockPosition, Integer> remaining) {
        return Integer.valueOf(paletteIndex).equals(remaining.get(new BlockPosition(x, y, z)));
    }

    private static void removeCuboid(
            BlockPosition from, int toX, int toY, int toZ, Map<BlockPosition, Integer> remaining) {
        for (long y = from.y(); y <= toY; y++) {
            for (long z = from.z(); z <= toZ; z++) {
                for (long x = from.x(); x <= toX; x++) {
                    remaining.remove(new BlockPosition((int) x, (int) y, (int) z));
                }
            }
        }
    }

    private static int relative(int coordinate, int origin) {
        return Math.toIntExact((long) coordinate - origin);
    }

    private static OperationException resultTooLarge(
            String message, ErrorDetails.ResultTooLarge details) {
        return new OperationException(OperationFailure.RESULT_TOO_LARGE, message, details);
    }

    record PackedBlocks(
            List<List<ExactPaletteEntry>> palettes, List<Placement> placements, List<Run> runs) {
        PackedBlocks {
            palettes = palettes.stream().map(List::copyOf).toList();
            placements = List.copyOf(placements);
            runs = List.copyOf(runs);
        }
    }

    record InspectedBlock(BlockPosition position, String blockState) {
        InspectedBlock {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(blockState, "blockState");
        }
    }
}
