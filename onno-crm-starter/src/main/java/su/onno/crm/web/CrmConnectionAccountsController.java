package su.onno.crm.web;

import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.service.*;
import su.onno.crm.service.CrmConnectionAccounts.Account;
import su.onno.crm.service.CrmConnectionAccounts.Scope;

/**
 * Shared and personal provider accounts: listing, onboarding and provider-owned commands.
 *
 * <p>{@code rename} and {@code removeDraft} are handled here; every other action is forwarded to
 * the account's {@link CrmAccountProvider} when its view offers it, so providers must not use those
 * two names for their own actions.</p>
 */
@RestController
@RequestMapping("/api/crm/connection-accounts")
public class CrmConnectionAccountsController {
    private static final Logger log = LoggerFactory.getLogger(CrmConnectionAccountsController.class);

    private final CrmConnectionAccounts accounts;
    private final List<CrmAccountProvider> providers;
    private final CrmChannelAccess access;
    private final CrmInboxWorkspaceService workspaces;

    public CrmConnectionAccountsController(CrmConnectionAccounts accounts, List<CrmAccountProvider> providers,
                                           CrmChannelAccess access, CrmInboxWorkspaceService workspaces) {
        this.accounts = accounts;
        this.providers = List.copyOf(providers);
        this.access = access;
        this.workspaces = workspaces;
        Set<String> keys = new HashSet<>();
        for (var provider : this.providers) {
            if (!keys.add(provider.descriptor().key()))
                throw new IllegalArgumentException("Duplicate connection provider key: " + provider.descriptor().key());
        }
    }

    public record Create(String provider, String scope, String label) {}
    public record Command(String action, String label) {}

    /** {@code GET} response. Descriptors only offer {@code PERSONAL} when the deployment enables it. */
    public record Listing(List<CrmAccountProvider.Descriptor> providers, List<AccountView> accounts,
                          boolean canManageShared, boolean canManagePersonal) {}
    public record AccountView(UUID id, String provider, Scope scope, String label, boolean canRemove,
                              CrmChannelConnection.View connection, boolean canManage) {}
    public record Removed(boolean removed) {}

    @GetMapping
    public Listing list(Principal p) {
        read(p);
        return new Listing(
                providers.stream().map(this::offered).toList(),
                accounts.visible(p).stream().map(a -> view(a, p)).toList(),
                access.canManage(p),
                canPersonal(p));
    }

    @PostMapping
    public AccountView create(@RequestBody Create request, Principal p) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        read(p);
        Scope scope = Scope.parse(request.scope());
        if (scope == Scope.PERSONAL && !canPersonal(p))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Personal accounts require inbox write access");
        var descriptor = offered(provider(request.provider()));
        if (!descriptor.enabled() || !descriptor.supports(scope))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This provider is not available for that account scope");
        var result = view(accounts.create(request.provider(), scope, request.label(), p), p);
        accounts.changed();
        return result;
    }

    /**
     * Manage an existing account. Owners keep control of their personal accounts even after losing
     * inbox access, so they can always disconnect a mailbox; see {@link CrmConnectionAccounts#canManage}.
     */
    @PostMapping("/{id}")
    public Object command(@PathVariable UUID id, @RequestBody Command request, Principal p) {
        if (request == null || request.action() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (p == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var account = accounts.require(id, p);
        switch (request.action()) {
            case "removeDraft" -> {
                accounts.removeDraft(id, p);
                accounts.changed();
                return new Removed(true);
            }
            case "rename" -> {
                var result = view(accounts.rename(id, request.label(), p), p);
                accounts.changed();
                return result;
            }
            default -> {
                var provider = provider(account.provider());
                if (!provider.view(account).actions().contains(request.action()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "This account action is unavailable");
                runProviderCommand(provider, account, request.action());
                accounts.changed();
                return view(accounts.find(id), p);
            }
        }
    }

    /**
     * Provider failures can quote upstream responses that carry tokens or addresses, so the client
     * gets a generic message and the server log keeps the cause. Only provider calls are wrapped:
     * a bug elsewhere in this controller still surfaces as a 500.
     */
    private void runProviderCommand(CrmAccountProvider provider, Account account, String action) {
        try {
            provider.command(account, action);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            log.warn("Connection account {} ({}) action '{}' failed: {}", account.id(), account.provider(), action,
                    failure.getClass().getSimpleName());
            log.debug("Connection account action failure", failure);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not update this account. Check its setup and try again.");
        }
    }

    private void read(Principal p) {
        if (p == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!access.canManage(p)) workspaces.requireAccess(p, false);
    }

    /** Adding a personal account needs the feature, the host's personal policy, and inbox write access. */
    private boolean canPersonal(Principal p) {
        return accounts.canManagePersonal(p)
                && (access.canManage(p) || workspaces.available(p).stream().anyMatch(w -> workspaces.permitted(w, p, true)));
    }

    /** The descriptor as this deployment offers it: without {@code PERSONAL} unless the feature is on. */
    private CrmAccountProvider.Descriptor offered(CrmAccountProvider provider) {
        var d = provider.descriptor();
        if (accounts.personalAccountsEnabled() || !d.supports(Scope.PERSONAL)) return d;
        var scopes = d.scopes().stream().filter(scope -> scope != Scope.PERSONAL).toList();
        return new CrmAccountProvider.Descriptor(d.key(), d.label(), d.channel(), d.iconUrl(), d.description(),
                d.enabled(), scopes, d.setupHint());
    }

    private CrmAccountProvider provider(String key) {
        return providers.stream().filter(p -> p.descriptor().key().equals(key)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not installed"));
    }

    private AccountView view(Account a, Principal p) {
        var connection = providers.stream().filter(c -> c.descriptor().key().equals(a.provider())).findFirst()
                .map(c -> c.view(a))
                .orElse(new CrmChannelConnection.View(a.id().toString(), a.provider(), a.provider(), "UNAVAILABLE", "",
                        "Provider is not installed", List.of()));
        return new AccountView(a.id(), a.provider(), a.scope(), a.label(), a.inboxId() == null, connection,
                accounts.canManage(a, p));
    }
}
