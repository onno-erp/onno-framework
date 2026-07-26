package su.onno.process;

import java.util.Objects;
import java.util.Locale;
import java.util.Set;

/** Authenticated actor performing a process operation. */
public record ProcessActor(String username, Set<String> roles) {

    public ProcessActor {
        username = Objects.requireNonNull(username, "username").trim();
        if (username.isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        roles = roles == null ? Set.of() : roles.stream()
                .map(ProcessActor::normalizeRole)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean hasRole(String role) {
        return roles.contains("ADMIN") || roles.contains(normalizeRole(role));
    }

    private static String normalizeRole(String role) {
        String normalized = Objects.requireNonNull(role, "role").trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring(5) : normalized;
    }
}
