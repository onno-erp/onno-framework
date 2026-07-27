package su.onno.process;

import java.util.List;
import java.util.Objects;

/** Typed payload and active source tokens supplied to a definition migration. */
public record ProcessMigrationState<
        P, S extends Enum<S> & ProcessStepKey>(
        P payload,
        List<ProcessMigrationToken<S>> tokens) {

    public ProcessMigrationState {
        payload = Objects.requireNonNull(payload, "payload");
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
    }
}
