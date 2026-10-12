package su.onno.ui;

import java.util.Set;

/** Additional, deny-only gates for generic entity surfaces, including REST, UI, MCP and events. */
public interface UiEntityAccessPolicy {
    /** Explicit opt-in for privacy boundaries that must also constrain the ADMIN role. */
    default boolean includesAdministrators() { return false; }
    boolean allows(Set<String> roles, String type, String normalizedName, boolean write);
}
