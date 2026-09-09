package su.onno.ui;

import java.util.Set;

/** Additional, deny-only gates for generic entity surfaces, including REST, UI, MCP and events. */
public interface UiEntityAccessPolicy {
    boolean allows(Set<String> roles, String type, String normalizedName, boolean write);
}
