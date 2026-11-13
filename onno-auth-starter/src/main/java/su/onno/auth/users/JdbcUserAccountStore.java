package su.onno.auth.users;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Default {@link UserAccountStore}: the {@code onno_auth_users} table. Like the framework's other
 * infrastructure tables it is created with a plain {@code CREATE TABLE IF NOT EXISTS} at startup
 * and is never modelled, migrated by the schema diff, or served by the generic API.
 */
public class JdbcUserAccountStore implements UserAccountStore {

    static final String TABLE = "onno_auth_users";

    private static final String COLUMNS = "_id, _login, _password_hash, _enabled, _roles, _created_at";

    private static final RowMapper<UserAccount> MAPPER = (rs, ctx) -> new UserAccount(
            rs.getObject("_id", UUID.class),
            rs.getString("_login"),
            rs.getString("_password_hash"),
            rs.getBoolean("_enabled"),
            parseRoles(rs.getString("_roles")),
            rs.getTimestamp("_created_at").toInstant());

    private final Jdbi jdbi;

    public JdbcUserAccountStore(Jdbi jdbi) {
        this.jdbi = jdbi;
        jdbi.useHandle(h -> h.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (\n"
                + "    _id UUID PRIMARY KEY,\n"
                + "    _login VARCHAR(256) NOT NULL,\n"
                + "    _login_key VARCHAR(256) NOT NULL UNIQUE,\n"
                + "    _password_hash VARCHAR(512),\n"
                + "    _enabled BOOLEAN NOT NULL,\n"
                + "    _roles VARCHAR(4000) NOT NULL,\n"
                + "    _created_at TIMESTAMP NOT NULL\n"
                + ")"));
    }

    /** The case-insensitive lookup key of a login. */
    static String key(String login) {
        return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public Optional<UserAccount> findByLogin(String login) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE _login_key = :key")
                .bind("key", key(login)).map(MAPPER).findOne());
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE _id = :id")
                .bind("id", id).map(MAPPER).findOne());
    }

    @Override
    public List<UserAccount> findAll() {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " ORDER BY _login_key")
                .map(MAPPER).list());
    }

    @Override
    public void insert(UserAccount account) {
        jdbi.useTransaction(h -> {
            boolean taken = h.createQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE _login_key = :key")
                    .bind("key", key(account.login())).mapTo(Long.class).one() > 0;
            if (taken) {
                throw new DuplicateLoginException(account.login());
            }
            h.createUpdate("INSERT INTO " + TABLE
                            + " (_id, _login, _login_key, _password_hash, _enabled, _roles, _created_at)"
                            + " VALUES (:id, :login, :key, :hash, :enabled, :roles, :createdAt)")
                    .bind("id", account.id())
                    .bind("login", account.login())
                    .bind("key", key(account.login()))
                    .bind("hash", account.passwordHash())
                    .bind("enabled", account.enabled())
                    .bind("roles", String.join(",", account.roles()))
                    .bind("createdAt", Timestamp.from(account.createdAt()))
                    .execute();
        });
    }

    @Override
    public void update(UserAccount account) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE " + TABLE
                        + " SET _password_hash = :hash, _enabled = :enabled, _roles = :roles WHERE _id = :id")
                .bind("id", account.id())
                .bind("hash", account.passwordHash())
                .bind("enabled", account.enabled())
                .bind("roles", String.join(",", account.roles()))
                .execute());
    }

    private static Set<String> parseRoles(String stored) {
        Set<String> roles = new LinkedHashSet<>();
        if (stored != null) {
            Arrays.stream(stored.split(",")).map(String::trim).filter(r -> !r.isEmpty()).forEach(roles::add);
        }
        return roles;
    }
}
