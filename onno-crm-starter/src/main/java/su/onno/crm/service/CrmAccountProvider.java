package su.onno.crm.service;

import java.util.List;
import su.onno.crm.service.CrmConnectionAccounts.Scope;

/**
 * An installed account provider, listed even while unavailable so the connections page can explain
 * what setup is missing. Descriptors never carry credentials.
 *
 * <p>Account {@link #view states} and {@link #command actions} use the same open vocabulary as
 * {@link CrmChannelConnection} ({@code CONNECTED}, {@code DISCONNECTED}, … and {@code connect},
 * {@code check}, …) so one connections UI renders both, and a provider can add its own actions
 * ({@code rename} and {@code removeDraft} are reserved by the CRM).</p>
 *
 * <p><b>Privacy contract.</b> A provider that advertises {@link Scope#PERSONAL} must keep each
 * account's credentials and inbox separate, bind the inbox with
 * {@link CrmConnectionAccounts#bindInbox}, and resolve contacts with the account's inbox UUID as
 * {@code IncomingContact.connectionKey}; that key is how other users are kept from seeing identities
 * that only appear in someone's personal inbox.</p>
 */
public interface CrmAccountProvider {

    /**
     * @param scopes ownership the provider can manage per account. Empty means the provider only
     *               works through one shared, server-configured account and cannot be added here.
     */
    record Descriptor(String key, String label, String channel, String iconUrl, String description,
                      boolean enabled, List<Scope> scopes, String setupHint) {
        public Descriptor {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }

        public boolean supports(Scope scope) { return scopes.contains(scope); }
    }

    Descriptor descriptor();

    default CrmChannelConnection.View view(CrmConnectionAccounts.Account account) {
        var descriptor = descriptor();
        return new CrmChannelConnection.View(account.id().toString(), descriptor.label(), descriptor.channel(),
                descriptor.enabled() ? "DISCONNECTED" : "UNAVAILABLE", "", descriptor.description(), List.of());
    }

    /** Run one of the actions the account's {@link #view} offers. */
    default void command(CrmConnectionAccounts.Account account, String action) {
        throw new IllegalArgumentException("This provider does not support managed accounts");
    }
}
