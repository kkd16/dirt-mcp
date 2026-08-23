package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import ca.deliyannides.dirtmcp.paper.world.model.RegionGeometry;
import java.time.Instant;
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
import org.bukkit.plugin.java.JavaPlugin;

public final class FaweWorldEditor
        implements ReplaceRegionBlocks, SetBlocks, GetEditHistory, UndoEdits, AutoCloseable {
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
            throw invalid(
                    "min and max are required",
                    new ErrorDetails.InvalidRequest.Missing(missingBoundsField(request)));
        }
        validateEditLabel(request.label());
        int effectiveMaxChangedBlocks = effectiveMaxChangedBlocks(request.maxChangedBlocks());
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
                long historyReservation =
                        Math.min((long) effectiveMaxChangedBlocks, region.volume());
                try {
                    execution =
                            this.platform.replace(
                                    prepared,
                                    region,
                                    request.dryRun(),
                                    effectiveMaxChangedBlocks,
                                    () -> lease.reserveHistory(historyReservation));
                } catch (EditRecoveryException failure) {
                    retainOrRollbackRecovery(
                            lease,
                            prepared,
                            world,
                            region.bounds(),
                            callId,
                            editId,
                            EditOperation.REPLACE_REGION_BLOCKS,
                            request.label(),
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
                                request.label(),
                                request.dryRun(),
                                historyReservation,
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
    public SetBlocks.Result setBlocks(SetBlocks.Request request, UUID callId)
            throws OperationException {
        requireCallId(callId);
        SetBlockGeometry geometry = validateSetRequest(request);
        validateEditLabel(request.label());
        int effectiveMaxChangedBlocks = effectiveMaxChangedBlocks(request.maxChangedBlocks());
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease =
                this.coordinator.enterMutation(world.id(), world.name())) {
            if (geometry.isEmpty()) {
                return new SetBlocks.Result(
                        world.name(),
                        null,
                        List.of(),
                        request.seed(),
                        EditOutcome.NO_CHANGE,
                        0,
                        0,
                        0,
                        null);
            }
            UUID editId = pendingEditId(request.dryRun());
            EditPlatform.EditResult execution;
            EditRecord edit = null;
            int blockCount;
            List<List<DestinationPaletteEntry>> palettes;
            try (EditPlatform.PreparedSet prepared =
                    this.platform.prepareSet(world, request, geometry)) {
                blockCount = prepared.blockCount();
                if (blockCount != geometry.blockCount()) {
                    throw new IllegalStateException(
                            "Prepared set-blocks count does not match its validated request");
                }
                palettes = immutablePalettes(prepared.palettes());
                long historyReservation = Math.min((long) effectiveMaxChangedBlocks, blockCount);
                try {
                    execution =
                            this.platform.set(
                                    prepared,
                                    request.dryRun(),
                                    effectiveMaxChangedBlocks,
                                    () -> lease.reserveHistory(historyReservation));
                } catch (EditRecoveryException failure) {
                    retainOrRollbackRecovery(
                            lease,
                            prepared,
                            world,
                            geometry.bounds(),
                            callId,
                            editId,
                            EditOperation.SET_BLOCKS,
                            request.label(),
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
                                request.label(),
                                request.dryRun(),
                                historyReservation,
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
            throw invalid(
                    "request is required", new ErrorDetails.InvalidRequest.Missing("request"));
        }
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease =
                this.coordinator.enterHistory(world.id(), world.name())) {
            return new GetEditHistory.Result(world.name(), lease.history());
        }
    }

    @Override
    public UndoEdits.Result undoEdits(UndoEdits.Request request, UUID callId)
            throws OperationException {
        requireCallId(callId);
        if (request == null || request.editIds() == null) {
            throw invalid(
                    "world and editIds are required",
                    new ErrorDetails.InvalidRequest.Missing(
                            request == null ? "request" : "editIds"));
        }
        validateUndoEditIds(request.editIds());
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease = this.coordinator.enterUndo(world.id(), world.name())) {
            List<RetainedEdit> prefix = lease.requireUndoPrefix(request.editIds());
            List<EditRecord> undone = new ArrayList<>(prefix.size());
            for (RetainedEdit edit : prefix) {
                try {
                    this.platform.undo(world, edit.undo());
                } catch (OperationException failure) {
                    lease.markRecoveryRequired(edit);
                    throw new UndoEditsException(
                            failure.failure(),
                            failure.getMessage(),
                            failure.details().orElse(null),
                            failure,
                            edit.record().editId(),
                            undone);
                } catch (RuntimeException failure) {
                    lease.markRecoveryRequired(edit);
                    throw new UndoEditsException(
                            OperationFailure.INTERNAL_ERROR,
                            "Undo failed; recovery edit ID: " + edit.record().editId(),
                            null,
                            failure,
                            edit.record().editId(),
                            undone);
                }
                lease.consumeRestored(edit);
                undone.add(edit.record());
            }
            return new UndoEdits.Result(world.name(), undone, callId, Instant.now());
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
            String label,
            boolean dryRun,
            long reservedChangedBlockCount,
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
                    null,
                    editId);
        }

        final EditRecord record;
        try {
            if (execution.changedBlockCount() > reservedChangedBlockCount) {
                throw new IllegalStateException("Edit backend returned an invalid change count");
            }
            record =
                    editRecord(
                            editId,
                            callId,
                            operation,
                            label,
                            world,
                            bounds,
                            execution.changedBlockCount(),
                            EditStatus.COMMITTED);
            if (lease.remember(new RetainedEdit(record, undo))) {
                return record;
            }
        } catch (RuntimeException failure) {
            throw unretainedFailure(
                    lease, prepared, world, bounds, callId, editId, operation, label, undo,
                    failure);
        }

        try {
            this.platform.rollbackPrepared(prepared, undo);
        } catch (OperationException | RuntimeException rollbackFailure) {
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE,
                    "The edit completed after its world became unavailable, but rollback failed; "
                            + "edit ID: "
                            + editId,
                    new ErrorDetails.WorldUnavailable.RollbackFailed(),
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
                new ErrorDetails.WorldUnavailable.RolledBack(),
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
            String label,
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
                                label,
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
                            null,
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
                        null,
                        failure,
                        editId);
            }
            undo.close();
            return new OperationException(
                    OperationFailure.INTERNAL_ERROR,
                    "The edit was rolled back after undo-history finalization and its first "
                            + "automatic rollback failed; edit ID: "
                            + editId,
                    null,
                    failure,
                    editId);
        }
        undo.close();
        return new OperationException(
                OperationFailure.INTERNAL_ERROR,
                "The edit was rolled back after undo-history finalization failed; edit ID: "
                        + editId,
                null,
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
            String label,
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
                            label,
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
                    new ErrorDetails.WorldUnavailable.RollbackFailed(),
                    failure,
                    editId);
        }
        recovery.close();
        if (finalizationFailed) {
            throw new OperationException(
                    OperationFailure.INTERNAL_ERROR,
                    "The edit failed, but its unretained recovery was rolled back; edit ID: "
                            + editId,
                    null,
                    failure,
                    editId);
        }
        throw new OperationException(
                failure.failure(),
                "The edit failed, but its unretained recovery was rolled back; edit ID: " + editId,
                new ErrorDetails.WorldUnavailable.RolledBack(),
                failure,
                editId);
    }

    private EditRecord editRecord(
            UUID editId,
            UUID callId,
            EditOperation operation,
            String label,
            EditPlatform.WorldHandle world,
            BlockBounds bounds,
            long changedBlockCount,
            EditStatus status) {
        return new EditRecord(
                editId,
                callId,
                operation,
                label,
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
            throw invalid(
                    "callId must be a UUID version 4",
                    new ErrorDetails.InvalidRequest.InvalidValue("callId"));
        }
    }

    private static OperationException recoveryFailure(EditRecoveryException failure, UUID editId) {
        return new OperationException(
                failure.failure(),
                failure.getMessage() + "; recovery edit ID: " + editId,
                failure.details().orElseThrow(),
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
                failure.details().orElse(null),
                failure,
                edit.editId());
    }

    private static OperationException retainedFailure(RuntimeException failure, EditRecord edit) {
        if (edit == null) {
            throw failure;
        }
        return new OperationException(
                OperationFailure.INTERNAL_ERROR,
                "The edit committed but response finalization failed; committed edit ID: "
                        + edit.editId(),
                null,
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

    private void validateUndoEditIds(List<UUID> editIds) throws OperationException {
        if (editIds.isEmpty()) {
            throw invalid(
                    "editIds must contain at least one edit ID",
                    new ErrorDetails.InvalidRequest.InvalidValue("editIds"));
        }
        int maximum = this.coordinator.maxEntriesPerWorld();
        if (editIds.size() > maximum) {
            throw invalid(
                    "editIds may contain at most " + maximum + " edit IDs",
                    new ErrorDetails.InvalidRequest.TooManyItems(List.of("editIds"), maximum));
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

    private static void validateEditLabel(String label) throws OperationException {
        if (label == null) {
            throw invalid("label is required", new ErrorDetails.InvalidRequest.Missing("label"));
        }
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

    private SetBlockGeometry validateSetRequest(SetBlocks.Request request)
            throws OperationException {
        if (request == null || request.origin() == null) {
            throw invalid(
                    "origin is required",
                    new ErrorDetails.InvalidRequest.Missing(
                            request == null ? "request" : "origin"));
        }
        if (request.placements() == null || request.runs() == null) {
            String field = request.placements() == null ? "placements" : "runs";
            throw invalid(
                    "placements and runs are required",
                    new ErrorDetails.InvalidRequest.Missing(field));
        }
        if (request.placements().isEmpty() && request.runs().isEmpty()) {
            if (request.palettes() == null || !request.palettes().isEmpty()) {
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
            String placementName = "placements[" + placementIndex + "]";
            if (placement == null) {
                throw invalid(
                        placementName + " is required",
                        new ErrorDetails.InvalidRequest.Missing(placementName));
            }
            validatePaletteIndex(
                    placement.paletteIndex(), request.palettes().size(), placementName);
            BlockPosition position =
                    resolvePosition(
                            request.origin(),
                            placement.x(),
                            placement.y(),
                            placement.z(),
                            placementName);
            addTouchedChunk(chunks, ChunkPosition.containing(position.x(), position.z()));
            occupancy.add(position, placementName);
            placements.add(
                    new SetBlockGeometry.ResolvedPlacement(placement.paletteIndex(), position));
            bounds.include(position);
        }
        for (int runIndex = 0; runIndex < request.runs().size(); runIndex++) {
            Run run = request.runs().get(runIndex);
            String runName = "runs[" + runIndex + "]";
            if (run == null) {
                throw invalid(
                        runName + " is required", new ErrorDetails.InvalidRequest.Missing(runName));
            }
            validatePaletteIndex(run.paletteIndex(), request.palettes().size(), runName);
            if (run.x() > run.toX() || run.y() > run.toY() || run.z() > run.toZ()) {
                throw invalid(
                        runName + " must use component-wise forward inclusive corners",
                        new ErrorDetails.InvalidRequest.InvalidValue(runName));
            }
            requestedBlockCount = addRunBlockCount(requestedBlockCount, run);
            BlockPosition from =
                    resolvePosition(request.origin(), run.x(), run.y(), run.z(), runName);
            BlockPosition to =
                    resolvePosition(request.origin(), run.toX(), run.toY(), run.toZ(), runName);
            Cuboid region = new Cuboid(from, to);
            addTouchedChunks(chunks, region);
            occupancy.add(region, runName);
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
        if (palettes == null || palettes.isEmpty()) {
            throw invalid(
                    "palettes must contain at least one palette",
                    new ErrorDetails.InvalidRequest.InvalidValue("palettes"));
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
                                + " entries in total",
                        new ErrorDetails.InvalidRequest.TooManyItems(
                                List.of("palettes"), this.maxBlockStatePatterns));
            }
        }
    }

    private static List<List<DestinationPaletteEntry>> immutablePalettes(
            List<List<DestinationPaletteEntry>> palettes) {
        Objects.requireNonNull(palettes, "prepared palettes");
        return palettes.stream().map(List::copyOf).toList();
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
        if (chunks.size() > this.maxTouchedChunks) {
            throw new OperationException(
                    OperationFailure.REGION_TOO_LARGE,
                    "Operation touches more than the maximum of "
                            + this.maxTouchedChunks
                            + " chunks",
                    new ErrorDetails.RegionTooLarge.TouchedChunks(
                            (long) this.maxTouchedChunks + 1, this.maxTouchedChunks));
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
            String placementField, String resolvedCoordinate, long value) {
        return invalid(
                placementField + " resolves outside the signed 32-bit coordinate range",
                new ErrorDetails.InvalidRequest.OutOfRange(
                        placementField + resolvedCoordinate,
                        value,
                        Integer.MIN_VALUE,
                        Integer.MAX_VALUE));
    }

    private EditPlatform.WorldHandle resolveWorld(String worldName) throws OperationException {
        if (worldName == null || worldName.isBlank()) {
            throw invalid(
                    "world must be a non-empty string",
                    new ErrorDetails.InvalidRequest.InvalidValue("world"));
        }
        EditPlatform.WorldHandle world = this.platform.resolveWorld(worldName);
        if (!worldName.equals(world.name())) {
            throw new OperationException(
                    OperationFailure.WORLD_NOT_FOUND,
                    "World is not loaded with the exact name: " + worldName,
                    new ErrorDetails.WorldNotFound(worldName));
        }
        return world;
    }

    private void validatePalette(List<DestinationPaletteEntry> palette, String field)
            throws OperationException {
        if (palette == null || palette.isEmpty()) {
            throw invalid(
                    field + " must contain at least one entry",
                    new ErrorDetails.InvalidRequest.InvalidValue(field));
        }
        if (palette.size() > this.maxBlockStatePatterns) {
            throw invalid(
                    field + " may contain at most " + this.maxBlockStatePatterns + " entries",
                    new ErrorDetails.InvalidRequest.TooManyItems(
                            List.of(field), this.maxBlockStatePatterns));
        }
        boolean weighted = false;
        boolean unweighted = false;
        long totalWeight = 0;
        for (int index = 0; index < palette.size(); index++) {
            DestinationPaletteEntry entry = palette.get(index);
            if (entry == null || entry.blockState() == null || entry.blockState().isBlank()) {
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
        if (values == null || values.isEmpty()) {
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
            if (value == null || value.isBlank()) {
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

    private static String missingBoundsField(ReplaceRegionBlocks.Request request) {
        if (request == null) {
            return "request";
        }
        return request.min() == null ? "min" : "max";
    }

    private static OperationException invalid(String message, ErrorDetails.InvalidRequest details) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message, details);
    }

    /** Detects overlap in 16-cubed sections without retaining one object per covered block. */
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
