package su.onno.crm.service;

import java.util.*;
import java.util.function.Function;
import su.onno.model.CatalogObject;
import su.onno.ui.CurrentUserResolver.CurrentUser;

/** Optional assignment capability backed by the host's employee/identity catalog. */
public record CrmAgentBinding<T extends CatalogObject>(CrmCatalogBinding<T> catalog,
        Function<CurrentUser, Optional<UUID>> currentAgent) {
    public CrmAgentBinding { Objects.requireNonNull(catalog); Objects.requireNonNull(currentAgent); }
    public Optional<UUID> resolve(CurrentUser user) {
        return currentAgent.apply(user).filter(id -> catalog.find(id).isPresent());
    }
}
