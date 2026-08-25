package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionBlockAlgorithms.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RegionInspectionService
        implements CountRegionBlockStates, GetBlocks, ScanOrthographicView {
    private final RegionSnapshotSource snapshots;
    private final int maxRegionVolume;
    private final int maxInspectionVolume;
    private final int maxInspectionResults;
    private final int maxTouchedChunks;
    private final int maxBlockStatePatterns;
    private final int maxPaletteEntries;
    private final InspectionAdmission admission;

    public RegionInspectionService(
            RegionSnapshotSource snapshots,
            int maxRegionVolume,
            int maxInspectionVolume,
            int maxInspectionResults,
            int maxTouchedChunks,
            int maxBlockStatePatterns,
            int maxPaletteEntries,
            InspectionAdmission admission) {
        if (maxRegionVolume < 1
                || maxInspectionVolume < 1
                || maxInspectionVolume > maxRegionVolume
                || maxInspectionResults < 1
                || maxTouchedChunks < 1
                || maxBlockStatePatterns < 1
                || maxPaletteEntries < 1) {
            throw new IllegalArgumentException("Inspection limits are invalid");
        }
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.maxRegionVolume = maxRegionVolume;
        this.maxInspectionVolume = maxInspectionVolume;
        this.maxInspectionResults = maxInspectionResults;
        this.maxTouchedChunks = maxTouchedChunks;
        this.maxBlockStatePatterns = maxBlockStatePatterns;
        this.maxPaletteEntries = maxPaletteEntries;
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    @Override
    public CountRegionBlockStates.Result countRegionBlockStates(
            CountRegionBlockStates.Request request) throws OperationException {
        if (request == null || request.min() == null || request.max() == null) {
            throw invalid(
                    "world, min, and max are required",
                    new ErrorDetails.InvalidRequest.Missing(missingRegionField(request)));
        }
        validateWorld(request.world());
        return this.admission.execute(
                () -> {
                    Cuboid region =
                            RegionGeometry.normalize(
                                    request.min(), request.max(), this.maxRegionVolume);
                    enforceChunkLimit(region);
                    CapturedRegion capture =
                            this.snapshots.capture(request.world(), region, List.of(), List.of());
                    Map<String, Long> counts =
                            RegionBlockAlgorithms.countBlockStates(region, capture);
                    return new CountRegionBlockStates.Result(
                            capture.worldName(),
                            region.bounds(),
                            region.dimensions(),
                            region.volume(),
                            counts);
                });
    }

    @Override
    public ExactBlockStructure getBlocks(GetBlocks.Request request) throws OperationException {
        validateGetBlocksRequest(request);
        int effectiveMaxResults = effectiveMaxResults(request.maxResults());
        if ((long) request.includeBlockStatePatterns().size()
                        + request.excludeBlockStatePatterns().size()
                > this.maxBlockStatePatterns) {
            throw invalid(
                    "includeBlockStatePatterns and excludeBlockStatePatterns may contain at most "
                            + this.maxBlockStatePatterns
                            + " entries combined",
                    new ErrorDetails.InvalidRequest.TooManyItems(
                            List.of("includeBlockStatePatterns", "excludeBlockStatePatterns"),
                            this.maxBlockStatePatterns));
        }
        return this.admission.execute(
                () -> {
                    Cuboid region =
                            RegionGeometry.normalize(
                                    request.min(), request.max(), this.maxInspectionVolume);
                    enforceChunkLimit(region);
                    CapturedRegion capture =
                            this.snapshots.capture(
                                    request.world(),
                                    region,
                                    request.includeBlockStatePatterns(),
                                    request.excludeBlockStatePatterns());
                    List<InspectedBlock> blocks =
                            RegionBlockAlgorithms.collectBlocks(
                                    region, capture, request.includeAir());
                    return packStructure(
                            capture.worldName(), region.min(), blocks, effectiveMaxResults);
                });
    }

    @Override
    public ExactBlockStructure scanOrthographicView(ScanOrthographicView.Request request)
            throws OperationException {
        if (request == null || request.origin() == null || request.direction() == null) {
            String field =
                    request == null ? "request" : request.origin() == null ? "origin" : "direction";
            throw invalid(
                    "world, origin, and direction are required",
                    new ErrorDetails.InvalidRequest.Missing(field));
        }
        validateWorld(request.world());
        int effectiveMaxResults = effectiveMaxResults(request.maxResults());
        return this.admission.execute(
                () -> {
                    OrthographicViewAlgorithms.ViewGeometry geometry =
                            OrthographicViewAlgorithms.geometry(request, this.maxInspectionVolume);
                    enforceChunkLimit(geometry.region());
                    CapturedRegion capture =
                            this.snapshots.capture(
                                    request.world(), geometry.region(), List.of(), List.of());
                    List<InspectedBlock> blocks =
                            OrthographicViewAlgorithms.collectBlocks(request, geometry, capture);
                    return packStructure(
                            capture.worldName(),
                            geometry.region().min(),
                            blocks,
                            effectiveMaxResults);
                });
    }

    private ExactBlockStructure packStructure(
            String world, BlockPosition origin, List<InspectedBlock> blocks, int maxResults)
            throws OperationException {
        RegionBlockAlgorithms.PackedBlocks packed =
                RegionBlockAlgorithms.packBlocks(
                        origin, blocks, maxResults, this.maxPaletteEntries);
        return new ExactBlockStructure(
                world, origin, packed.palettes(), packed.placements(), packed.runs());
    }

    private int effectiveMaxResults(int requested) throws OperationException {
        if (requested < 1) {
            throw new OperationException(
                    OperationFailure.INVALID_REQUEST,
                    "maxResults must be positive",
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            "maxResults", requested, 1, Integer.MAX_VALUE));
        }
        return Math.min(requested, this.maxInspectionResults);
    }

    private void enforceChunkLimit(Cuboid region) throws OperationException {
        RegionGeometry.touchedChunks(region, this.maxTouchedChunks);
    }

    private void validateGetBlocksRequest(GetBlocks.Request request) throws OperationException {
        if (request == null) {
            throw invalid(
                    "request is required", new ErrorDetails.InvalidRequest.Missing("request"));
        }
        validateWorld(request.world());
        validatePatterns(request.includeBlockStatePatterns(), "includeBlockStatePatterns");
        validatePatterns(request.excludeBlockStatePatterns(), "excludeBlockStatePatterns");
    }

    private static void validateWorld(String world) throws OperationException {
        if (world == null || world.isBlank()) {
            throw invalid(
                    "world must be a non-empty string",
                    new ErrorDetails.InvalidRequest.InvalidValue("world"));
        }
    }

    private static void validatePatterns(List<String> patterns, String field)
            throws OperationException {
        Set<String> distinct = new HashSet<>();
        for (int index = 0; index < patterns.size(); index++) {
            String pattern = patterns.get(index);
            if (pattern.isBlank()) {
                String item = field + "[" + index + "]";
                throw invalid(
                        item + " must be a non-empty string",
                        new ErrorDetails.InvalidRequest.InvalidValue(item));
            }
            if (!distinct.add(pattern)) {
                String item = field + "[" + index + "]";
                throw invalid(
                        field + " must not contain duplicate patterns",
                        new ErrorDetails.InvalidRequest.Duplicate(item));
            }
        }
    }

    private static String missingRegionField(CountRegionBlockStates.Request request) {
        if (request == null) {
            return "request";
        }
        return request.min() == null ? "min" : "max";
    }

    private static OperationException invalid(String message, ErrorDetails.InvalidRequest details) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message, details);
    }
}
