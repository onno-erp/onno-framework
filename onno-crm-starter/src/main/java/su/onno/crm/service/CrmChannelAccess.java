package su.onno.crm.service;

import java.security.Principal;

/** Host policy for connection credentials. Defaults to administrators only. */
@FunctionalInterface
public interface CrmChannelAccess {
    boolean canManage(Principal principal);
    /** Override to restrict personal account onboarding; workspace write access is checked separately. */
    default boolean canManagePersonal(Principal principal) { return principal != null; }
}
