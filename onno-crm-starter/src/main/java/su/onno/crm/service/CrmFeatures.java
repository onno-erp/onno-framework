package su.onno.crm.service;

/**
 * Optional inbox behaviour, all off by default. Register a bean to enable features:
 *
 * <pre>{@code
 * @Bean CrmFeatures crmFeatures() { return CrmFeatures.defaults().withPersonalAccounts(true); }
 * }</pre>
 *
 * @param chatGroups       personal chat grouping in the inbox
 * @param personalAccounts users may connect their own provider accounts whose conversations only
 *                         they can read. Enabling it also closes the generic CRM catalogs to
 *                         administrators, because generic surfaces cannot filter personal inboxes.
 */
public record CrmFeatures(boolean chatGroups, boolean personalAccounts) {

    public CrmFeatures(boolean chatGroups) {
        this(chatGroups, false);
    }

    public static CrmFeatures defaults() {
        return new CrmFeatures(false, false);
    }

    public CrmFeatures withChatGroups(boolean enabled) {
        return new CrmFeatures(enabled, personalAccounts);
    }

    public CrmFeatures withPersonalAccounts(boolean enabled) {
        return new CrmFeatures(chatGroups, enabled);
    }
}
