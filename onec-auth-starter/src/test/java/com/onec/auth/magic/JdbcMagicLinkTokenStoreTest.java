package com.onec.auth.magic;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMagicLinkTokenStoreTest {

    /** A fresh H2 in-memory database per test; DB_CLOSE_DELAY keeps the schema across connections. */
    private static JdbcMagicLinkTokenStore store(String name) {
        JdbcMagicLinkTokenStore store = new JdbcMagicLinkTokenStore(
                Jdbi.create("jdbc:h2:mem:ml-" + name + ";DB_CLOSE_DELAY=-1"));
        store.initSchema();
        return store;
    }

    @Test
    void savesThenConsumesExactlyOnce() {
        JdbcMagicLinkTokenStore store = store("once");
        Instant now = Instant.now();
        store.save("hash-1", "alice", now.plus(15, ChronoUnit.MINUTES));

        assertThat(store.consume("hash-1", now)).contains("alice");
        // A replay (or a concurrent second verify) finds nothing — single use.
        assertThat(store.consume("hash-1", now)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        JdbcMagicLinkTokenStore store = store("expired");
        Instant now = Instant.now();
        store.save("hash-2", "bob", now.minus(1, ChronoUnit.MINUTES));

        assertThat(store.consume("hash-2", now)).isEmpty();
    }

    @Test
    void unknownTokenIsEmpty() {
        assertThat(store("unknown").consume("does-not-exist", Instant.now())).isEmpty();
    }

    @Test
    void purgeRemovesOnlyExpiredTokens() {
        JdbcMagicLinkTokenStore store = store("purge");
        Instant now = Instant.now();
        store.save("old", "a", now.minus(5, ChronoUnit.MINUTES));
        store.save("fresh", "b", now.plus(5, ChronoUnit.MINUTES));

        assertThat(store.purgeExpired(now)).isEqualTo(1);
        assertThat(store.consume("fresh", now)).contains("b");
    }
}
