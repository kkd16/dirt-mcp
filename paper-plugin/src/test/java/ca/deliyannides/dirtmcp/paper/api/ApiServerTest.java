package ca.deliyannides.dirtmcp.paper.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class ApiServerTest {
    private static final Logger LOGGER = Logger.getLogger(ApiServerTest.class.getName());

    @Test
    void reportsHealthOnLoopback() throws Exception {
        try (ApiServer server = new ApiServer(0, "0.1.0-test", "26.2", LOGGER);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(healthUri(server)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "application/json; charset=utf-8",
                    response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(
                    "{\"status\":\"ok\",\"service\":\"dirt-mcp-paper\",\"version\":\"0.1.0-test\","
                            + "\"minecraftVersion\":\"26.2\",\"capabilities\":{\"worldEditing\":false}}",
                    response.body());
        }
    }

    @Test
    void rejectsUnsupportedMethods() throws Exception {
        try (ApiServer server = new ApiServer(0, "0.1.0-test", "26.2", LOGGER);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(healthUri(server))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(405, response.statusCode());
            assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
            assertEquals("{\"error\":\"method_not_allowed\"}", response.body());
        }
    }

    private static URI healthUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/health");
    }
}
