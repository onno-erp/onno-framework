package su.onno.auth.users;

import org.jdbi.v3.core.Jdbi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for one-time secrets: the {@code onno_auth_codes} table. Only a salted SHA-256 of each
 * secret is stored. Being in the database (not in process memory) makes expiry, the attempt budget,
 * single use and request rate limiting hold across every node of a scaled-out deployment.
 */
public class AuthCodeStore {

    static final String TABLE = "onno_auth_codes";

    private record Candidate(UUID id, String salt, String hash) {
    }

    private final Jdbi jdbi;

    public AuthCodeStore(Jdbi jdbi) {
        this.jdbi = jdbi;
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (\n"
                    + "    _id UUID PRIMARY KEY,\n"
                    + "    _login_key VARCHAR(256) NOT NULL,\n"
                    + "    _purpose VARCHAR(32) NOT NULL,\n"
                    + "    _salt VARCHAR(64) NOT NULL,\n"
                    + "    _code_hash VARCHAR(128) NOT NULL,\n"
                    + "    _attempts INT NOT NULL,\n"
                    + "    _created_at TIMESTAMP NOT NULL,\n"
                    + "    _expires_at TIMESTAMP NOT NULL,\n"
                    + "    _consumed_at TIMESTAMP\n"
                    + ")");
            h.execute("CREATE INDEX IF NOT EXISTS onno_auth_codes_login_idx ON " + TABLE
                    + " (_login_key, _purpose, _created_at)");
        });
    }

    /**
     * Stores a new secret for the login unless {@code maxRequests} were already issued within
     * {@code window}. Issuing supersedes every earlier unused secret of the same purpose, so only
     * the latest one works.
     *
     * @return false when the request was rate-limited and nothing was stored
     */
    public boolean issue(String loginKey, AuthCodePurpose purpose, String code, Instant now,
                         Duration validity, int maxRequests, Duration window) {
        return jdbi.inTransaction(h -> {
            // Rows are only needed while they can still be redeemed or count toward the window.
            Duration retention = window.compareTo(validity) > 0 ? window : validity;
            h.createUpdate("DELETE FROM " + TABLE + " WHERE _created_at < :cutoff")
                    .bind("cutoff", Timestamp.from(now.minus(retention))).execute();

            long recent = h.createQuery("SELECT COUNT(*) FROM " + TABLE
                            + " WHERE _login_key = :key AND _purpose = :purpose AND _created_at > :since")
                    .bind("key", loginKey).bind("purpose", purpose.name())
                    .bind("since", Timestamp.from(now.minus(window)))
                    .mapTo(Long.class).one();
            if (recent >= maxRequests) {
                return false;
            }
            h.createUpdate("UPDATE " + TABLE + " SET _consumed_at = :now"
                            + " WHERE _login_key = :key AND _purpose = :purpose AND _consumed_at IS NULL")
                    .bind("now", Timestamp.from(now)).bind("key", loginKey).bind("purpose", purpose.name())
                    .execute();
            String salt = UUID.randomUUID().toString();
            h.createUpdate("INSERT INTO " + TABLE
                            + " (_id, _login_key, _purpose, _salt, _code_hash, _attempts, _created_at, _expires_at)"
                            + " VALUES (:id, :key, :purpose, :salt, :hash, 0, :now, :expires)")
                    .bind("id", UUID.randomUUID()).bind("key", loginKey).bind("purpose", purpose.name())
                    .bind("salt", salt).bind("hash", hash(salt, code))
                    .bind("now", Timestamp.from(now)).bind("expires", Timestamp.from(now.plus(validity)))
                    .execute();
            return true;
        });
    }

    /**
     * Redeems a secret: true exactly once, for the latest unexpired, unused secret of that purpose,
     * and only while its attempt budget lasts. Every call — right or wrong — spends one attempt
     * <em>before</em> the comparison, so concurrent guesses cannot exceed {@code maxAttempts}.
     */
    public boolean redeem(String loginKey, AuthCodePurpose purpose, String code, Instant now, int maxAttempts) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return jdbi.inTransaction(h -> {
            Optional<Candidate> row = h.createQuery("SELECT _id, _salt, _code_hash FROM " + TABLE
                            + " WHERE _login_key = :key AND _purpose = :purpose AND _consumed_at IS NULL"
                            + " AND _expires_at > :now ORDER BY _created_at DESC LIMIT 1")
                    .bind("key", loginKey).bind("purpose", purpose.name()).bind("now", Timestamp.from(now))
                    .map((rs, ctx) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                    .findOne();
            if (row.isEmpty()) {
                return false;
            }
            UUID id = row.get().id();
            int spent = h.createUpdate("UPDATE " + TABLE + " SET _attempts = _attempts + 1"
                            + " WHERE _id = :id AND _attempts < :max AND _consumed_at IS NULL")
                    .bind("id", id).bind("max", maxAttempts).execute();
            if (spent == 0) {
                return false;
            }
            String expected = row.get().hash();
            String actual = hash(row.get().salt(), code.trim());
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    actual.getBytes(StandardCharsets.UTF_8))) {
                return false;
            }
            return h.createUpdate("UPDATE " + TABLE + " SET _consumed_at = :now"
                            + " WHERE _id = :id AND _consumed_at IS NULL")
                    .bind("now", Timestamp.from(now)).bind("id", id).execute() == 1;
        });
    }

    /** Voids every outstanding secret of the login (after a password change or when it is disabled). */
    public void invalidateAll(String loginKey, Instant now) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE " + TABLE + " SET _consumed_at = :now"
                        + " WHERE _login_key = :key AND _consumed_at IS NULL")
                .bind("now", Timestamp.from(now)).bind("key", loginKey).execute());
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((salt + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
