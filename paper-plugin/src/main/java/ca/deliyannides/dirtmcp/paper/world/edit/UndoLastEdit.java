package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;

@FunctionalInterface
public interface UndoLastEdit {
    Result undoLastEdit(Request request) throws OperationException;

    record Request(String world) {}

    record Result(String world, long changedBlockCount) {}
}
