package ca.deliyannides.dirtmcp.paper.bridge;

import java.io.IOException;

@FunctionalInterface
public interface RequestDecoder<T> {
    T decode(BridgeExchange exchange) throws IOException, InvalidRequestException;
}
