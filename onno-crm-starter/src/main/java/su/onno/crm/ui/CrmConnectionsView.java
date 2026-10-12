package su.onno.crm.ui;

import su.onno.crm.service.CrmConnectionAccounts.Scope;
import su.onno.ui.PageBuilder;
import su.onno.ui.PageView;

import java.util.*;
import java.util.stream.Collectors;

/** Typed placement of the CRM channel-connections widget ({@code crmConnections}; the older
 * {@code crmSettings} type name renders the same widget). Scopes are {@link Scope} values. Configuration
 * filters presentation only; connector access is still enforced by the connection-accounts API.
 * The first placement freezes the view: later configuration calls throw {@link IllegalStateException}.
 */
public final class CrmConnectionsView implements PageView {
    private String title = "Connections";
    private List<Scope> scopes = List.of();
    private List<String> providers = List.of();
    private Scope defaultScope;
    private String sharedLabel;
    private String personalLabel;
    private Boolean showUnavailable;
    private volatile boolean frozen;

    public CrmConnectionsView title(String title) { mutable(); this.title = required(title); return this; }
    /** Tabs to offer, in order. Defaults to both. */
    public CrmConnectionsView scopes(Scope... scopes) {
        mutable();
        var next = Arrays.stream(scopes).map(s -> Objects.requireNonNull(s, "scope")).distinct().toList();
        if (next.isEmpty()) throw new IllegalArgumentException("At least one scope is required");
        this.scopes = next; return this;
    }
    /** Connector provider keys to show (e.g. {@code "gmail"}). Defaults to every registered provider. */
    public CrmConnectionsView providers(String... keys) {
        mutable(); this.providers = Arrays.stream(keys).map(CrmConnectionsView::required).distinct().toList(); return this;
    }
    /** The tab selected initially; must be one of {@link #scopes}. */
    public CrmConnectionsView defaultScope(Scope scope) { mutable(); this.defaultScope = Objects.requireNonNull(scope, "scope"); return this; }
    public CrmConnectionsView sharedLabel(String label) { mutable(); this.sharedLabel = required(label); return this; }
    public CrmConnectionsView personalLabel(String label) { mutable(); this.personalLabel = required(label); return this; }
    /** Also list providers that are not enabled or don't support the selected scope (default true). */
    public CrmConnectionsView showUnavailable(boolean show) { mutable(); this.showUnavailable = show; return this; }

    @Override
    public void compose(PageBuilder page) {
        if (defaultScope != null && !scopes.isEmpty() && !scopes.contains(defaultScope))
            throw new IllegalStateException("defaultScope " + defaultScope + " is not one of " + scopes);
        frozen = true;
        var widget = page.widget(title).type("crmConnections");
        if (!scopes.isEmpty()) widget.config("scopes", scopes.stream().map(CrmConnectionsView::key).collect(Collectors.joining(",")));
        if (!providers.isEmpty()) widget.config("providers", String.join(",", providers));
        if (defaultScope != null) widget.config("defaultScope", key(defaultScope));
        if (sharedLabel != null) widget.config("sharedLabel", sharedLabel);
        if (personalLabel != null) widget.config("personalLabel", personalLabel);
        if (showUnavailable != null) widget.config("showUnavailable", showUnavailable.toString());
    }

    private static String key(Scope scope) { return scope.name().toLowerCase(Locale.ROOT); }
    private void mutable() {
        if (frozen) throw new IllegalStateException("CrmConnectionsView is frozen after its first placement");
    }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Value must not be blank");
        return value;
    }
}
