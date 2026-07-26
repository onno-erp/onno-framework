package su.onno.process;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import su.onno.types.Ref;

import java.util.Objects;

/** Stable identity used for task ownership; normally the UUID of an identity catalog record. */
public record ProcessActorId(String value) {

    public ProcessActorId {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("actor id must not be blank");
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ProcessActorId of(String value) {
        return new ProcessActorId(value);
    }

    public static ProcessActorId of(Ref<?> identity) {
        return new ProcessActorId(Objects.requireNonNull(identity, "identity").id().toString());
    }

    @JsonValue
    public String jsonValue() {
        return value;
    }
}
