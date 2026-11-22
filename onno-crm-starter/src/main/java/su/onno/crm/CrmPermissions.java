package su.onno.crm;

import su.onno.access.Permission;

/**
 * The capabilities the CRM starter enforces. Grant them to roles in {@code RoleGrants}:
 *
 * <pre>{@code
 * .role(AppRole.SUPPORT_LEAD, r -> r.permit(CrmPermissions.ADMINISTER).read(Conversation.class))
 * }</pre>
 */
public final class CrmPermissions {

    /**
     * Administer the CRM: manage shared channel connections, open every inbox workspace, and read
     * the CRM catalogs (conversations, messages, contact identities, inboxes) on the generic
     * surfaces when personal accounts are off — together with an entity grant on those catalogs.
     * Only ever held through an explicit grant: an application without grants has no CRM
     * administrator. Another user's personal account stays private even to a holder.
     */
    public static final Permission ADMINISTER =
            Permission.of("crm.administer", "Manage CRM channels, open every inbox workspace and read raw CRM records");

    private CrmPermissions() {
    }
}
