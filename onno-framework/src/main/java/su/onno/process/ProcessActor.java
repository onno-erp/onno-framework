package su.onno.process;

import java.util.Objects;
import java.util.Set;
import su.onno.access.AccessSubject;

/**
 * Authenticated actor performing a process operation.
 *
 * @param identity      the stable identity of the actor
 * @param roles         the actor's roles, normalized by {@link AccessSubject#normalizeRole}
 * @param administrator whether the actor may operate every task and instance; the web layer sets
 *                      it from {@code processes.administer} ({@code CorePermissions.ADMINISTER_PROCESSES}).
 *                      No role name grants it implicitly.
 */
public record ProcessActor(ProcessIdentity identity, Set<String> roles, boolean administrator) {

    public ProcessActor {
        identity = Objects.requireNonNull(identity, "identity");
        roles = roles == null ? Set.of() : roles.stream()
                .map(AccessSubject::normalizeRole)
                .filter(role -> !role.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** An actor that is not a process administrator. */
    public ProcessActor(ProcessIdentity identity, Set<String> roles) {
        this(identity, roles, false);
    }

    /** Convenience for applications without an identity catalog; the login is the stable id. */
    public ProcessActor(String username, Set<String> roles) {
        this(ProcessIdentity.unlinked(username), roles, false);
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

    /** Whether the actor holds {@code role} (compared normalized). */
    public boolean hasRole(String role) {
        return roles.contains(AccessSubject.normalizeRole(Objects.requireNonNull(role, "role")));
    }
}
