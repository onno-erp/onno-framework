package su.onno.auth.users;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * One database-backed login account ({@code onno.auth.mode=database}).
 *
 * <p>The account holds credentials and authorization only. It is linked to the application's person
 * record the same way every other auth mode is: {@code Layout.identity(Employee.class, "email")}
 * matches the {@link #login} against that catalog attribute, so business data stays in the host
 * catalog and the password hash never travels through the generic read API, exports or MCP.
 *
 * @param id           stable account id, used by the admin API
 * @param login        the principal name, as entered when the account was created
 * @param passwordHash encoded password, or {@code null} while the account has no password yet
 *                     (it can then only sign in after a password reset or with a one-time code)
 * @param enabled      a disabled account cannot sign in and its live sessions are ended
 * @param roles        role names without the {@code ROLE_} prefix (e.g. {@code ADMIN})
 * @param createdAt    when the account was created
 */
public record UserAccount(UUID id, String login, String passwordHash, boolean enabled,
                          Set<String> roles, Instant createdAt) {

    public UserAccount {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /** Whether a password has been set. */
    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    UserAccount withPasswordHash(String hash) {
        return new UserAccount(id, login, hash, enabled, roles, createdAt);
    }

    UserAccount withEnabled(boolean value) {
        return new UserAccount(id, login, passwordHash, value, roles, createdAt);
    }

    UserAccount withRoles(Set<String> value) {
        return new UserAccount(id, login, passwordHash, enabled, value, createdAt);
    }
}
