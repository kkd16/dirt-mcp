package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;

public final class FaweWorldEditor
        implements ReplaceRegionBlocks, FillRegion, SetBlocks, UndoLastEdit, AutoCloseable {
    private final EditPlatform platform;
    private final EditCoordinator coordinator;
    private final int maxRegionVolume;
    private final int maxTouchedChunks;

    public FaweWorldEditor(JavaPlugin plugin, MainThread mainThread, DirtConfig.Limits limits) {
        this(
                new PaperFaweEditPlatform(
                        Objects.requireNonNull(plugin, "plugin"),
                        Objects.requireNonNull(mainThread, "mainThread"),
                        Objects.requireNonNull(limits, "limits").maxChangedBlocks()),
                limits.maxRegionVolume(),
                limits.maxTouchedChunks(),
                limits.undoHistoryPerWorld());
    }

    FaweWorldEditor(
            EditPlatform platform,
            int maxRegionVolume,
            int maxTouchedChunks,
            int undoHistoryPerWorld) {
        this.platform = Objects.requireNonNull(platform, "platform");
        if (maxRegionVolume < 1 || maxTouchedChunks < 1) {
            throw new IllegalArgumentException("Edit region and chunk limits must be positive");
        }
        this.maxRegionVolume = maxRegionVolume;
        this.maxTouchedChunks = maxTouchedChunks;
        this.coordinator = new EditCoordinator(undoHistoryPerWorld);
    }

    @Override
    public ReplaceRegionBlocks.Result replaceRegionBlocks(ReplaceRegionBlocks.Request request)
            throws OperationException {
        if (request == null || request.min() == null || request.max() == null) {
            throw invalid("min and max are required");
        }
        Cuboid region = boundedRegion(request.min(), request.max());
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease = this.coordinator.enter(world.id(), world.name())) {
            EditPlatform.EditResult execution;
            List<String> sourcePatterns;
            List<DestinationPaletteEntry> destinationPalette;
            try (EditPlatform.PreparedReplace prepared =
                    this.platform.prepareReplace(world, request, region)) {
                execution = this.platform.replace(prepared, region, request.dryRun());
                sourcePatterns = prepared.sourcePatterns();
                destinationPalette = prepared.destinationPalette();
                if (!request.dryRun()) {
                    lease.remember(execution.undo());
                }
            }
            return new ReplaceRegionBlocks.Result(
                    world.name(),
                    region.bounds(),
                    sourcePatterns,
                    destinationPalette,
                    request.seed(),
                    request.dryRun(),
                    execution.matchedBlockCount(),
                    execution.changedBlockCount());
        }
    }

    @Override
    public FillRegion.Result fillRegion(FillRegion.Request request) throws OperationException {
        if (request == null || request.min() == null || request.max() == null) {
            throw invalid("min and max are required");
        }
        Cuboid region = boundedRegion(request.min(), request.max());
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease = this.coordinator.enter(world.id(), world.name())) {
            EditPlatform.EditResult execution;
            List<DestinationPaletteEntry> destinationPalette;
            try (EditPlatform.PreparedFill prepared =
                    this.platform.prepareFill(world, request, region)) {
                execution = this.platform.fill(prepared, region, request.dryRun());
                destinationPalette = prepared.destinationPalette();
                if (!request.dryRun()) {
                    lease.remember(execution.undo());
                }
            }
            return new FillRegion.Result(
                    world.name(),
                    region.bounds(),
                    destinationPalette,
                    request.seed(),
                    request.dryRun(),
                    region.volume(),
                    execution.changedBlockCount());
        }
    }

    @Override
    public SetBlocks.Result setBlocks(SetBlocks.Request request) throws OperationException {
        List<ChunkPosition> chunks = validateSparseRequest(request);
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease = this.coordinator.enter(world.id(), world.name())) {
            EditPlatform.EditResult execution;
            int blockCount;
            try (EditPlatform.PreparedSet prepared =
                    this.platform.prepareSet(world, request, chunks)) {
                execution = this.platform.set(prepared, request.dryRun());
                blockCount = prepared.blockCount();
                if (!request.dryRun()) {
                    lease.remember(execution.undo());
                }
            }
            if (execution.changedBlockCount() > blockCount) {
                throw new IllegalStateException("Edit backend returned an invalid change count");
            }
            return new SetBlocks.Result(
                    world.name(),
                    request.dryRun(),
                    blockCount,
                    execution.changedBlockCount(),
                    blockCount - execution.changedBlockCount());
        }
    }

    @Override
    public UndoLastEdit.Result undoLastEdit(UndoLastEdit.Request request)
            throws OperationException {
        if (request == null) {
            throw invalid("request is required");
        }
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease = this.coordinator.enter(world.id(), world.name())) {
            EditPlatform.UndoToken undo = lease.latestUndo();
            if (undo == null) {
                throw new OperationException(
                        OperationFailure.NOTHING_TO_UNDO, "No Dirt MCP edit is available to undo");
            }
            this.platform.undo(world, undo);
            lease.removeLatest(undo);
            return new UndoLastEdit.Result(world.name(), undo.changedBlockCount());
        }
    }

    public void invalidateWorld(UUID worldId) {
        this.coordinator.invalidate(Objects.requireNonNull(worldId, "worldId"));
    }

    public void beginStopping() {
        this.coordinator.close();
        this.platform.beginStopping();
    }

    @Override
    public void close() {
        beginStopping();
        this.platform.close();
    }

    private Cuboid boundedRegion(BlockPosition min, BlockPosition max) throws OperationException {
        Cuboid region = RegionGeometry.normalize(min, max, this.maxRegionVolume);
        RegionGeometry.touchedChunks(region, this.maxTouchedChunks);
        return region;
    }

    private List<ChunkPosition> validateSparseRequest(SetBlocks.Request request)
            throws OperationException {
        if (request == null || request.changes() == null || request.changes().isEmpty()) {
            throw invalid("changes must contain at least one block change");
        }
        if (request.changes().size() > this.maxRegionVolume) {
            throw new OperationException(
                    OperationFailure.REGION_TOO_LARGE,
                    "Sparse edit contains "
                            + request.changes().size()
                            + " blocks; maximum is "
                            + this.maxRegionVolume);
        }

        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        for (int index = 0; index < request.changes().size(); index++) {
            BlockChange change = request.changes().get(index);
            if (change == null || change.position() == null) {
                throw invalid("changes[" + index + "] must contain a position and blockState");
            }
            BlockPosition position = change.position();
            chunks.add(ChunkPosition.containing(position.x(), position.z()));
            if (chunks.size() > this.maxTouchedChunks) {
                throw new OperationException(
                        OperationFailure.REGION_TOO_LARGE,
                        "Operation touches more than the maximum of "
                                + this.maxTouchedChunks
                                + " chunks");
            }
        }
        return List.copyOf(new ArrayList<>(chunks));
    }

    private EditPlatform.WorldHandle resolveWorld(String worldName) throws OperationException {
        if (worldName == null || worldName.isBlank()) {
            throw invalid("world must be a non-empty string");
        }
        return this.platform.resolveWorld(worldName);
    }

    private static OperationException invalid(String message) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message);
    }
}
