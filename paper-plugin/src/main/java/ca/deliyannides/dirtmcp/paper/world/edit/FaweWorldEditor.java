package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;

public final class FaweWorldEditor
        implements ReplaceRegionBlocks,
                FillRegion,
                SetBlocks,
                GetEditHistory,
                UndoEdit,
                AutoCloseable {
    private final EditPlatform platform;
    private final EditCoordinator coordinator;
    private final int maxRegionVolume;
    private final int maxTouchedChunks;
    private final int maxBlockStatePatterns;
    private final int maxChangedBlocks;

    public FaweWorldEditor(
            JavaPlugin plugin,
            MainThread mainThread,
            DirtConfig.Limits limits,
            DirtConfig.EditHistory history,
            DirtLog log) {
        this(
                new PaperFaweEditPlatform(
                        Objects.requireNonNull(plugin, "plugin"),
                        Objects.requireNonNull(mainThread, "mainThread"),
                        Objects.requireNonNull(limits, "limits").maxChangedBlocks(),
                        Objects.requireNonNull(log, "log")),
                limits.maxRegionVolume(),
                limits.maxTouchedChunks(),
                limits.maxBlockStatePatterns(),
                limits.maxChangedBlocks(),
                Objects.requireNonNull(history, "history"));
    }

    FaweWorldEditor(
            EditPlatform platform,
            int maxRegionVolume,
            int maxTouchedChunks,
            int maxBlockStatePatterns,
            int maxChangedBlocks,
            DirtConfig.EditHistory history) {
        this.platform = Objects.requireNonNull(platform, "platform");
        if (maxRegionVolume < 1
                || maxTouchedChunks < 1
                || maxBlockStatePatterns < 1
                || maxChangedBlocks < 1) {
            throw new IllegalArgumentException("Edit and chunk limits must be positive");
        }
        this.maxRegionVolume = maxRegionVolume;
        this.maxTouchedChunks = maxTouchedChunks;
        this.maxBlockStatePatterns = maxBlockStatePatterns;
        this.maxChangedBlocks = maxChangedBlocks;
        DirtConfig.EditHistory checkedHistory = Objects.requireNonNull(history, "history");
        if (maxChangedBlocks > checkedHistory.maxRetainedChangedBlocks()) {
            throw new IllegalArgumentException(
                    "Maximum changed blocks must fit the edit-history block budget");
        }
        this.coordinator =
                new EditCoordinator(
                        checkedHistory.maxEntriesPerWorld(),
                        checkedHistory.maxEntriesTotal(),
                        checkedHistory.maxRetainedChangedBlocks());
    }

    @Override
    public ReplaceRegionBlocks.Result replaceRegionBlocks(
            ReplaceRegionBlocks.Request request, UUID callId) throws OperationException {
        requireCallId(callId);
        if (request == null || request.min() == null || request.max() == null) {
            throw invalid("min and max are required");
        }
        validateBlockStateList(request.sourceBlockStatePatterns(), "sourceBlockStatePatterns");
        validatePalette(request.destinationPalette(), "destinationPalette");
        Cuboid region = boundedRegion(request.min(), request.max());
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        UUID editId = pendingEditId(request.dryRun());
        try (EditCoordinator.Lease lease =
                this.coordinator.enterMutation(world.id(), world.name())) {
            EditPlatform.EditResult execution;
            EditRecord edit = null;
            List<String> sourcePatterns;
            List<DestinationPaletteEntry> destinationPalette;
            try (EditPlatform.PreparedReplace prepared =
                    this.platform.prepareReplace(world, request, region)) {
                sourcePatterns = List.copyOf(prepared.sourcePatterns());
                destinationPalette = List.copyOf(prepared.destinationPalette());
                try {
                    execution =
                            this.platform.replace(
                                    prepared,
                                    region,
                                    request.dryRun(),
                                    () ->
                                            lease.reserveHistory(
                                                    Math.min(
                                                            (long) this.maxChangedBlocks,
                                                            region.volume())));
                } catch (EditRecoveryException failure) {
                    retainOrRollbackRecovery(
                            lease,
                            prepared,
                            world,
                            region.bounds(),
                            callId,
                            editId,
                            EditOperation.REPLACE_REGION_BLOCKS,
                            failure);
                    throw recoveryFailure(failure, editId);
                }
                edit =
                        retainCommitted(
                                lease,
                                prepared,
                                world,
                                region.bounds(),
                                callId,
                                editId,
                                EditOperation.REPLACE_REGION_BLOCKS,
                                request.dryRun(),
                                region.volume(),
                                execution);
                if (execution.changedBlockCount() > execution.matchedBlockCount()) {
                    throw new IllegalStateException(
                            "Replace changes cannot exceed its matched block count");
                }
            } catch (OperationException failure) {
                throw retainedFailure(failure, edit);
            } catch (RuntimeException failure) {
                if (edit == null) {
                    throw failure;
                }
                throw retainedFailure(failure, edit);
            }
            return new ReplaceRegionBlocks.Result(
                    world.name(),
                    region.bounds(),
                    sourcePatterns,
                    destinationPalette,
                    request.seed(),
                    outcome(request.dryRun(), execution.changedBlockCount()),
                    execution.matchedBlockCount(),
                    execution.changedBlockCount(),
                    edit);
        }
    }

    @Override
    public FillRegion.Result fillRegion(FillRegion.Request request, UUID callId)
            throws OperationException {
        requireCallId(callId);
        if (request == null || request.min() == null || request.max() == null) {
            throw invalid("min and max are required");
        }
        validatePalette(request.destinationPalette(), "destinationPalette");
        Cuboid region = boundedRegion(request.min(), request.max());
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        UUID editId = pendingEditId(request.dryRun());
        try (EditCoordinator.Lease lease =
                this.coordinator.enterMutation(world.id(), world.name())) {
            EditPlatform.EditResult execution;
            EditRecord edit = null;
            List<DestinationPaletteEntry> destinationPalette;
            try (EditPlatform.PreparedFill prepared =
                    this.platform.prepareFill(world, request, region)) {
                destinationPalette = List.copyOf(prepared.destinationPalette());
                try {
                    execution =
                            this.platform.fill(
                                    prepared,
                                    region,
                                    request.dryRun(),
                                    () ->
                                            lease.reserveHistory(
                                                    Math.min(
                                                            (long) this.maxChangedBlocks,
                                                            region.volume())));
                } catch (EditRecoveryException failure) {
                    retainOrRollbackRecovery(
                            lease,
                            prepared,
                            world,
                            region.bounds(),
                            callId,
                            editId,
                            EditOperation.FILL_REGION,
                            failure);
                    throw recoveryFailure(failure, editId);
                }
                edit =
                        retainCommitted(
                                lease,
                                prepared,
                                world,
                                region.bounds(),
                                callId,
                                editId,
                                EditOperation.FILL_REGION,
                                request.dryRun(),
                                region.volume(),
                                execution);
            } catch (OperationException failure) {
                throw retainedFailure(failure, edit);
            } catch (RuntimeException failure) {
                if (edit == null) {
                    throw failure;
                }
                throw retainedFailure(failure, edit);
            }
            return new FillRegion.Result(
                    world.name(),
                    region.bounds(),
                    destinationPalette,
                    request.seed(),
                    outcome(request.dryRun(), execution.changedBlockCount()),
                    region.volume(),
                    execution.changedBlockCount(),
                    edit);
        }
    }

    @Override
    public SetBlocks.Result setBlocks(SetBlocks.Request request, UUID callId)
            throws OperationException {
        requireCallId(callId);
        SetRequestGeometry geometry = validateSetRequest(request);
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        UUID editId = pendingEditId(request.dryRun());
        try (EditCoordinator.Lease lease =
                this.coordinator.enterMutation(world.id(), world.name())) {
            EditPlatform.EditResult execution;
            EditRecord edit = null;
            int blockCount;
            List<List<DestinationPaletteEntry>> palettes;
            try (EditPlatform.PreparedSet prepared =
                    this.platform.prepareSet(
                            world,
                            request,
                            geometry.positions(),
                            geometry.bounds(),
                            geometry.chunks())) {
                blockCount = prepared.blockCount();
                if (blockCount != request.placements().size()) {
                    throw new IllegalStateException(
                            "Prepared set-blocks count does not match its validated request");
                }
                palettes = immutablePalettes(prepared.palettes());
                try {
                    execution =
                            this.platform.set(
                                    prepared,
                                    request.dryRun(),
                                    () ->
                                            lease.reserveHistory(
                                                    Math.min(
                                                            (long) this.maxChangedBlocks,
                                                            request.placements().size())));
                } catch (EditRecoveryException failure) {
                    retainOrRollbackRecovery(
                            lease,
                            prepared,
                            world,
                            geometry.bounds(),
                            callId,
                            editId,
                            EditOperation.SET_BLOCKS,
                            failure);
                    throw recoveryFailure(failure, editId);
                }
                edit =
                        retainCommitted(
                                lease,
                                prepared,
                                world,
                                geometry.bounds(),
                                callId,
                                editId,
                                EditOperation.SET_BLOCKS,
                                request.dryRun(),
                                blockCount,
                                execution);
            } catch (OperationException failure) {
                throw retainedFailure(failure, edit);
            } catch (RuntimeException failure) {
                if (edit == null) {
                    throw failure;
                }
                throw retainedFailure(failure, edit);
            }
            return new SetBlocks.Result(
                    world.name(),
                    geometry.bounds(),
                    palettes,
                    request.seed(),
                    outcome(request.dryRun(), execution.changedBlockCount()),
                    blockCount,
                    execution.changedBlockCount(),
                    blockCount - execution.changedBlockCount(),
                    edit);
        }
    }

    @Override
    public GetEditHistory.Result getEditHistory(GetEditHistory.Request request)
            throws OperationException {
        if (request == null) {
            throw invalid("request is required");
        }
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease =
                this.coordinator.enterHistory(world.id(), world.name())) {
            return new GetEditHistory.Result(world.name(), lease.history());
        }
    }

    @Override
    public UndoEdit.Result undoEdit(UndoEdit.Request request, UUID callId)
            throws OperationException {
        requireCallId(callId);
        if (request == null || request.editId() == null) {
            throw invalid("world and editId are required");
        }
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease = this.coordinator.enterUndo(world.id(), world.name())) {
            RetainedEdit edit = lease.latest();
            if (edit == null || !edit.record().editId().equals(request.editId())) {
                OperationFailure failure =
                        lease.contains(request.editId())
                                ? OperationFailure.EDIT_NOT_LATEST
                                : OperationFailure.EDIT_NOT_FOUND;
                String message =
                        failure == OperationFailure.EDIT_NOT_LATEST
                                ? "Edit is retained but is not the next edit eligible for undo"
                                : "Edit is not retained for this world: " + request.editId();
                throw new OperationException(failure, message);
            }
            try {
                this.platform.undo(world, edit.undo());
            } catch (OperationException failure) {
                lease.markRecoveryRequired(edit);
                throw recoveryUndoFailure(failure, edit.record().editId());
            } catch (RuntimeException failure) {
                lease.markRecoveryRequired(edit);
                throw new OperationException(
                        OperationFailure.INTERNAL_ERROR,
                        "Undo failed; recovery edit ID: " + edit.record().editId(),
                        failure,
                        edit.record().editId());
            }
            try {
                lease.removeLatest(edit);
                return new UndoEdit.Result(edit.record(), callId, Instant.now());
            } catch (RuntimeException failure) {
                throw new OperationException(
                        OperationFailure.INTERNAL_ERROR,
                        "Undo completed but response finalization failed; edit ID: "
                                + edit.record().editId(),
                        failure,
                        edit.record().editId());
            }
        }
    }

    private EditRecord retainCommitted(
            EditCoordinator.Lease lease,
            EditPlatform.PreparedOperation prepared,
            EditPlatform.WorldHandle world,
            BlockBounds bounds,
            UUID callId,
            UUID editId,
            EditOperation operation,
            boolean dryRun,
            long maximumChangedBlockCount,
            EditPlatform.EditResult execution)
            throws OperationException {
        if (dryRun || execution.changedBlockCount() == 0) {
            if (execution.undo() != null) {
                execution.undo().close();
                throw new IllegalStateException("Preview and no-change results cannot retain undo");
            }
            return null;
        }
        if (editId == null) {
            throw new IllegalStateException("A non-empty committed edit requires an ID");
        }
        EditPlatform.UndoToken undo = execution.undo();
        if (undo == null) {
            throw new OperationException(
                    OperationFailure.INTERNAL_ERROR,
                    "The edit completed without required undo data; edit ID: " + editId,
                    null,
                    editId);
        }

        final EditRecord record;
        final RetainedEdit retained;
        try {
            if (execution.changedBlockCount() > maximumChangedBlockCount) {
                throw new IllegalStateException("Edit backend returned an invalid change count");
            }
            record =
                    editRecord(
                            editId,
                            callId,
                            operation,
                            world,
                            bounds,
                            execution.changedBlockCount(),
                            EditStatus.COMMITTED);
            retained = new RetainedEdit(record, undo);
        } catch (RuntimeException failure) {
            throw unretainedFailure(
                    lease, prepared, world, bounds, callId, editId, operation, undo, failure);
        }

        try {
            if (lease.remember(retained)) {
                return record;
            }
        } catch (RuntimeException failure) {
            throw unretainedFailure(
                    lease, prepared, world, bounds, callId, editId, operation, undo, failure);
        }

        try {
            this.platform.rollbackPrepared(prepared, undo);
        } catch (OperationException | RuntimeException rollbackFailure) {
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE,
                    "The edit completed after its world became unavailable, but rollback failed; "
                            + "edit ID: "
                            + editId,
                    rollbackFailure,
                    editId);
        } finally {
            undo.close();
        }
        throw new OperationException(
                OperationFailure.WORLD_UNAVAILABLE,
                "The edit completed after its world became unavailable and was rolled back; edit "
                        + "ID: "
                        + editId,
                null,
                editId);
    }

    private OperationException unretainedFailure(
            EditCoordinator.Lease lease,
            EditPlatform.PreparedOperation prepared,
            EditPlatform.WorldHandle world,
            BlockBounds bounds,
            UUID callId,
            UUID editId,
            EditOperation operation,
            EditPlatform.UndoToken undo,
            RuntimeException failure) {
        try {
            this.platform.rollbackPrepared(prepared, undo);
        } catch (OperationException | RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            try {
                EditRecord recovery =
                        editRecord(
                                editId,
                                callId,
                                operation,
                                world,
                                bounds,
                                undo.changedBlockCount(),
                                EditStatus.RECOVERY_REQUIRED);
                if (lease.rememberRecovery(new RetainedEdit(recovery, undo))) {
                    return new OperationException(
                            OperationFailure.INTERNAL_ERROR,
                            "Undo-history finalization and automatic rollback failed; recovery "
                                    + "edit ID: "
                                    + editId,
                            failure,
                            editId);
                }
            } catch (RuntimeException retentionFailure) {
                failure.addSuppressed(retentionFailure);
            }
            try {
                this.platform.rollbackPrepared(prepared, undo);
            } catch (OperationException | RuntimeException retryFailure) {
                failure.addSuppressed(retryFailure);
                undo.close();
                return new OperationException(
                        OperationFailure.INTERNAL_ERROR,
                        "Undo-history finalization failed and recovery could not be retained or "
                                + "rolled back; edit ID: "
                                + editId,
                        failure,
                        editId);
            }
            undo.close();
            return new OperationException(
                    OperationFailure.INTERNAL_ERROR,
                    "The edit was rolled back after undo-history finalization and its first "
                            + "automatic rollback failed; edit ID: "
                            + editId,
                    failure,
                    editId);
        }
        undo.close();
        return new OperationException(
                OperationFailure.INTERNAL_ERROR,
                "The edit was rolled back after undo-history finalization failed; edit ID: "
                        + editId,
                failure,
                editId);
    }

    private void retainOrRollbackRecovery(
            EditCoordinator.Lease lease,
            EditPlatform.PreparedOperation prepared,
            EditPlatform.WorldHandle world,
            BlockBounds bounds,
            UUID callId,
            UUID editId,
            EditOperation operation,
            EditRecoveryException failure)
            throws OperationException {
        EditPlatform.UndoToken recovery = failure.recovery();
        if (editId == null) {
            recovery.close();
            throw new IllegalStateException("A dry run unexpectedly produced recovery history");
        }
        boolean finalizationFailed = false;
        try {
            EditRecord record =
                    editRecord(
                            editId,
                            callId,
                            operation,
                            world,
                            bounds,
                            recovery.changedBlockCount(),
                            EditStatus.RECOVERY_REQUIRED);
            if (lease.rememberRecovery(new RetainedEdit(record, recovery))) {
                return;
            }
        } catch (RuntimeException retentionFailure) {
            failure.addSuppressed(retentionFailure);
            finalizationFailed = true;
        }
        try {
            this.platform.rollbackPrepared(prepared, recovery);
        } catch (OperationException | RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            recovery.close();
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE,
                    "The edit and its automatic rollback failed, recovery could not be retained, "
                            + "and retry rollback failed; edit ID: "
                            + editId,
                    failure,
                    editId);
        }
        recovery.close();
        throw new OperationException(
                finalizationFailed ? OperationFailure.INTERNAL_ERROR : failure.failure(),
                "The edit failed, but its unretained recovery was rolled back; edit ID: " + editId,
                failure,
                editId);
    }

    private EditRecord editRecord(
            UUID editId,
            UUID callId,
            EditOperation operation,
            EditPlatform.WorldHandle world,
            BlockBounds bounds,
            long changedBlockCount,
            EditStatus status) {
        return new EditRecord(
                editId,
                callId,
                operation,
                world.name(),
                world.id(),
                bounds,
                changedBlockCount,
                Instant.now(),
                status);
    }

    private UUID pendingEditId(boolean dryRun) {
        if (dryRun) {
            return null;
        }
        return UUID.randomUUID();
    }

    private static EditOutcome outcome(boolean dryRun, long changedBlockCount) {
        if (dryRun) {
            return EditOutcome.PREVIEW;
        }
        return changedBlockCount == 0 ? EditOutcome.NO_CHANGE : EditOutcome.COMMITTED;
    }

    private static void requireCallId(UUID callId) throws OperationException {
        try {
            UuidV4.require(callId, "callId");
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid("callId must be a UUID version 4");
        }
    }

    private static OperationException recoveryFailure(EditRecoveryException failure, UUID editId) {
        return new OperationException(
                failure.failure(),
                failure.getMessage() + "; recovery edit ID: " + editId,
                failure,
                editId);
    }

    private static OperationException retainedFailure(OperationException failure, EditRecord edit) {
        if (edit == null || failure.editId().isPresent()) {
            return failure;
        }
        return new OperationException(
                failure.failure(),
                failure.getMessage() + "; committed edit ID: " + edit.editId(),
                failure,
                edit.editId());
    }

    private static OperationException recoveryUndoFailure(OperationException failure, UUID editId) {
        if (failure.editId().isPresent()) {
            return failure;
        }
        return new OperationException(
                failure.failure(),
                failure.getMessage() + "; recovery edit ID: " + editId,
                failure,
                editId);
    }

    private static OperationException retainedFailure(RuntimeException failure, EditRecord edit) {
        if (edit == null) {
            throw failure;
        }
        return new OperationException(
                OperationFailure.INTERNAL_ERROR,
                "The edit committed but response finalization failed; committed edit ID: "
                        + edit.editId(),
                failure,
                edit.editId());
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

    private SetRequestGeometry validateSetRequest(SetBlocks.Request request)
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
        BlockPosition min = null;
        BlockPosition max = null;
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
            min =
                    min == null
                            ? position
                            : new BlockPosition(
                                    Math.min(min.x(), position.x()),
                                    Math.min(min.y(), position.y()),
                                    Math.min(min.z(), position.z()));
            max =
                    max == null
                            ? position
                            : new BlockPosition(
                                    Math.max(max.x(), position.x()),
                                    Math.max(max.y(), position.y()),
                                    Math.max(max.z(), position.z()));
            chunks.add(ChunkPosition.containing(position.x(), position.z()));
            if (chunks.size() > this.maxTouchedChunks) {
                throw new OperationException(
                        OperationFailure.REGION_TOO_LARGE,
                        "Operation touches more than the maximum of "
                                + this.maxTouchedChunks
                                + " chunks");
            }
        }
        return new SetRequestGeometry(
                List.copyOf(positions),
                List.copyOf(chunks),
                new BlockBounds(
                        Objects.requireNonNull(min, "minimum position"),
                        Objects.requireNonNull(max, "maximum position")));
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

    private static List<List<DestinationPaletteEntry>> immutablePalettes(
            List<List<DestinationPaletteEntry>> palettes) {
        Objects.requireNonNull(palettes, "prepared palettes");
        return palettes.stream().map(List::copyOf).toList();
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
        EditPlatform.WorldHandle world = this.platform.resolveWorld(worldName);
        if (!worldName.equals(world.name())) {
            throw new OperationException(
                    OperationFailure.WORLD_NOT_FOUND,
                    "World is not loaded with the exact name: " + worldName);
        }
        return world;
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

    private record SetRequestGeometry(
            List<BlockPosition> positions, List<ChunkPosition> chunks, BlockBounds bounds) {
        private SetRequestGeometry {
            Objects.requireNonNull(positions, "positions");
            Objects.requireNonNull(chunks, "chunks");
            Objects.requireNonNull(bounds, "bounds");
        }
    }
}
