package su.onno.access;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one subject may do: the merged grants of the roles it holds, resolved by
 * {@link RoleGrants#resolve}. A right or permission is held when any held role grants it.
 */
public final class EffectiveAccess {

    /** Everything: trusted code, and every user of an application without grants. */
    static final EffectiveAccess ALL = new EffectiveAccess(null);

    /**
     * Nothing: an anonymous caller (no signed-in user). Even an application without grants, where
     * every signed-in user may do everything, gives an anonymous caller nothing.
     */
    public static final EffectiveAccess NONE = new EffectiveAccess(List.of());

    private final List<RoleGrants.RoleGrant> roles;

    EffectiveAccess(List<RoleGrants.RoleGrant> roles) {
        this.roles = roles;
    }

    /** Whether the subject may exercise {@code right} on {@code entity} (its Java class). */
    public boolean can(Class<?> entity, EntityRight right) {
        if (roles == null) return true;
        return roles.stream().anyMatch(g -> g.can(entity, right));
    }

    /** Whether the subject holds {@code permission}. */
    public boolean has(Permission permission) {
        if (roles == null) return true;
        return roles.stream().anyMatch(g -> g.permissions().contains(permission));
    }

    /**
     * Whether a role the subject holds explicitly grants {@code permission}. Unlike {@link #has},
     * unrestricted access (trusted code, an application without grants) does not imply it. For
     * capabilities that change what a user sees or bypass a safeguard — a closed-period override,
     * every process task, every CRM workspace — which an application must hand out deliberately.
     */
    public boolean granted(Permission permission) {
        return !unrestricted() && has(permission);
    }

    /** Whether this is unrestricted access (trusted code, or an application without grants). */
    public boolean unrestricted() {
        return roles == null;
    }

    /** The application roles the subject holds, by name (empty when {@link #unrestricted()}). */
    public Set<String> roles() {
        if (roles == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (RoleGrants.RoleGrant g : roles) out.add(g.name());
        return Collections.unmodifiableSet(out);
    }

    /** The permissions the subject holds, by key (every role's, merged). Empty when unrestricted. */
    public Set<Permission> permissions() {
        if (roles == null) return Set.of();
        Set<Permission> out = new LinkedHashSet<>();
        for (RoleGrants.RoleGrant g : roles) out.addAll(g.permissions());
        return Collections.unmodifiableSet(out);
    }
}
