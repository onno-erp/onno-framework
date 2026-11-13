package su.onno.auth.users;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence SPI for {@code onno.auth.mode=database} accounts. The starter auto-configures
 * {@link JdbcUserStore} over the application's {@code DataSource}; declare your own
 * {@code UserStore} bean to keep accounts elsewhere (an existing users table, a directory service).
 *
 * <p>Usernames are matched case-insensitively. Implementations store the hash they are given and
 * never hash or compare passwords themselves — {@link UserAccounts} owns that.
 */
public interface UserStore {

    /** The account and its password hash, or empty when no account has that username. */
    Optional<Credentials> findCredentials(String username);

    Optional<UserAccount> findById(UUID id);

    default Optional<UserAccount> findByUsername(String username) {
        return findCredentials(username).map(Credentials::account);
    }

    /** All accounts, ordered by username. */
    List<UserAccount> findAll();

    long count();

    /**
     * Inserts a new account.
     *
     * @throws org.springframework.dao.DuplicateKeyException if the username is already taken
     */
    void insert(UserAccount account, String passwordHash);

    /** Replaces the mutable fields (roles, enabled, change-required, security stamp) of an account. */
    void update(UserAccount account);

    /** Stores a new password hash and its metadata on the account, replacing the security stamp. */
    void updatePassword(UUID id, String passwordHash, boolean changeRequired, Instant changedAt, long securityStamp);

    void recordLogin(UUID id, Instant at);

    /**
     * An account together with its stored password hash.
     *
     * @param account      the account
     * @param passwordHash the encoded password; null when no password was ever set
     */
    record Credentials(UserAccount account, String passwordHash) {
    }
}
