package su.onno.process;

import java.util.Objects;
import java.util.Locale;
import java.util.Set;

/** Authenticated actor performing a process operation. */
public record ProcessActor(ProcessIdentity identity, Set<String> roles) {

    public ProcessActor {
        identity = Objects.requireNonNull(identity, "identity");
        roles = roles == null ? Set.of() : roles.stream()
                .map(ProcessActor::normalizeRole)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Convenience for applications without an identity catalog; the login is the stable id. */
    public ProcessActor(String username, Set<String> roles) {
        this(ProcessIdentity.unlinked(username), roles);
    }

    public ProcessActorId id() {
        return identity.id();
    }

    public String username() {
        return identity.username();
    }

    public String displayName() {
        return identity.displayName();
    }

    public boolean hasRole(String role) {
        return roles.contains("ADMIN") || roles.contains(normalizeRole(role));
    }

    private static String normalizeRole(String role) {
        String normalized = Objects.requireNonNull(role, "role").trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring(5) : normalized;
    }
}
