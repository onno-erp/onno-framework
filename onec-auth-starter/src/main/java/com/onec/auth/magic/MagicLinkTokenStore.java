package com.onec.auth.magic;

import java.time.Instant;
import java.util.Optional;

/**
 * Persists single-use magic-link tokens and consumes them atomically. The default implementation
 * ({@link JdbcMagicLinkTokenStore}) stores tokens in a database table so a link emailed by one node
 * validates on any node of a horizontally-scaled deployment; an application can replace it (e.g. with
 * a Redis-backed store) by registering its own {@code MagicLinkTokenStore} bean.
 *
 * <p>Only the <em>hash</em> of a token is ever stored — the raw token lives only in the email — so a
 * leak of the store cannot be replayed as a login. Consumption is single-use and expiry-checked in
 * one atomic step to close the replay/double-use window.
 */
public interface MagicLinkTokenStore {

    /**
     * Records a freshly-issued token.
     *
     * @param tokenHash hex-encoded SHA-256 of the raw token (never the raw token itself)
     * @param username  the account the token signs in
     * @param expiresAt the instant after which the token is no longer valid
     */
    void save(String tokenHash, String username, Instant expiresAt);

    /**
     * Atomically validates and consumes a token: succeeds only if a matching token exists, has not
     * already been consumed, and has not expired — and in the same step marks it consumed so it can
     * never be used again.
     *
     * @param tokenHash hex-encoded SHA-256 of the raw token presented by the caller
     * @param now       the current instant, for the expiry check
     * @return the username the token signs in, or empty if the token is unknown, already used, or expired
     */
    Optional<String> consume(String tokenHash, Instant now);

    /**
     * Deletes tokens that expired before {@code now}. Optional housekeeping — consumption already
     * rejects expired tokens, so this only reclaims space. Returns the number of rows removed.
     */
    default int purgeExpired(Instant now) {
        return 0;
    }
}
