package su.onno.service;

import java.util.Set;

/**
 * Additional, deny-only gates for generic entity surfaces, including REST, UI, MCP and events. A
 * policy narrows what the {@code RoleGrants} entity grants allow; it never widens them. Every
 * policy constrains every user — no role is exempt implicitly. Trusted code
 * ({@code AccessSubject.system()}) is not checked.
 */
@FunctionalInterface
public interface EntityAccessPolicy {

    /**
     * Whether a user holding {@code roles} (normalized) may read ({@code write == false}) or write
     * the entity of {@code type} ({@code catalog}, {@code document}, {@code register},
     * {@code information register}) named {@code normalizedName} (spaces and underscores stripped,
     * lower case).
     */
    boolean allows(Set<String> roles, String type, String normalizedName, boolean write);
}
