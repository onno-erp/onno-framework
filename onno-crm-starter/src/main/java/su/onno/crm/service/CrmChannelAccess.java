package su.onno.crm.service;

import java.security.Principal;

/** Host policy for connection credentials. Defaults to administrators only. */
@FunctionalInterface
public interface CrmChannelAccess {
    boolean canManage(Principal principal);
}
