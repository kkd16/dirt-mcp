package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import java.time.Instant;
import java.util.UUID;

public record EditRecord(
        UUID editId,
        UUID callId,
        EditOperation operation,
        String world,
        UUID worldId,
        BlockBounds bounds,
        long changedBlockCount,
        Instant completedAt,
        EditStatus status) {
    EditRecord withStatus(EditStatus replacement) {
        return new EditRecord(
                this.editId,
                this.callId,
                this.operation,
                this.world,
                this.worldId,
                this.bounds,
                this.changedBlockCount,
                this.completedAt,
                replacement);
    }
}
