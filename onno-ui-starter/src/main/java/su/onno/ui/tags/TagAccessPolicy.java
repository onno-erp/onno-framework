package su.onno.ui.tags;

import java.security.Principal;
import java.util.UUID;

/** Optional record-level authorization in addition to the owning entity's read/write roles. */
@FunctionalInterface
public interface TagAccessPolicy {
    void require(String kind, String name, UUID record, Principal principal, boolean write);
}
