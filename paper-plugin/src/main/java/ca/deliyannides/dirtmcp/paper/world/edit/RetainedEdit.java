package ca.deliyannides.dirtmcp.paper.world.edit;

import java.util.Objects;

record RetainedEdit(EditRecord record, EditPlatform.UndoToken undo) {
    RetainedEdit {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(undo, "undo");
        if (record.changedBlockCount() != undo.changedBlockCount()) {
            throw new IllegalArgumentException("Edit metadata and undo token counts must match");
        }
    }

    RetainedEdit requireRecovery() {
        return this.record.status() == EditStatus.RECOVERY_REQUIRED
                ? this
                : new RetainedEdit(this.record.withStatus(EditStatus.RECOVERY_REQUIRED), this.undo);
    }
}
