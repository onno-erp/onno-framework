package su.onno.access;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who is asking for data — the value every record-scoped read and write is evaluated against.
 *
 * <p>A {@link User} is a signed-in person: their login, normalized roles, the identity-linked
 * catalog record (from {@code Layout.identity(...)}) and app-defined attributes contributed by
 * {@link AccessSubjectContributor} beans. {@link System} is trusted framework or application code
 * (posting, jobs, processes, migrations, connectors) and is never scoped.
 *
 * <p>{@code System} is never <em>inferred</em> from a missing security context: every trusted entry
 * point passes it explicitly, so forgetting to resolve a user is a compile error rather than a
 * silent unscoped read. A subject is an immutable value, safe to capture into background tasks,
 * SSE subscriptions and MCP exchanges instead of relying on thread-local propagation.
 */
public sealed interface AccessSubject permits AccessSubject.User, AccessSubject.System {

    /** The role that bypasses entity-level and record-level checks. */
    String SUPERUSER_ROLE = "ADMIN";

    /** The normalized roles this subject holds (empty for {@link System}). */
    Set<String> roles();

    /** Whether this subject bypasses every record policy (the {@code ADMIN} superuser or {@link System}). */
    boolean superuser();

    /** The trusted, unscoped subject for background work, posting, migrations and connectors. */
    static AccessSubject system() {
        return System.INSTANCE;
    }

    /** A signed-in user with no identity record and no contributed attributes. */
    static User user(String username, Set<String> roles) {
        return new User(username, roles, null, Map.of());
    }

    /**
     * A signed-in user.
     *
     * @param username   the login (principal name)
     * @param roles      the granted roles, normalized to upper case without a {@code ROLE_} prefix
     * @param recordId   the identity-linked catalog record id, or {@code null} when no
     *                   {@code Layout.identity(...)} is configured or no record matches
     * @param attributes app-defined attributes from {@link AccessSubjectContributor} beans
     */
    record User(String username, Set<String> roles, UUID recordId, Map<String, Object> attributes)
            implements AccessSubject {

        public User {
            Set<String> normalized = new LinkedHashSet<>();
            if (roles != null) {
                for (String role : roles) {
                    String n = normalizeRole(role);
                    if (!n.isEmpty()) normalized.add(n);
                }
            }
            roles = Collections.unmodifiableSet(normalized);
            attributes = attributes == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        /** Whether the user holds {@code role} (compared normalized). */
        public boolean hasRole(String role) {
            return roles.contains(normalizeRole(role));
        }

        /** The contributed attribute {@code name}, or {@code null} when absent. */
        public Object attribute(String name) {
            return attributes.get(name);
        }

        /** A copy with {@code recordId} replaced. */
        public User withRecordId(UUID id) {
            return new User(username, roles, id, attributes);
        }

        /** A copy with {@code extra} merged over the existing attributes. */
        public User withAttributes(Map<String, ?> extra) {
            Map<String, Object> merged = new LinkedHashMap<>(attributes);
            if (extra != null) merged.putAll(extra);
            return new User(username, roles, recordId, merged);
        }

        @Override
        public boolean superuser() {
            return roles.contains(SUPERUSER_ROLE);
        }
    }

    /** Trusted code: posting, processes, jobs, migrations, connectors. Never record-scoped. */
    enum System implements AccessSubject {
        INSTANCE;

        @Override
        public Set<String> roles() {
            return Set.of();
        }

        @Override
        public boolean superuser() {
            return true;
        }
    }

    /** Upper-case and strip a {@code ROLE_} prefix, matching Spring Security authority naming. */
    static String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }
}
