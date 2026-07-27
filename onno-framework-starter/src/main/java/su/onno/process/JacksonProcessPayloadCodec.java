package su.onno.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/** Jackson-backed process payload codec supplied by the Spring framework starter. */
public final class JacksonProcessPayloadCodec implements ProcessPayloadCodec {

    private final ObjectMapper json;

    public JacksonProcessPayloadCodec(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Process value is not JSON serializable", exception);
        }
    }

    @Override
    public <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored process payload cannot be read as " + type.getName(), exception);
        }
    }
}
