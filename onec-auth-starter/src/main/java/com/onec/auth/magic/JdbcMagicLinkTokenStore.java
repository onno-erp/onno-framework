package com.onec.auth.magic;

import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Database-backed {@link MagicLinkTokenStore}. Tokens live in {@code onec_auth_magic_link}, keyed by
 * the token hash, so a link issued by one application node is verifiable on any other — the property
 * a horizontally-scaled deployment needs. Portable across the two supported engines (H2 for
 * single-node dev, PostgreSQL in production); the conditional {@code UPDATE} used for consumption is
 * standard SQL and needs no dialect branch.
 *
 * <p>Timestamps are stored in UTC ({@link Instant} mapped to a UTC {@link LocalDateTime}) so expiry
 * comparisons are independent of the JVM's default time zone.
 */
public class JdbcMagicLinkTokenStore implements MagicLinkTokenStore {

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS onec_auth_magic_link (\n" +
                    "    _token_hash VARCHAR(64) PRIMARY KEY,\n" +
                    "    _username VARCHAR(200) NOT NULL,\n" +
                    "    _expires_at TIMESTAMP NOT NULL,\n" +
                    "    _consumed_at TIMESTAMP,\n" +
                    "    _created_at TIMESTAMP NOT NULL\n" +
                    ")";

    private final Jdbi jdbi;

    public JdbcMagicLinkTokenStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void initSchema() {
        jdbi.useHandle(h -> h.execute(DDL));
    }

    @Override
    public void save(String tokenHash, String username, Instant expiresAt) {
        LocalDateTime now = utc(Instant.now());
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO onec_auth_magic_link "
                                + "(_token_hash, _username, _expires_at, _created_at) "
                                + "VALUES (:hash, :username, :expires, :now)")
                .bind("hash", tokenHash)
                .bind("username", username)
                .bind("expires", utc(expiresAt))
                .bind("now", now)
                .execute());
    }

    @Override
    public Optional<String> consume(String tokenHash, Instant now) {
        LocalDateTime ts = utc(now);
        // Flip unconsumed→consumed only while still valid, in one statement: the row count tells us
        // whether THIS call won the race. A second concurrent verify (or a replay) updates zero rows
        // and gets nothing back, so a token can be redeemed at most once.
        return jdbi.inTransaction(h -> {
            int updated = h.createUpdate(
                            "UPDATE onec_auth_magic_link SET _consumed_at = :now "
                                    + "WHERE _token_hash = :hash AND _consumed_at IS NULL AND _expires_at > :now")
                    .bind("hash", tokenHash)
                    .bind("now", ts)
                    .execute();
            if (updated == 0) {
                return Optional.<String>empty();
            }
            return h.createQuery("SELECT _username FROM onec_auth_magic_link WHERE _token_hash = :hash")
                    .bind("hash", tokenHash)
                    .mapTo(String.class)
                    .findFirst();
        });
    }

    @Override
    public int purgeExpired(Instant now) {
        return jdbi.withHandle(h -> h.createUpdate(
                        "DELETE FROM onec_auth_magic_link WHERE _expires_at <= :now")
                .bind("now", utc(now))
                .execute());
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
