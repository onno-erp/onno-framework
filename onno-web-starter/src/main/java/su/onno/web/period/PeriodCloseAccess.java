package su.onno.web.period;

import su.onno.period.PeriodCloseOverridePolicy;
import su.onno.period.PeriodOverride;
import su.onno.web.AccessService;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Who may manage and who may bypass period close, from {@code onno.period-close.manage-roles} and
 * {@code onno.period-close.override-roles}. As the {@link PeriodCloseOverridePolicy} it grants the
 * override to the signed-in user of the current request when they hold an override role; trusted
 * background code has no signed-in user and is never exempt implicitly.
 */
public class PeriodCloseAccess implements PeriodCloseOverridePolicy {

    private final AccessService access;
    private final List<String> manageRoles;
    private final List<String> overrideRoles;

    public PeriodCloseAccess(AccessService access, List<String> manageRoles, List<String> overrideRoles) {
        this.access = access;
        this.manageRoles = manageRoles == null ? List.of() : List.copyOf(manageRoles);
        this.overrideRoles = overrideRoles == null ? List.of() : List.copyOf(overrideRoles);
    }

    /** Whether {@code principal} may close and reopen periods ({@code ADMIN} always may). */
    public boolean canManage(Principal principal) {
        return access.hasAnyRole(principal, manageRoles);
    }

    /** The roles for an action's {@code .roles(...)} gate: {@code ADMIN} plus the manage roles. */
    public String[] manageRoleNames() {
        List<String> roles = new ArrayList<>();
        roles.add("ADMIN");
        roles.addAll(manageRoles);
        return roles.toArray(String[]::new);
    }

    /** The signed-in user of the current request, for adjustment audit entries. */
    @Override
    public String actor() {
        return access.username(null);
    }

    @Override
    public Optional<PeriodOverride> current() {
        if (overrideRoles.isEmpty()) return Optional.empty();
        String username = access.username(null);
        if (username == null) return Optional.empty();
        // Not hasAnyRole: ADMIN is exempt only when listed, like any other role.
        boolean holds = access.roles(null).stream()
                .anyMatch(role -> overrideRoles.stream().anyMatch(role::equalsIgnoreCase));
        return holds
                ? Optional.of(new PeriodOverride(username, "override role"))
                : Optional.empty();
    }
}
