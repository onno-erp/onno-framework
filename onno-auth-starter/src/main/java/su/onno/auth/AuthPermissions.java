package su.onno.auth;

import su.onno.access.Permission;

/**
 * The capabilities the auth starter enforces. Grant them to roles in {@code RoleGrants}:
 *
 * <pre>{@code
 * .role(AppRole.IT, r -> r.permit(AuthPermissions.MANAGE_USERS))
 * }</pre>
 */
public final class AuthPermissions {

    /**
     * Manage accounts in {@code onno.auth.mode=database}: list, create, enable/disable, change
     * roles, reset passwords and sign accounts out ({@code /api/auth/users}). The server refuses to
     * disable or demote the last enabled account that holds it.
     */
    public static final Permission MANAGE_USERS =
            Permission.of("users.manage", "Create, disable and change accounts, their roles and passwords");

    private AuthPermissions() {
    }
}
