package su.onno.process;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Candidate users and roles allowed to claim a human task.
 *
 * <p>An empty assignment is deliberately not public: only {@code ADMIN} can operate it. Use
 * {@link #roles} or {@link #users} to make the work visible to business users.</p>
 */
public record TaskAssignment(Set<String> users, Set<String> roles) {

    public TaskAssignment {
        users = users == null ? Set.of() : users.stream()
                .filter(user -> user != null && !user.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        roles = roles == null ? Set.of() : roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(TaskAssignment::normalizeRole)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static TaskAssignment users(String... usernames) {
        return new TaskAssignment(values(usernames), Set.of());
    }

    public static TaskAssignment roles(String... roles) {
        return new TaskAssignment(Set.of(), values(roles));
    }

    public static TaskAssignment usersAndRoles(Set<String> users, Set<String> roles) {
        return new TaskAssignment(users, roles);
    }

    public boolean allows(ProcessActor actor) {
        if (actor.roles().contains("ADMIN")) {
            return true;
        }
        return users.contains(actor.username())
                || roles.stream().anyMatch(actor.roles()::contains);
    }

    private static Set<String> values(String[] values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            Arrays.stream(values).filter(v -> v != null && !v.isBlank())
                    .map(String::trim).forEach(result::add);
        }
        return Set.copyOf(result);
    }

    private static String normalizeRole(String role) {
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring(5) : normalized;
    }
}
