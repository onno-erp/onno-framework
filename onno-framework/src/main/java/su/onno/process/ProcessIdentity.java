package su.onno.process;

import java.util.Objects;

/** Stable process identity plus mutable login/display snapshots for audit and UI. */
public record ProcessIdentity(ProcessActorId id, String username, String displayName) {

    public ProcessIdentity {
        id = Objects.requireNonNull(id, "id");
        username = text(username, id.value());
        displayName = text(displayName, username);
    }

    public static ProcessIdentity unlinked(String username) {
        return new ProcessIdentity(ProcessActorId.of(username), username, username);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
