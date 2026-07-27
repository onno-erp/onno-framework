package su.onno.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

class BufferedHttpTelemetrySinkTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsAuthenticatedSanitizedBatch() throws Exception {
        ArrayBlockingQueue<Captured> requests = new ArrayBlockingQueue<>(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/telemetry/v1/fotori/batches", exchange -> {
            requests.add(new Captured(
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();

        TelemetryProperties properties = new TelemetryProperties();
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTenant("fotori");
        properties.setToken("secret");
        properties.setDeploymentId("deploy-1");
        properties.setFlushIntervalMs(60_000);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        try (BufferedHttpTelemetrySink sink = new BufferedHttpTelemetrySink(properties, mapper)) {
            sink.accept(new TelemetryEvent("id", "ux", "api.request", Instant.now(), "success",
                    12L, null, null, "session", "/api/orders/123?secret=yes",
                    Map.of("method", "GET", "notAllowed", "private")));
            sink.flush();
        }

        Captured captured = requests.poll(2, TimeUnit.SECONDS);
        assertThat(captured).isNotNull();
        assertThat(captured.authorization()).isEqualTo("Bearer secret");
        JsonNode json = mapper.readTree(captured.body());
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
        JsonNode event = json.path("events").get(0);
        assertThat(event.path("route").asText()).isEqualTo("/api/orders/:id");
        assertThat(event.path("dimensions").has("method")).isTrue();
        assertThat(event.path("dimensions").has("notAllowed")).isFalse();
    }

    @Test
    void normalizesIdentifiersAndQueryStrings() {
        assertThat(BufferedHttpTelemetrySink.normalizeRoute(
                "/documents/550e8400-e29b-41d4-a716-446655440000?token=x"))
                .isEqualTo("/documents/:id");
        assertThat(BufferedHttpTelemetrySink.normalizeRoute("/catalog/42/edit"))
                .isEqualTo("/catalog/:id/edit");
    }

    private record Captured(String authorization, byte[] body) {
    }
}
