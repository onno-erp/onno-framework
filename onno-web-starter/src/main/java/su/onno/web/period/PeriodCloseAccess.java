package su.onno.web.period;

import su.onno.access.CorePermissions;
import su.onno.period.PeriodCloseOverridePolicy;
import su.onno.period.PeriodOverride;
import su.onno.service.AccessService;

import java.security.Principal;
import java.util.Optional;

/**
 * Who may manage and who may bypass period close: holders of
 * {@link CorePermissions#MANAGE_PERIOD_CLOSE} close and reopen periods; holders of
 * {@link CorePermissions#OVERRIDE_PERIOD_CLOSE} change documents inside a closed period. As the
 * {@link PeriodCloseOverridePolicy} it grants the override to the signed-in user of the current
 * request. The override is only ever held through an explicit grant: in an application without
 * grants (where every user holds every other permission) nobody bypasses a close, and trusted
 * background code has no signed-in user and is never exempt implicitly.
 */
public class PeriodCloseAccess implements PeriodCloseOverridePolicy {

    private final AccessService access;

    public PeriodCloseAccess(AccessService access) {
        this.access = access;
    }

    /** Whether {@code principal} may close and reopen periods. */
    public boolean canManage(Principal principal) {
        return access.has(principal, CorePermissions.MANAGE_PERIOD_CLOSE);
    }

    /** The signed-in user of the current request, for adjustment audit entries. */
    @Override
    public String actor() {
        return access.username(null);
    }

    @Override
    public Optional<PeriodOverride> current() {
        String username = access.username(null);
        if (username == null) return Optional.empty();
        // An explicit grant only: an application without grants resolves every permission to
        // true, which must not let every user bypass a closed period.
        boolean holds = access.access((Principal) null).granted(CorePermissions.OVERRIDE_PERIOD_CLOSE);
        return holds
                ? Optional.of(new PeriodOverride(username, "period-close override permission"))
                : Optional.empty();
    }
}
