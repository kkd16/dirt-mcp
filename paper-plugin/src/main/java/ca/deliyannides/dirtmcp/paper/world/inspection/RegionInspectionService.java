package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.BlockListResult;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.BlockRunsResult;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.Viewport;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

public final class RegionInspectionService
        implements CountRegionBlockStates, GetRegionBlocks, ScanOrthographicView {
    private final RegionSnapshotSource snapshots;
    private final int maxRegionVolume;
    private final int maxInspectionVolume;
    private final int maxInspectionResults;
    private final int maxTouchedChunks;
    private final int maxBlockStatePatterns;
    private final Semaphore admissions;
    private final int maximumConcurrentInspections;

    public RegionInspectionService(
            RegionSnapshotSource snapshots,
            int maxRegionVolume,
            int maxInspectionVolume,
            int maxInspectionResults,
            int maxTouchedChunks,
            int maxBlockStatePatterns,
            int maxConcurrentInspections) {
        if (maxRegionVolume < 1
                || maxInspectionVolume < 1
                || maxInspectionVolume > maxRegionVolume
                || maxInspectionResults < 1
                || maxTouchedChunks < 1
                || maxBlockStatePatterns < 1
                || maxConcurrentInspections < 1) {
            throw new IllegalArgumentException("Inspection limits are invalid");
        }
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.maxRegionVolume = maxRegionVolume;
        this.maxInspectionVolume = maxInspectionVolume;
        this.maxInspectionResults = maxInspectionResults;
        this.maxTouchedChunks = maxTouchedChunks;
        this.maxBlockStatePatterns = maxBlockStatePatterns;
        this.admissions = new Semaphore(maxConcurrentInspections);
        this.maximumConcurrentInspections = maxConcurrentInspections;
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
        return admitted(
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
    public GetRegionBlocks.Result getRegionBlocks(GetRegionBlocks.Request request)
            throws OperationException {
        validateDetailedRequest(request);
        validateMaxResults(request.maxResults());
        List<String> includes = canonicalPatterns(request.includeBlockStatePatterns());
        List<String> excludes = canonicalPatterns(request.excludeBlockStatePatterns());
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
        return admitted(
                () -> {
                    Cuboid region =
                            RegionGeometry.normalize(
                                    request.min(), request.max(), this.maxInspectionVolume);
                    enforceChunkLimit(region);
                    CapturedRegion capture =
                            this.snapshots.capture(request.world(), region, includes, excludes);
                    List<InspectedBlock> blocks =
                            RegionBlockAlgorithms.collectBlocks(
                                    region,
                                    capture,
                                    request.includeAir(),
                                    request.maxResults(),
                                    request.format());
                    if (request.format() == GetRegionBlocks.Format.BLOCKS) {
                        return new BlockListResult(
                                capture.worldName(),
                                region.bounds(),
                                region.volume(),
                                blocks.size(),
                                "blocks",
                                blocks);
                    }

                    return new BlockRunsResult(
                            capture.worldName(),
                            region.bounds(),
                            region.volume(),
                            blocks.size(),
                            "runs",
                            RegionBlockAlgorithms.groupSortedRuns(blocks, request.maxResults()));
                });
    }

    @Override
    public ScanOrthographicView.Result scanOrthographicView(ScanOrthographicView.Request request)
            throws OperationException {
        if (request == null || request.origin() == null || request.direction() == null) {
            String field =
                    request == null ? "request" : request.origin() == null ? "origin" : "direction";
            throw invalid(
                    "world, origin, and direction are required",
                    new ErrorDetails.InvalidRequest.Missing(field));
        }
        validateWorld(request.world());
        validateMaxResults(request.maxResults());
        return admitted(
                () -> {
                    OrthographicViewAlgorithms.ViewGeometry geometry =
                            OrthographicViewAlgorithms.geometry(request, this.maxInspectionVolume);
                    enforceChunkLimit(geometry.region());
                    CapturedRegion capture =
                            this.snapshots.capture(
                                    request.world(), geometry.region(), List.of(), List.of());
                    List<ScanOrthographicView.ViewBlock> blocks =
                            OrthographicViewAlgorithms.collectVisibleBlocks(
                                    request, geometry, capture);
                    return new ScanOrthographicView.Result(
                            capture.worldName(),
                            request.origin(),
                            request.direction().name().toLowerCase(Locale.ROOT),
                            "blocks",
                            geometry.basis(),
                            new Viewport(
                                    request.horizontalRadius(),
                                    request.verticalRadius(),
                                    request.maxDistance(),
                                    request.depth()),
                            geometry.region().bounds(),
                            geometry.region().volume(),
                            blocks.size(),
                            blocks);
                });
    }

    private void validateMaxResults(int maxResults) throws OperationException {
        if (maxResults < 1 || maxResults > this.maxInspectionResults) {
            throw new OperationException(
                    OperationFailure.INVALID_REQUEST,
                    "maxResults must be between 1 and " + this.maxInspectionResults,
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            "maxResults", maxResults, 1, this.maxInspectionResults));
        }
    }

    private void enforceChunkLimit(Cuboid region) throws OperationException {
        RegionGeometry.touchedChunks(region, this.maxTouchedChunks);
    }

    private <T> T admitted(Inspection<T> inspection) throws OperationException {
        if (!this.admissions.tryAcquire()) {
            throw new OperationException(
                    OperationFailure.SERVER_UNAVAILABLE,
                    "The server is handling too many region inspections",
                    new ErrorDetails.ServerUnavailable.InspectionBusy(
                            this.maximumConcurrentInspections));
        }
        try {
            return inspection.run();
        } finally {
            this.admissions.release();
        }
    }

    private void validateDetailedRequest(GetRegionBlocks.Request request)
            throws OperationException {
        if (request == null
                || request.min() == null
                || request.max() == null
                || request.includeBlockStatePatterns() == null
                || request.excludeBlockStatePatterns() == null
                || request.format() == null) {
            String field =
                    request == null
                            ? "request"
                            : request.min() == null
                                    ? "min"
                                    : request.max() == null
                                            ? "max"
                                            : request.includeBlockStatePatterns() == null
                                                    ? "includeBlockStatePatterns"
                                                    : request.excludeBlockStatePatterns() == null
                                                            ? "excludeBlockStatePatterns"
                                                            : "format";
            throw invalid(
                    "world, min, max, pattern lists, and format are required",
                    new ErrorDetails.InvalidRequest.Missing(field));
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
        for (int index = 0; index < patterns.size(); index++) {
            String pattern = patterns.get(index);
            if (pattern == null || pattern.isBlank()) {
                String item = field + "[" + index + "]";
                throw invalid(
                        item + " must be a non-empty string",
                        new ErrorDetails.InvalidRequest.InvalidValue(item));
            }
        }
    }

    private static List<String> canonicalPatterns(List<String> patterns) {
        return List.copyOf(new LinkedHashSet<>(patterns));
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

    @FunctionalInterface
    private interface Inspection<T> {
        T run() throws OperationException;
    }
}
