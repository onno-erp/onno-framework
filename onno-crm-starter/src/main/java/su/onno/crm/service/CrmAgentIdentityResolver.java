package su.onno.crm.service;

import java.util.Optional;
import java.util.UUID;
import su.onno.ui.CurrentUserResolver.CurrentUser;

/** Maps the host application's authenticated identity to a CRM agent. */
@FunctionalInterface
public interface CrmAgentIdentityResolver {

    Optional<UUID> resolve(CurrentUser user);
}
