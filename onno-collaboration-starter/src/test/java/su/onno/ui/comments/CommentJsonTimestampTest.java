package su.onno.ui.comments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire contract for comment timestamps. Before #177 {@code createdAt}/{@code editedAt} were
 * zoneless {@code LocalDateTime}s and serialized without a {@code Z}/offset, so a viewer off UTC
 * mis-rendered "Xh ago" by their own offset. They are {@link Instant}s now; this pins the
 * zone-qualified ({@code "…Z"}) JSON the server emits.
 */
class CommentJsonTimestampTest {

    // Mirrors the ObjectMapper Spring Boot serves responses with: jackson-datatype-jsr310 registered
    // (on the classpath) and WRITE_DATES_AS_TIMESTAMPS disabled by JacksonAutoConfiguration, so
    // temporals are ISO-8601 strings, not epoch numbers. The issue's evidence — a LocalDateTime
    // rendered as "2026-06-22T23:28:02.128714" — confirms this config is what the app actually runs.
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void serializesCreatedAtZoneQualifiedAndEditedAtNull() throws Exception {
        Instant createdAt = Instant.parse("2026-06-22T23:28:02.128714Z");
        // The shape CommentController.toJson puts on the wire for the temporal fields.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("createdAt", createdAt);
        json.put("editedAt", null);

        String body = mapper.writeValueAsString(json);

        String emitted = mapper.readTree(body).get("createdAt").asText();
        // Zone-qualified (the whole point of #177) and parses back to the same instant — clients in
        // any zone localize it correctly instead of reading it as local wall-clock.
        assertThat(emitted).endsWith("Z");
        assertThat(Instant.parse(emitted)).isEqualTo(createdAt);
        assertThat(body).contains("\"editedAt\":null");
    }
}
