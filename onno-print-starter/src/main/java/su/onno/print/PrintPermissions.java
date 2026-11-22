package su.onno.print;

import su.onno.access.Permission;

/**
 * The capabilities the print starter enforces. Grant them to roles in {@code RoleGrants}:
 *
 * <pre>{@code
 * .role(AppRole.IT, r -> r.permit(PrintPermissions.MANAGE_TEMPLATES))
 * }</pre>
 */
public final class PrintPermissions {

    /** List, download, upload and reset print templates ({@code /api/print/templates}). */
    public static final Permission MANAGE_TEMPLATES =
            Permission.of("print.templates.manage", "List, download, upload and reset print templates");

    private PrintPermissions() {
    }
}
