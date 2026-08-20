package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
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
    private final int maxBlockStatePatterns;

    public FaweWorldEditor(JavaPlugin plugin, MainThread mainThread, DirtConfig.Limits limits) {
        this(
                new PaperFaweEditPlatform(
                        Objects.requireNonNull(plugin, "plugin"),
                        Objects.requireNonNull(mainThread, "mainThread"),
                        Objects.requireNonNull(limits, "limits").maxChangedBlocks()),
                limits.maxRegionVolume(),
                limits.maxTouchedChunks(),
                limits.undoHistoryPerWorld(),
                limits.maxBlockStatePatterns());
    }

    FaweWorldEditor(
            EditPlatform platform,
            int maxRegionVolume,
            int maxTouchedChunks,
            int undoHistoryPerWorld,
            int maxBlockStatePatterns) {
        this.platform = Objects.requireNonNull(platform, "platform");
        if (maxRegionVolume < 1 || maxTouchedChunks < 1 || maxBlockStatePatterns < 1) {
            throw new IllegalArgumentException("Edit region and chunk limits must be positive");
        }
        this.maxRegionVolume = maxRegionVolume;
        this.maxTouchedChunks = maxTouchedChunks;
        this.maxBlockStatePatterns = maxBlockStatePatterns;
        this.coordinator = new EditCoordinator(undoHistoryPerWorld);
    }

    @Override
    public ReplaceRegionBlocks.Result replaceRegionBlocks(ReplaceRegionBlocks.Request request)
            throws OperationException {
        if (request == null || request.min() == null || request.max() == null) {
            throw invalid("min and max are required");
        }
        validateBlockStateList(request.sourceBlockStatePatterns(), "sourceBlockStatePatterns");
        validatePalette(request.destinationPalette(), "destinationPalette");
        Cuboid region = boundedRegion(request.min(), request.max());
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease = this.coordinator.enter(world.id(), world.name())) {
            EditPlatform.EditResult execution;
            List<String> sourcePatterns;
            List<DestinationPaletteEntry> destinationPalette;
            try (EditPlatform.PreparedReplace prepared =
                    this.platform.prepareReplace(world, request, region)) {
                try {
                    execution = this.platform.replace(prepared, region, request.dryRun());
                } catch (EditRecoveryException failure) {
                    lease.rememberRecovery(failure.recovery());
                    throw failure;
                }
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
        validatePalette(request.destinationPalette(), "destinationPalette");
        Cuboid region = boundedRegion(request.min(), request.max());
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease = this.coordinator.enter(world.id(), world.name())) {
            EditPlatform.EditResult execution;
            List<DestinationPaletteEntry> destinationPalette;
            try (EditPlatform.PreparedFill prepared =
                    this.platform.prepareFill(world, request, region)) {
                try {
                    execution = this.platform.fill(prepared, region, request.dryRun());
                } catch (EditRecoveryException failure) {
                    lease.rememberRecovery(failure.recovery());
                    throw failure;
                }
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
        List<ChunkPosition> chunks = validateSetRequest(request);
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease = this.coordinator.enter(world.id(), world.name())) {
            EditPlatform.EditResult execution;
            int blockCount;
            List<List<DestinationPaletteEntry>> palettes;
            try (EditPlatform.PreparedSet prepared =
                    this.platform.prepareSet(world, request, chunks)) {
                try {
                    execution = this.platform.set(prepared, request.dryRun());
                } catch (EditRecoveryException failure) {
                    lease.rememberRecovery(failure.recovery());
                    throw failure;
                }
                blockCount = prepared.blockCount();
                palettes = prepared.palettes();
                if (!request.dryRun()) {
                    lease.remember(execution.undo());
                }
            }
            if (execution.changedBlockCount() > blockCount) {
                throw new IllegalStateException("Edit backend returned an invalid change count");
            }
            return new SetBlocks.Result(
                    world.name(),
                    palettes,
                    request.seed(),
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
        try (EditCoordinator.Lease lease =
                this.coordinator.enterForUndo(world.id(), world.name())) {
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
        if (!closeIfQuiescent()) {
            throw new IllegalStateException("Cannot close world editing while an edit is active");
        }
    }

    public boolean closeIfQuiescent() {
        beginStopping();
        if (!this.coordinator.isQuiescent()) {
            return false;
        }
        this.platform.close();
        return true;
    }

    private Cuboid boundedRegion(BlockPosition min, BlockPosition max) throws OperationException {
        Cuboid region = RegionGeometry.normalize(min, max, this.maxRegionVolume);
        RegionGeometry.touchedChunks(region, this.maxTouchedChunks);
        return region;
    }

    private List<ChunkPosition> validateSetRequest(SetBlocks.Request request)
            throws OperationException {
        if (request == null || request.origin() == null) {
            throw invalid("origin is required");
        }
        validateSetPalettes(request.palettes());
        if (request.placements() == null || request.placements().isEmpty()) {
            throw invalid("placements must contain at least one entry");
        }

        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        Set<BlockPosition> positions = new LinkedHashSet<>();
        if (request.placements().size() > this.maxRegionVolume) {
            throw new OperationException(
                    OperationFailure.REGION_TOO_LARGE,
                    "Set-blocks edit contains more than the maximum of "
                            + this.maxRegionVolume
                            + " blocks");
        }
        for (int placementIndex = 0;
                placementIndex < request.placements().size();
                placementIndex++) {
            SetBlocks.Placement placement = request.placements().get(placementIndex);
            String placementName = "placements[" + placementIndex + "]";
            if (placement == null) {
                throw invalid(placementName + " is required");
            }
            if (placement.paletteIndex() < 0
                    || placement.paletteIndex() >= request.palettes().size()) {
                throw invalid(placementName + "[0] must reference an entry in palettes");
            }
            BlockPosition position = resolvePosition(request.origin(), placement, placementName);
            if (!positions.add(position)) {
                throw invalid(placementName + " resolves to a duplicate block position");
            }
            chunks.add(ChunkPosition.containing(position.x(), position.z()));
            if (chunks.size() > this.maxTouchedChunks) {
                throw new OperationException(
                        OperationFailure.REGION_TOO_LARGE,
                        "Operation touches more than the maximum of "
                                + this.maxTouchedChunks
                                + " chunks");
            }
        }
        return List.copyOf(chunks);
    }

    private void validateSetPalettes(List<List<DestinationPaletteEntry>> palettes)
            throws OperationException {
        if (palettes == null || palettes.isEmpty()) {
            throw invalid("palettes must contain at least one palette");
        }
        int entryCount = 0;
        for (int index = 0; index < palettes.size(); index++) {
            List<DestinationPaletteEntry> palette = palettes.get(index);
            validatePalette(palette, "palettes[" + index + "]");
            entryCount += palette.size();
            if (entryCount > this.maxBlockStatePatterns) {
                throw invalid(
                        "palettes may contain at most "
                                + this.maxBlockStatePatterns
                                + " entries in total");
            }
        }
    }

    private static BlockPosition resolvePosition(
            BlockPosition origin, SetBlocks.Placement placement, String field)
            throws OperationException {
        try {
            return new BlockPosition(
                    Math.addExact(origin.x(), placement.x()),
                    Math.addExact(origin.y(), placement.y()),
                    Math.addExact(origin.z(), placement.z()));
        } catch (ArithmeticException exception) {
            throw invalid(field + " resolves outside the signed 32-bit coordinate range");
        }
    }

    private EditPlatform.WorldHandle resolveWorld(String worldName) throws OperationException {
        if (worldName == null || worldName.isBlank()) {
            throw invalid("world must be a non-empty string");
        }
        return this.platform.resolveWorld(worldName);
    }

    private void validatePalette(List<DestinationPaletteEntry> palette, String field)
            throws OperationException {
        if (palette == null || palette.isEmpty()) {
            throw invalid(field + " must contain at least one entry");
        }
        if (palette.size() > this.maxBlockStatePatterns) {
            throw invalid(
                    field + " may contain at most " + this.maxBlockStatePatterns + " entries");
        }
        boolean weighted = false;
        boolean unweighted = false;
        int totalWeight = 0;
        for (int index = 0; index < palette.size(); index++) {
            DestinationPaletteEntry entry = palette.get(index);
            if (entry == null || entry.blockState() == null || entry.blockState().isBlank()) {
                throw invalid(field + "[" + index + "].blockState must be a non-empty string");
            }
            Integer weight = entry.weight();
            if (weight == null) {
                unweighted = true;
            } else {
                if (weight < 1 || weight > 100) {
                    throw invalid(field + "[" + index + "].weight must be between 1 and 100");
                }
                weighted = true;
                totalWeight += weight;
            }
        }
        if (weighted && unweighted) {
            throw invalid(
                    field
                            + " weights must be provided for every entry or omitted from every entry");
        }
        if (weighted && totalWeight != 100) {
            throw invalid(field + " weights must total 100");
        }
    }

    private void validateBlockStateList(List<String> values, String field)
            throws OperationException {
        if (values == null || values.isEmpty()) {
            throw invalid(field + " must contain at least one entry");
        }
        if (values.size() > this.maxBlockStatePatterns) {
            throw invalid(
                    field + " may contain at most " + this.maxBlockStatePatterns + " entries");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value == null || value.isBlank()) {
                throw invalid(field + "[" + index + "] must be a non-empty string");
            }
            if (!distinct.add(value)) {
                throw invalid(field + " contains a duplicate pattern: " + value);
            }
        }
    }

    private static OperationException invalid(String message) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message);
    }
}
