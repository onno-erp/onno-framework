package su.onno.ui.tags;

import java.security.Principal;
import java.util.UUID;

/**
 * Optional record-level authorization in addition to the owning entity's read/write roles.
 *
 * @deprecated since 3.4.0: tag reads and writes now apply the record's
 *             {@link su.onno.access.RecordAccessPolicy} scope (read scope to list tags, write scope to
 *             change them). Express record-level rules there; this hook still runs in addition and is
 *             slated for removal in 4.0.
 */
@Deprecated(since = "3.4.0")
@FunctionalInterface
public interface TagAccessPolicy {
    void require(String kind, String name, UUID record, Principal principal, boolean write);
}
