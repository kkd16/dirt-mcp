package ca.deliyannides.dirtmcp.paper.bridge;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class BridgeDispatcherTest {
    @Test
    void rejectsDuplicateRoutes() {
        BridgeEndpoint first = endpoint("first", "GET", "/v1/ping");
        BridgeEndpoint duplicate = endpoint("duplicate", "GET", "/v1/ping");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BridgeDispatcher(
                                List.of(first, duplicate),
                                new BearerAuthenticator(BridgeTestFixture.TOKEN, 32),
                                1,
                                1024,
                                Logger.getAnonymousLogger()));
    }

    @Test
    void rejectsRoutesOutsideTheVersionedNamespace() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BridgeDispatcher(
                                List.of(endpoint("bad", "GET", "/ping")),
                                new BearerAuthenticator(BridgeTestFixture.TOKEN, 32),
                                1,
                                1024,
                                Logger.getAnonymousLogger()));
    }

    private static BridgeEndpoint endpoint(String operation, String method, String path) {
        return new BridgeEndpoint() {
            @Override
            public String operation() {
                return operation;
            }

            @Override
            public String method() {
                return method;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public void handle(BridgeExchange exchange) {}

            @Override
            public String internalErrorMessage() {
                return "failure";
            }
        };
    }
}
