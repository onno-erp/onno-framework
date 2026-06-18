package su.onno.types;

import java.util.Objects;
import java.util.UUID;

public record Ref<T>(Class<T> type, UUID id) {

    public Ref {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }

    public static <T> Ref<T> of(Class<T> type, UUID id) {
        return new Ref<>(type, id);
    }

    @Override
    public String toString() {
        return type.getSimpleName() + ":" + id;
    }
}
