package su.onno.access;

/**
 * A role the application defines, implemented by an enum so a role name is a compile-checked
 * constant rather than a string:
 *
 * <pre>{@code
 * enum AppRole implements RoleKey { MANAGER, ACCOUNTANT, IT }
 * }</pre>
 *
 * <p>The role's {@link #name()} is what a user account stores and what an identity provider's
 * role claim is matched against (normalized: upper case, no {@code ROLE_} prefix). What a role may
 * do is declared in one place, {@link RoleGrants}; a role carries no rights of its own and no role
 * bypasses its grants.
 */
public interface RoleKey {

    /** The stable role name; for an enum, the constant's name. */
    String name();
}
