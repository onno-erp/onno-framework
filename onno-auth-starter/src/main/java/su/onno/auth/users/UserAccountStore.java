package su.onno.auth.users;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence SPI for database-mode accounts. The default {@link JdbcUserAccountStore} keeps them in
 * the framework-managed {@code onno_auth_users} table; register your own bean to keep them somewhere
 * else (an existing user table, a directory service).
 *
 * <p>Login lookups are case-insensitive: {@code Anna@Clinic.example} and {@code anna@clinic.example}
 * are the same account.
 */
public interface UserAccountStore {

    Optional<UserAccount> findByLogin(String login);

    Optional<UserAccount> findById(UUID id);

    /** Every account, ordered by login. */
    List<UserAccount> findAll();

    /**
     * Inserts a new account.
     *
     * @throws DuplicateLoginException when the login is already taken
     */
    void insert(UserAccount account);

    /** Replaces the mutable fields (password hash, enabled, roles) of an existing account. */
    void update(UserAccount account);
}
