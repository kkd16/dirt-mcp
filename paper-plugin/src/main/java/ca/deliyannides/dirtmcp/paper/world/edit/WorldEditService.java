package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class WorldEditService
        implements ReplaceRegionBlocks, SetBlocks, GetEditHistory, UndoEdits, AutoCloseable {
    private final EditPlatform platform;
    private final EditCoordinator coordinator;
    private final EditRequestValidator validator;

    WorldEditService(
            EditPlatform platform,
            int maxRegionVolume,
            int maxTouchedChunks,
            int maxBlockStatePatterns,
            int maxPaletteEntries,
            int maxChangedBlocks,
            DirtConfig.EditHistory history) {
        this.platform = Objects.requireNonNull(platform, "platform");
        if (maxRegionVolume < 1
                || maxTouchedChunks < 1
                || maxBlockStatePatterns < 1
                || maxPaletteEntries < 1
                || maxChangedBlocks < 1) {
            throw new IllegalArgumentException("Edit and chunk limits must be positive");
        }
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
        this.validator =
                new EditRequestValidator(
                        maxRegionVolume,
                        maxTouchedChunks,
                        maxBlockStatePatterns,
                        maxPaletteEntries,
                        maxChangedBlocks,
                        checkedHistory.maxEntriesPerWorld());
    }

    @Override
    public ReplaceRegionBlocks.Result replaceRegionBlocks(
            ReplaceRegionBlocks.Request request, UUID callId) throws OperationException {
        requireCallId(callId);
        EditRequestValidator.ValidatedReplace validated = this.validator.validateReplace(request);
        int effectiveMaxChangedBlocks = validated.maxChangedBlocks();
        Cuboid region = validated.region();
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        UUID editId = pendingEditId(request.dryRun());
        try (EditCoordinator.Lease lease =
                this.coordinator.enterMutation(world.id(), world.name())) {
            EditPlatform.EditResult execution;
            EditRecord edit = null;
            try (EditPlatform.PreparedReplace prepared =
                    this.platform.prepareReplace(world, request, region)) {
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
        EditRequestValidator.ValidatedSet validated = this.validator.validateSet(request);
        SetBlockGeometry geometry = validated.geometry();
        int effectiveMaxChangedBlocks = validated.maxChangedBlocks();
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease =
                this.coordinator.enterMutation(world.id(), world.name())) {
            if (geometry.isEmpty()) {
                return new SetBlocks.Result(
                        world.name(),
                        null,
                        request.seed(),
                        outcome(request.dryRun(), 0),
                        0,
                        0,
                        0,
                        null);
            }
            UUID editId = pendingEditId(request.dryRun());
            EditPlatform.EditResult execution;
            EditRecord edit = null;
            int blockCount;
            try (EditPlatform.PreparedSet prepared =
                    this.platform.prepareSet(world, request, geometry)) {
                blockCount = prepared.blockCount();
                if (blockCount != geometry.blockCount()) {
                    throw new IllegalStateException(
                            "Prepared set-blocks count does not match its validated request");
                }
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
        this.validator.validateUndo(request);
        EditPlatform.WorldHandle world = resolveWorld(request.world());
        try (EditCoordinator.Lease lease = this.coordinator.enterUndo(world.id(), world.name())) {
            List<RetainedEdit> prefix = lease.requireUndoPrefix(request.editIds());
            List<EditRecord> undone = new ArrayList<>(prefix.size());
            for (RetainedEdit edit : prefix) {
                try {
                    this.platform.undo(world, edit.undo());
                } catch (OperationException failure) {
                    lease.markRecoveryRequired(edit);
                    boolean worldUnavailable =
                            failure.failure() == OperationFailure.WORLD_UNAVAILABLE;
                    return new UndoEdits.Partial(
                            world.name(),
                            callId,
                            undone,
                            new OperationException(
                                    worldUnavailable
                                            ? OperationFailure.WORLD_UNAVAILABLE
                                            : OperationFailure.INTERNAL_ERROR,
                                    failure.getMessage(),
                                    worldUnavailable ? failure.details().orElseThrow() : null,
                                    failure,
                                    edit.record().editId()));
                } catch (RuntimeException failure) {
                    lease.markRecoveryRequired(edit);
                    return new UndoEdits.Partial(
                            world.name(),
                            callId,
                            undone,
                            new OperationException(
                                    OperationFailure.INTERNAL_ERROR,
                                    "Undo failed; recovery edit ID: " + edit.record().editId(),
                                    null,
                                    failure,
                                    edit.record().editId()));
                }
                lease.consumeRestored(edit);
                undone.add(edit.record());
            }
            return new UndoEdits.Completed(world.name(), undone, callId, Instant.now());
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

    private static OperationException invalid(String message, ErrorDetails.InvalidRequest details) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message, details);
    }
}
