package su.onno.auth.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link UserStore}: one framework-managed table, {@code onno_auth_user}, created on
 * startup if missing. It lives outside the business model on purpose — the password hash must never
 * be reachable through the generic catalog REST, MCP, import or export surfaces.
 */
public class JdbcUserStore implements UserStore {

    static final String TABLE = "onno_auth_user";

    private static final String COLUMNS = "id, username, roles, enabled, password_change_required, "
            + "created_at, password_changed_at, last_login_at, security_stamp, password_hash";

    private final JdbcTemplate jdbc;

    public JdbcUserStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id UUID PRIMARY KEY,"
                + "username VARCHAR(320) NOT NULL,"
                + "username_key VARCHAR(320) NOT NULL UNIQUE,"
                + "password_hash VARCHAR(255),"
                + "roles VARCHAR(2000) NOT NULL,"
                + "enabled BOOLEAN NOT NULL,"
                + "password_change_required BOOLEAN NOT NULL,"
                + "created_at TIMESTAMP NOT NULL,"
                + "password_changed_at TIMESTAMP,"
                + "last_login_at TIMESTAMP,"
                + "security_stamp BIGINT NOT NULL)");
    }

    static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public Optional<Credentials> findCredentials(String username) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE username_key = ?",
                        CREDENTIALS, key(username))
                .stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?", CREDENTIALS, id)
                .stream().findFirst().map(Credentials::account);
    }

    @Override
    public List<UserAccount> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " ORDER BY username_key", CREDENTIALS)
                .stream().map(Credentials::account).toList();
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public void insert(UserAccount a, String passwordHash) {
        jdbc.update("INSERT INTO " + TABLE + " (id, username, username_key, password_hash, roles, enabled, "
                        + "password_change_required, created_at, password_changed_at, last_login_at, security_stamp) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                a.id(), a.username().trim(), key(a.username()), passwordHash, joinRoles(a.roles()), a.enabled(),
                a.passwordChangeRequired(), ts(a.createdAt()), ts(a.passwordChangedAt()), ts(a.lastLoginAt()),
                a.securityStamp());
    }

    @Override
    public void update(UserAccount a) {
        jdbc.update("UPDATE " + TABLE + " SET roles = ?, enabled = ?, password_change_required = ?, "
                        + "security_stamp = ? WHERE id = ?",
                joinRoles(a.roles()), a.enabled(), a.passwordChangeRequired(), a.securityStamp(), a.id());
    }

    @Override
    public void updatePassword(UUID id, String passwordHash, boolean changeRequired, Instant changedAt,
                               long securityStamp) {
        jdbc.update("UPDATE " + TABLE + " SET password_hash = ?, password_change_required = ?, "
                        + "password_changed_at = ?, security_stamp = ? WHERE id = ?",
                passwordHash, changeRequired, ts(changedAt), securityStamp, id);
    }

    @Override
    public void recordLogin(UUID id, Instant at) {
        jdbc.update("UPDATE " + TABLE + " SET last_login_at = ? WHERE id = ?", ts(at), id);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String joinRoles(Set<String> roles) {
        return roles.stream().sorted().collect(Collectors.joining(","));
    }

    private static Set<String> splitRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static final RowMapper<Credentials> CREDENTIALS = (rs, row) -> new Credentials(
            new UserAccount(
                    rs.getObject("id", UUID.class),
                    rs.getString("username"),
                    splitRoles(rs.getString("roles")),
                    rs.getBoolean("enabled"),
                    rs.getBoolean("password_change_required"),
                    instant(rs.getTimestamp("created_at")),
                    instant(rs.getTimestamp("password_changed_at")),
                    instant(rs.getTimestamp("last_login_at")),
                    rs.getLong("security_stamp")),
            rs.getString("password_hash"));
}
