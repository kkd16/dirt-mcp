package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.BlockListResult;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.BlockRunsResult;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionSnapshotSource.CapturedRegion;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView.Viewport;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class RegionInspectionService
        implements CountRegionBlockStates, GetRegionBlocks, ScanOrthographicView {
    private final RegionSnapshotSource snapshots;
    private final long maxRegionVolume;
    private final long maxInspectionVolume;
    private final int maxInspectionResults;
    private final long maxTouchedChunks;

    public RegionInspectionService(
            RegionSnapshotSource snapshots,
            long maxRegionVolume,
            long maxInspectionVolume,
            int maxInspectionResults,
            long maxTouchedChunks) {
        if (maxRegionVolume < 1
                || maxInspectionVolume < 1
                || maxInspectionResults < 1
                || maxTouchedChunks < 1) {
            throw new IllegalArgumentException("Inspection limits must be positive");
        }
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.maxRegionVolume = maxRegionVolume;
        this.maxInspectionVolume = maxInspectionVolume;
        this.maxInspectionResults = maxInspectionResults;
        this.maxTouchedChunks = maxTouchedChunks;
    }

    @Override
    public CountRegionBlockStates.Result countRegionBlockStates(
            CountRegionBlockStates.Request request) throws OperationException {
        Objects.requireNonNull(request, "request");
        Cuboid region =
                RegionGeometry.normalize(request.min(), request.max(), this.maxRegionVolume);
        enforceChunkLimit(region);
        CapturedRegion capture =
                this.snapshots.capture(request.world(), region, List.of(), List.of());
        Map<String, Long> counts = RegionBlockAlgorithms.countBlockStates(region, capture);
        return new CountRegionBlockStates.Result(
                capture.worldName(), region.bounds(), region.dimensions(), region.volume(), counts);
    }

    @Override
    public GetRegionBlocks.Result getRegionBlocks(GetRegionBlocks.Request request)
            throws OperationException {
        Objects.requireNonNull(request, "request");
        validateMaxResults(request.maxResults());
        long maximumVolume = Math.min(this.maxRegionVolume, this.maxInspectionVolume);
        Cuboid region = RegionGeometry.normalize(request.min(), request.max(), maximumVolume);
        enforceChunkLimit(region);
        CapturedRegion capture =
                this.snapshots.capture(
                        request.world(),
                        region,
                        request.includeBlockStatePatterns(),
                        request.excludeBlockStatePatterns());
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
    }

    @Override
    public ScanOrthographicView.Result scanOrthographicView(ScanOrthographicView.Request request)
            throws OperationException {
        Objects.requireNonNull(request, "request");
        validateMaxResults(request.maxResults());
        long maximumVolume = Math.min(this.maxRegionVolume, this.maxInspectionVolume);
        OrthographicViewAlgorithms.ViewGeometry geometry =
                OrthographicViewAlgorithms.geometry(request, maximumVolume);
        enforceChunkLimit(geometry.region());
        CapturedRegion capture =
                this.snapshots.capture(request.world(), geometry.region(), List.of(), List.of());
        List<ScanOrthographicView.ViewBlock> blocks =
                OrthographicViewAlgorithms.collectVisibleBlocks(request, geometry, capture);
        return new ScanOrthographicView.Result(
                capture.worldName(),
                request.origin(),
                request.direction().name().toLowerCase(Locale.ROOT),
                "blocks",
                geometry.basis(),
                new Viewport(
                        request.horizontalRadius(),
                        request.verticalRadius(),
                        request.maxDistance()),
                geometry.region().bounds(),
                geometry.scannedVolume(),
                blocks.size(),
                blocks);
    }

    private void validateMaxResults(int maxResults) throws OperationException {
        if (maxResults < 1 || maxResults > this.maxInspectionResults) {
            throw new OperationException(
                    OperationFailure.INVALID_REQUEST,
                    "maxResults must be between 1 and " + this.maxInspectionResults);
        }
    }

    private void enforceChunkLimit(Cuboid region) throws OperationException {
        RegionGeometry.touchedChunks(region, this.maxTouchedChunks);
    }
}
