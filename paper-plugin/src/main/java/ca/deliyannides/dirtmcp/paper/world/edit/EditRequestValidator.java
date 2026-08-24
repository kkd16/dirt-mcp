package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure validation and geometry expansion for edit commands. */
final class EditRequestValidator {
    private final int maxRegionVolume;
    private final int maxEditTouchedChunks;
    private final int maxBlockStatePatterns;
    private final int maxPaletteEntries;
    private final int maxChangedBlocks;
    private final int maxUndoEntries;

    EditRequestValidator(
            int maxRegionVolume,
            int maxEditTouchedChunks,
            int maxBlockStatePatterns,
            int maxPaletteEntries,
            int maxChangedBlocks,
            int maxUndoEntries) {
        if (maxRegionVolume < 1
                || maxEditTouchedChunks < 1
                || maxBlockStatePatterns < 1
                || maxPaletteEntries < 1
                || maxChangedBlocks < 1
                || maxUndoEntries < 1) {
            throw new IllegalArgumentException("Edit validation limits must be positive");
        }
        this.maxRegionVolume = maxRegionVolume;
        this.maxEditTouchedChunks = maxEditTouchedChunks;
        this.maxBlockStatePatterns = maxBlockStatePatterns;
        this.maxPaletteEntries = maxPaletteEntries;
        this.maxChangedBlocks = maxChangedBlocks;
        this.maxUndoEntries = maxUndoEntries;
    }

    ValidatedReplace validateReplace(ReplaceRegionBlocks.Request request)
            throws OperationException {
        Objects.requireNonNull(request, "request");
        validateEditLabel(request.label());
        validateBlockStateList(request.sourceBlockStatePatterns(), "sourceBlockStatePatterns");
        validatePalette(request.destinationPalette(), "destinationPalette");
        Cuboid region =
                RegionGeometry.normalize(request.min(), request.max(), this.maxRegionVolume);
        RegionGeometry.touchedChunks(region, this.maxEditTouchedChunks);
        return new ValidatedReplace(region, effectiveMaxChangedBlocks(request.maxChangedBlocks()));
    }

    ValidatedSet validateSet(SetBlocks.Request request) throws OperationException {
        Objects.requireNonNull(request, "request");
        validateEditLabel(request.label());
        return new ValidatedSet(
                validateSetGeometry(request),
                effectiveMaxChangedBlocks(request.maxChangedBlocks()));
    }

    void validateUndo(UndoEdits.Request request) throws OperationException {
        Objects.requireNonNull(request, "request");
        List<UUID> editIds = request.editIds();
        if (editIds.isEmpty()) {
            throw invalid(
                    "editIds must contain at least one edit ID",
                    new ErrorDetails.InvalidRequest.InvalidValue("editIds"));
        }
        if (editIds.size() > this.maxUndoEntries) {
            throw invalid(
                    "editIds may contain at most " + this.maxUndoEntries + " edit IDs",
                    new ErrorDetails.InvalidRequest.TooManyItems(
                            List.of("editIds"), this.maxUndoEntries));
        }
        Set<UUID> distinct = new HashSet<>();
        for (int index = 0; index < editIds.size(); index++) {
            if (!distinct.add(editIds.get(index))) {
                throw invalid(
                        "editIds must not contain duplicate edit IDs",
                        new ErrorDetails.InvalidRequest.Duplicate("editIds[" + index + "]"));
            }
        }
    }

