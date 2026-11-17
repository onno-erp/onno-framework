package su.onno.crm.service;

import jakarta.annotation.PostConstruct;
import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import su.onno.web.EventPublisher;

/**
 * Durable ownership of connected provider accounts, separate from provider credentials.
 *
 * <p>Personal ownership is never client supplied: the owner is the authenticated principal's name
 * (the authentication subject, which OIDC keeps stable across email or display-name changes). A
 * personal account's inbox is private to its owner. {@link CrmInboxWorkspaceService} is the single
 * place that applies that rule to conversation reads, through {@link #hiddenInboxes} and
 * {@link #isHidden}.</p>
 *
 * <p>Like the channel adapters' state tables, the table is internal module state created on
 * startup rather than a modelled catalog, so it never appears on generic entity surfaces.</p>
 */
public class CrmConnectionAccounts {

    /** Who an account belongs to. */
    public enum Scope {
        /** Managed by administrators; conversations follow inbox workspace permissions. */
        SHARED,
        /** Owned by one user; its inbox is invisible to everyone else, administrators included. */
        PERSONAL;

        /** Parse client input, answering 400 rather than 500 for an unknown value. */
        public static Scope parse(String value) {
            if (value != null) {
                try {
                    return valueOf(value.strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // reported below as a client error
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose shared or personal ownership");
        }
    }

    public record Account(UUID id, String provider, Scope scope, String owner, String label, UUID inboxId) {
        public boolean personal() { return scope == Scope.PERSONAL; }
        public boolean ownedBy(Principal principal) { return principal != null && owner.equals(principal.getName()); }
    }

    private static final String COLUMNS = "id,provider,scope,owner_name,label,inbox_id";
    private static final int MAX_OWNER = 320;
    private static final int MAX_LABEL = 120;

    private final JdbcTemplate jdbc;
    private final CrmChannelAccess access;
    private final CrmFeatures features;
    private final ObjectProvider<EventPublisher> events;

    public CrmConnectionAccounts(JdbcTemplate jdbc, CrmChannelAccess access, CrmFeatures features) {
        this(jdbc, access, features, null);
    }

    public CrmConnectionAccounts(JdbcTemplate jdbc, CrmChannelAccess access, CrmFeatures features,
                                 ObjectProvider<EventPublisher> events) {
        this.jdbc = jdbc;
        this.access = access;
        this.features = java.util.Objects.requireNonNull(features, "features");
        this.events = events;
    }

    /** Whether this deployment allows personal accounts at all ({@link CrmFeatures#personalAccounts()}). */
    public boolean personalAccountsEnabled() {
        return features.personalAccounts();
    }

    /** May this principal add or manage personal accounts? Off unless the feature is enabled. */
    public boolean canManagePersonal(Principal principal) {
        return principal != null && features.personalAccounts() && access.canManagePersonal(principal);
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_connection_accounts (id UUID PRIMARY KEY, "
                + "provider VARCHAR(100) NOT NULL, scope VARCHAR(20) NOT NULL, owner_name VARCHAR(320) NOT NULL, "
                + "label VARCHAR(120) NOT NULL, inbox_id UUID UNIQUE)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS onno_crm_connection_accounts_owner "
                + "ON onno_crm_connection_accounts (scope, owner_name)");
    }

    /** Tell open connection pages to reload. */
    public void changed() {
        var publisher = events == null ? null : events.getIfAvailable();
        if (publisher != null) publisher.publish("updated", "page", "crm-connections", null);
    }

    public List<Account> visible(Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return query("WHERE scope=? OR (scope=? AND owner_name=?) ORDER BY label,id",
                Scope.SHARED.name(), Scope.PERSONAL.name(), principal.getName());
    }

    public Account find(UUID id) {
        return lookup(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The account with this id, if any, without any access check: for providers, not request paths. */
    public java.util.Optional<Account> lookup(UUID id) {
        return id == null ? java.util.Optional.empty() : query("WHERE id=?", id).stream().findFirst();
    }

    /**
     * Shared accounts: {@link CrmChannelAccess#canManage}. Personal accounts: only the owner, and the
     * owner always keeps control (rename, disconnect, pause) of an account they connected, even if
     * their role or the feature changes later, so a mailbox can never keep syncing out of reach.
     */
    public boolean canManage(Account account, Principal principal) {
        if (principal == null) return false;
        return account.personal() ? account.ownedBy(principal) : access.canManage(principal);
    }

    /** The account, if this principal may manage it; another user's personal account reads as absent. */
    public Account require(UUID id, Principal principal) {
        Account account = find(id);
        if (account.personal() && !account.ownedBy(principal)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!canManage(account, principal))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account management access required");
        return account;
    }

    public Account create(String provider, Scope scope, String label, Principal principal) {
        if (scope == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose shared or personal ownership");
        boolean allowed = principal != null
                && (scope == Scope.SHARED ? access.canManage(principal) : canManagePersonal(principal));
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String owner = scope == Scope.PERSONAL ? principal.getName() : "";
        if (owner.length() > MAX_OWNER) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account identity is too long");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO onno_crm_connection_accounts (" + COLUMNS + ") VALUES (?,?,?,?,?,NULL)",
                id, provider, scope.name(), owner, label(label));
        return find(id);
    }

    public Account rename(UUID id, String label, Principal principal) {
        require(id, principal);
        jdbc.update("UPDATE onno_crm_connection_accounts SET label=? WHERE id=?", label(label), id);
        return find(id);
    }

    /** Remove an account that never connected; connected accounts keep their history. */
    public void removeDraft(UUID id, Principal principal) {
        require(id, principal);
        if (jdbc.update("DELETE FROM onno_crm_connection_accounts WHERE id=? AND inbox_id IS NULL", id) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connected accounts keep their history; disconnect the account instead");
    }

    /**
     * Called by a provider once authorization created the account's inbox. Re-binding the same inbox
     * is a no-op; an inbox already owned by another account is rejected.
     */
    public void bindInbox(UUID id, UUID inbox) {
        var owners = jdbc.queryForList("SELECT id FROM onno_crm_connection_accounts WHERE inbox_id=?", UUID.class, inbox);
        if (owners.contains(id)) return;
        if (!owners.isEmpty()) throw new IllegalStateException("This inbox already belongs to another connected account");
        jdbc.update("UPDATE onno_crm_connection_accounts SET inbox_id=? WHERE id=?", inbox, id);
    }

    /** Inboxes of other users' personal accounts: invisible to this principal, administrators included. */
    public Set<UUID> hiddenInboxes(Principal principal) {
        return new HashSet<>(jdbc.query(
                "SELECT inbox_id FROM onno_crm_connection_accounts WHERE scope=? AND owner_name<>? AND inbox_id IS NOT NULL",
                (rs, n) -> rs.getObject(1, UUID.class), Scope.PERSONAL.name(), name(principal)));
    }

    /** Single-inbox form of {@link #hiddenInboxes}: one indexed lookup instead of loading the set. */
    public boolean isHidden(UUID inbox, Principal principal) {
        if (inbox == null) return false;
        Integer hits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM onno_crm_connection_accounts WHERE inbox_id=? AND scope=? AND owner_name<>?",
                Integer.class, inbox, Scope.PERSONAL.name(), name(principal));
        return hits != null && hits > 0;
    }

    private List<Account> query(String suffix, Object... args) {
        return jdbc.query("SELECT " + COLUMNS + " FROM onno_crm_connection_accounts " + suffix,
                (rs, n) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), Scope.valueOf(rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class)), args);
    }

    private static String name(Principal principal) {
        return principal == null ? "" : principal.getName();
    }

    private static String label(String value) {
        String label = value == null ? "" : value.strip();
        if (label.isEmpty() || label.length() > MAX_LABEL)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use an account name of 1–120 characters");
        return label;
    }
}
