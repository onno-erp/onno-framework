package su.onno.process;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import su.onno.types.Ref;

/**
 * Candidate stable identities and roles allowed to claim a human task.
 *
 * <p>An empty assignment is deliberately not public: only {@code ADMIN} can operate it. Use
 * {@link #roles} or {@link #identities} to make the work visible to business users.</p>
 */
public record TaskAssignment(Set<ProcessActorId> actors, Set<String> roles) {

    public TaskAssignment {
        actors = actors == null ? Set.of() : Set.copyOf(actors);
        roles = roles == null ? Set.of() : roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(TaskAssignment::normalizeRole)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static TaskAssignment actors(ProcessActorId... actorIds) {
        return new TaskAssignment(actorIds == null ? Set.of() : Set.of(actorIds), Set.of());
    }

    /** Assign directly to typed identity-catalog references. */
    public static TaskAssignment identities(Ref<?>... identities) {
        if (identities == null) {
            return new TaskAssignment(Set.of(), Set.of());
        }
        return new TaskAssignment(Arrays.stream(identities)
                .map(ProcessActorId::of)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()), Set.of());
    }

    public static TaskAssignment roles(String... roles) {
        return new TaskAssignment(Set.of(), values(roles));
    }

    public static TaskAssignment actorsAndRoles(
            Set<ProcessActorId> actors, Set<String> roles) {
        return new TaskAssignment(actors, roles);
    }

    public boolean allows(ProcessActor actor) {
        if (actor.roles().contains("ADMIN")) {
            return true;
        }
        return actors.contains(actor.id())
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