    private int effectiveMaxChangedBlocks(Integer requested) throws OperationException {
        if (requested == null) {
            return this.maxChangedBlocks;
        }
        if (requested < 1) {
            throw invalid(
                    "maxChangedBlocks must be a positive signed 32-bit integer",
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            "maxChangedBlocks", requested, 1, Integer.MAX_VALUE));
        }
        return Math.min(requested, this.maxChangedBlocks);
    }

    private SetBlockGeometry validateSetGeometry(SetBlocks.Request request)
            throws OperationException {
        if (request.placements().isEmpty() && request.runs().isEmpty()) {
            if (!request.palettes().isEmpty()) {
                throw invalid(
                        "palettes must be empty when placements and runs are empty",
                        new ErrorDetails.InvalidRequest.InvalidValue("palettes"));
            }
            return new SetBlockGeometry(List.of(), List.of(), List.of(), null, 0);
        }
        validateSetPalettes(request.palettes());

        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        SetBlockOccupancy occupancy = new SetBlockOccupancy();
        List<SetBlockGeometry.ResolvedPlacement> placements =
                new ArrayList<>(request.placements().size());
        List<SetBlockGeometry.ResolvedRun> runs = new ArrayList<>(request.runs().size());
        SetBounds bounds = new SetBounds();
        long requestedBlockCount = request.placements().size();
        enforceSetBlockCount(requestedBlockCount);
        for (int placementIndex = 0;
                placementIndex < request.placements().size();
                placementIndex++) {
            Placement placement = request.placements().get(placementIndex);
            String field = "placements[" + placementIndex + "]";
            validatePaletteIndex(placement.paletteIndex(), request.palettes().size(), field);
            BlockPosition position =
                    resolvePosition(
                            request.origin(), placement.x(), placement.y(), placement.z(), field);
            addTouchedChunk(chunks, ChunkPosition.containing(position.x(), position.z()));
            occupancy.add(position, field);
            placements.add(
                    new SetBlockGeometry.ResolvedPlacement(placement.paletteIndex(), position));
            bounds.include(position);
        }
        for (int runIndex = 0; runIndex < request.runs().size(); runIndex++) {
            Run run = request.runs().get(runIndex);
            String field = "runs[" + runIndex + "]";
            validatePaletteIndex(run.paletteIndex(), request.palettes().size(), field);
            if (run.x() > run.toX() || run.y() > run.toY() || run.z() > run.toZ()) {
                throw invalid(
                        field + " must use component-wise forward inclusive corners",
                        new ErrorDetails.InvalidRequest.InvalidValue(field));
            }
            requestedBlockCount = addRunBlockCount(requestedBlockCount, run);
            BlockPosition from =
                    resolvePosition(request.origin(), run.x(), run.y(), run.z(), field);
            BlockPosition to =
                    resolvePosition(request.origin(), run.toX(), run.toY(), run.toZ(), field);
            Cuboid region = new Cuboid(from, to);
            addTouchedChunks(chunks, region);
            occupancy.add(region, field);
            runs.add(new SetBlockGeometry.ResolvedRun(run.paletteIndex(), region));
            bounds.include(region);
        }

        return new SetBlockGeometry(
                placements,
                runs,
                List.copyOf(chunks),
                bounds.build(),
                Math.toIntExact(requestedBlockCount));
    }

    private void validateSetPalettes(List<List<DestinationPaletteEntry>> palettes)
            throws OperationException {
        if (palettes.isEmpty()) {
            throw invalid(
                    "palettes must contain at least one palette",
                    new ErrorDetails.InvalidRequest.InvalidValue("palettes"));
        }
        int entryCount = 0;
        for (int index = 0; index < palettes.size(); index++) {
            validatePalette(palettes.get(index), "palettes[" + index + "]");
            entryCount += palettes.get(index).size();
            if (entryCount > this.maxPaletteEntries) {
                throw invalid(
                        "palettes may contain at most "
                                + this.maxPaletteEntries
                                + " entries in total",
                        new ErrorDetails.InvalidRequest.TooManyItems(
                                List.of("palettes"), this.maxPaletteEntries));
            }
        }
    }

    private void validatePaletteIndex(int paletteIndex, int paletteCount, String field)
            throws OperationException {
        if (paletteIndex < 0 || paletteIndex >= paletteCount) {
            throw invalid(
                    field + "[0] must reference an entry in palettes",
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            field + "[0]", paletteIndex, 0, paletteCount - 1));
        }
    }

    private long addRunBlockCount(long current, Run run) throws OperationException {
        long sizeX = (long) run.toX() - run.x() + 1;
        long sizeY = (long) run.toY() - run.y() + 1;
        long sizeZ = (long) run.toZ() - run.z() + 1;
        long remaining = (long) this.maxRegionVolume - current;
        if (sizeX > remaining || sizeY > remaining / sizeX || sizeZ > remaining / (sizeX * sizeY)) {
            throw setBlockCountExceeded();
        }
        return current + sizeX * sizeY * sizeZ;
    }

    private void enforceSetBlockCount(long blockCount) throws OperationException {
        if (blockCount > this.maxRegionVolume) {
            throw setBlockCountExceeded();
        }
    }

    private OperationException setBlockCountExceeded() {
        return new OperationException(
                OperationFailure.REGION_TOO_LARGE,
                "Set-blocks edit contains more than the maximum of "
                        + this.maxRegionVolume
                        + " blocks",
                new ErrorDetails.RegionTooLarge.BlockCount(
                        (long) this.maxRegionVolume + 1, this.maxRegionVolume));
    }

    private void addTouchedChunks(Set<ChunkPosition> chunks, Cuboid region)
            throws OperationException {
        int minChunkX = region.min().x() >> 4;
        int maxChunkX = region.max().x() >> 4;
        int minChunkZ = region.min().z() >> 4;
        int maxChunkZ = region.max().z() >> 4;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                addTouchedChunk(chunks, new ChunkPosition(chunkX, chunkZ));
            }
        }
    }

    private void addTouchedChunk(Set<ChunkPosition> chunks, ChunkPosition chunk)
            throws OperationException {
        chunks.add(chunk);
        if (chunks.size() > this.maxEditTouchedChunks) {
            throw new OperationException(
                    OperationFailure.REGION_TOO_LARGE,
                    "Operation touches more than the maximum of "
                            + this.maxEditTouchedChunks
                            + " chunks",
                    new ErrorDetails.RegionTooLarge.TouchedChunks(
                            (long) this.maxEditTouchedChunks + 1, this.maxEditTouchedChunks));
        }
    }

    private static BlockPosition resolvePosition(
            BlockPosition origin, int offsetX, int offsetY, int offsetZ, String field)
            throws OperationException {
        long x = (long) origin.x() + offsetX;
        long y = (long) origin.y() + offsetY;
        long z = (long) origin.z() + offsetZ;
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) {
            throw resolvedPositionOutOfRange(field, ".resolved.x", x);
        }
        if (y < Integer.MIN_VALUE || y > Integer.MAX_VALUE) {
            throw resolvedPositionOutOfRange(field, ".resolved.y", y);
        }
        if (z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            throw resolvedPositionOutOfRange(field, ".resolved.z", z);
        }
        return new BlockPosition((int) x, (int) y, (int) z);
    }

    private static OperationException resolvedPositionOutOfRange(
            String field, String coordinate, long value) {
        return invalid(
                field + " resolves outside the signed 32-bit coordinate range",
                new ErrorDetails.InvalidRequest.OutOfRange(
                        field + coordinate, value, Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    private void validatePalette(List<DestinationPaletteEntry> palette, String field)
            throws OperationException {
        if (palette.isEmpty()) {
            throw invalid(
                    field + " must contain at least one entry",
                    new ErrorDetails.InvalidRequest.InvalidValue(field));
        }
        if (palette.size() > this.maxPaletteEntries) {
            throw invalid(
                    field + " may contain at most " + this.maxPaletteEntries + " entries",
                    new ErrorDetails.InvalidRequest.TooManyItems(
                            List.of(field), this.maxPaletteEntries));
        }
        boolean weighted = false;
        boolean unweighted = false;
        long totalWeight = 0;
        for (int index = 0; index < palette.size(); index++) {
            DestinationPaletteEntry entry = palette.get(index);
            if (entry.blockState() == null || entry.blockState().isBlank()) {
                String stateField = field + "[" + index + "].blockState";
                throw invalid(
                        stateField + " must be a non-empty string",
                        new ErrorDetails.InvalidRequest.InvalidValue(stateField));
            }
            Integer weight = entry.weight();
            if (weight == null) {
                unweighted = true;
            } else {
                if (weight < 1 || weight > 100) {
                    String weightField = field + "[" + index + "].weight";
                    throw invalid(
                            weightField + " must be between 1 and 100",
                            new ErrorDetails.InvalidRequest.OutOfRange(
                                    weightField, weight, 1, 100));
                }
                weighted = true;
                totalWeight += weight;
            }
        }
        if (weighted && unweighted) {
            throw invalid(
                    field + " weights must be provided for every entry or omitted from every entry",
                    new ErrorDetails.InvalidRequest.PaletteWeightsMixed(field));
        }
        if (weighted && totalWeight != 100) {
            throw invalid(
                    field + " weights must total 100",
                    new ErrorDetails.InvalidRequest.PaletteWeightTotal(field, totalWeight));
        }
    }

    private void validateBlockStateList(List<String> values, String field)
            throws OperationException {
        if (values.isEmpty()) {
            throw invalid(
                    field + " must contain at least one entry",
                    new ErrorDetails.InvalidRequest.InvalidValue(field));
        }
        if (values.size() > this.maxBlockStatePatterns) {
            throw invalid(
                    field + " may contain at most " + this.maxBlockStatePatterns + " entries",
                    new ErrorDetails.InvalidRequest.TooManyItems(
                            List.of(field), this.maxBlockStatePatterns));
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value.isBlank()) {
                String item = field + "[" + index + "]";
                throw invalid(
                        item + " must be a non-empty string",
                        new ErrorDetails.InvalidRequest.InvalidValue(item));
            }
            if (!distinct.add(value)) {
                throw invalid(
                        field + " contains a duplicate pattern: " + value,
                        new ErrorDetails.InvalidRequest.Duplicate(field + "[" + index + "]"));
            }
        }
    }

    private static void validateEditLabel(String label) throws OperationException {
        int codePoints = label.codePointCount(0, label.length());
        if (codePoints < 1 || codePoints > 120) {
            throw invalid(
                    "label must contain between 1 and 120 Unicode code points",
                    new ErrorDetails.InvalidRequest.InvalidValue("label"));
        }
        int first = label.codePointAt(0);
        int last = label.codePointBefore(label.length());
        if (isLabelWhitespace(first) || isLabelWhitespace(last)) {
            throw invalid(
                    "label must not have leading or trailing whitespace",
                    new ErrorDetails.InvalidRequest.InvalidValue("label"));
        }
        for (int offset = 0; offset < label.length(); ) {
            int codePoint = label.codePointAt(offset);
            if (codePoint <= 0x1f
                    || codePoint >= 0x7f && codePoint <= 0x9f
                    || codePoint == 0x2028
                    || codePoint == 0x2029) {
                throw invalid(
                        "label must be a single line without control characters",
                        new ErrorDetails.InvalidRequest.InvalidValue("label"));
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static boolean isLabelWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || codePoint == 0xfeff;
    }

    private static OperationException invalid(String message, ErrorDetails.InvalidRequest details) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message, details);
    }

    record ValidatedReplace(Cuboid region, int maxChangedBlocks) {}

    record ValidatedSet(SetBlockGeometry geometry, int maxChangedBlocks) {}

    /** Detects overlap in 16-cubed sections without one object per covered block. */
    private static final class SetBlockOccupancy {
        private final Map<SectionPosition, BitSet> sections = new HashMap<>();

        private void add(BlockPosition position, String field) throws OperationException {
            BitSet occupied = section(position.x() >> 4, position.y() >> 4, position.z() >> 4);
            int index =
                    ((position.y() & 15) << 8) | ((position.z() & 15) << 4) | (position.x() & 15);
            if (occupied.get(index)) {
                throw duplicatePosition(field);
            }
            occupied.set(index);
        }

        private void add(Cuboid region, String field) throws OperationException {
            int minSectionX = region.min().x() >> 4;
            int maxSectionX = region.max().x() >> 4;
            int minSectionY = region.min().y() >> 4;
            int maxSectionY = region.max().y() >> 4;
            int minSectionZ = region.min().z() >> 4;
            int maxSectionZ = region.max().z() >> 4;
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                int fromY = sectionY == minSectionY ? region.min().y() & 15 : 0;
                int toY = sectionY == maxSectionY ? region.max().y() & 15 : 15;
                for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                    int fromZ = sectionZ == minSectionZ ? region.min().z() & 15 : 0;
                    int toZ = sectionZ == maxSectionZ ? region.max().z() & 15 : 15;
                    for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                        int fromX = sectionX == minSectionX ? region.min().x() & 15 : 0;
                        int toX = sectionX == maxSectionX ? region.max().x() & 15 : 15;
                        BitSet occupied = section(sectionX, sectionY, sectionZ);
                        for (int localY = fromY; localY <= toY; localY++) {
                            for (int localZ = fromZ; localZ <= toZ; localZ++) {
                                int row = (localY << 8) | (localZ << 4);
                                int fromIndex = row | fromX;
                                int toIndex = (row | toX) + 1;
                                int overlap = occupied.nextSetBit(fromIndex);
                                if (overlap >= 0 && overlap < toIndex) {
                                    throw duplicatePosition(field);
                                }
                                occupied.set(fromIndex, toIndex);
                            }
                        }
                    }
                }
            }
        }

        private BitSet section(int x, int y, int z) {
            return this.sections.computeIfAbsent(
                    new SectionPosition(x, y, z), ignored -> new BitSet());
        }
    }

    private static OperationException duplicatePosition(String field) {
        return invalid(
                field + " resolves to a duplicate block position",
                new ErrorDetails.InvalidRequest.Duplicate(field));
    }

    private static final class SetBounds {
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        private void include(BlockPosition position) {
            this.minX = Math.min(this.minX, position.x());
            this.minY = Math.min(this.minY, position.y());
            this.minZ = Math.min(this.minZ, position.z());
            this.maxX = Math.max(this.maxX, position.x());
            this.maxY = Math.max(this.maxY, position.y());
            this.maxZ = Math.max(this.maxZ, position.z());
        }

        private void include(Cuboid region) {
            include(region.min());
            include(region.max());
        }

        private BlockBounds build() {
            return new BlockBounds(
                    new BlockPosition(this.minX, this.minY, this.minZ),
                    new BlockPosition(this.maxX, this.maxY, this.maxZ));
        }
    }

    private record SectionPosition(int x, int y, int z) {}
}
