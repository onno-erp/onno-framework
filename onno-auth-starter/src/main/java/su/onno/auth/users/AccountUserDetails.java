package su.onno.auth.users;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.UUID;

/**
 * The Spring Security principal for a {@link UserAccount}. Carries the account id and the
 * {@linkplain UserAccount#securityStamp() security stamp} it was issued under, which
 * {@link AccountSessionGuardFilter} compares against the store on every API request.
 */
public class AccountUserDetails extends User {

    private final UUID accountId;
    private final long securityStamp;
    private final boolean passwordChangeRequired;

    public AccountUserDetails(UserAccount account, String passwordHash) {
        super(account.username(), passwordHash == null ? "" : passwordHash, account.enabled(),
                true, true, true,
                account.roles().stream()
                        .map(su.onno.access.AccessSubject::normalizeRole)
                        .filter(r -> !r.isEmpty())
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        this.accountId = account.id();
        this.securityStamp = account.securityStamp();
        this.passwordChangeRequired = account.passwordChangeRequired();
    }

    public UUID accountId() {
        return accountId;
    }

    public long securityStamp() {
        return securityStamp;
    }

    public boolean passwordChangeRequired() {
        return passwordChangeRequired;
    }
}
