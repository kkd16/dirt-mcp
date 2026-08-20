package ca.deliyannides.dirtmcp.paper.status;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;

@FunctionalInterface
public interface PingServer {
    Result ping() throws OperationException;

    record Result(String status) {}
}
