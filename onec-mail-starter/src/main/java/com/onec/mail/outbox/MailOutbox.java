package com.onec.mail.outbox;

import com.onec.schema.SqlDialect;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Side-effect queue for outbound mail. Lives in its own table {@code onec_mail_outbox}
 * rather than the framework's domain-event outbox: mail is a command-to-execute, not an event-to-broadcast,
 * and giving it a separate table avoids racing with the Kafka relay over the shared {@code onec_outbox}.
 *
 * <p>Works on both supported engines: PostgreSQL gets the lock-free fast paths
 * ({@code ON CONFLICT}, {@code UPDATE ... RETURNING} with {@code SKIP LOCKED}); H2 — used by
 * single-node dev/demo apps — gets portable equivalents selected via {@link SqlDialect}.
 */
public class MailOutbox {

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS onec_mail_outbox (\n" +
                    "    _id UUID PRIMARY KEY,\n" +
                    "    _payload TEXT NOT NULL,\n" +
                    "    _provider VARCHAR(64),\n" +
                    "    _idempotency_key VARCHAR(200),\n" +
                    "    _attempts INT NOT NULL DEFAULT 0,\n" +
                    "    _last_error TEXT,\n" +
                    "    _created_at TIMESTAMP NOT NULL,\n" +
                    "    _dispatched_at TIMESTAMP,\n" +
                    "    _claimed_at TIMESTAMP,\n" +
                    "    _next_attempt_at TIMESTAMP NOT NULL,\n" +
                    "    _status VARCHAR(32) NOT NULL\n" +
                    ")";

    private static final String DDL_IDEMPOTENCY_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS onec_mail_outbox_idem " +
                    "ON onec_mail_outbox (_idempotency_key)";

    // Brings tables created before the claim-based relay up to date.
    private static final String DDL_CLAIMED_AT =
            "ALTER TABLE onec_mail_outbox ADD COLUMN IF NOT EXISTS _claimed_at TIMESTAMP";

    private final Jdbi jdbi;
    private volatile SqlDialect dialect;

    public MailOutbox(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    private SqlDialect dialect() {
        SqlDialect d = dialect;
        if (d == null) {
            d = jdbi.withHandle(h -> SqlDialect.detect(h.getConnection()));
            dialect = d;
        }
        return d;
    }

    public void initSchema() {
        jdbi.useHandle(h -> {
            h.execute(DDL);
            h.execute(DDL_CLAIMED_AT);
            h.execute(DDL_IDEMPOTENCY_INDEX);
        });
    }

    public UUID enqueue(String payload, String provider) {
        return enqueue(payload, provider, null);
    }

    /**
     * Enqueues a message. When {@code idempotencyKey} is non-blank and a row with that key already exists,
     * the existing id is returned and no new row is inserted, so retried application logic can't double-send.
     */
    public UUID enqueue(String payload, String provider, String idempotencyKey) {
        boolean hasKey = idempotencyKey != null && !idempotencyKey.isBlank();
        if (hasKey) {
            Optional<UUID> existing = findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        // PostgreSQL absorbs a concurrent same-key insert with ON CONFLICT DO NOTHING;
        // H2 has no ON CONFLICT, so the loser of that race surfaces as a unique-index
        // violation instead. Both end the same way: return the winner's id.
        String insert = "INSERT INTO onec_mail_outbox " +
                "(_id, _payload, _provider, _idempotency_key, _attempts, _created_at, _next_attempt_at, _status) " +
                "VALUES (:id, :payload, :provider, :key, 0, :now, :now, 'NEW')" +
                (dialect() == SqlDialect.POSTGRESQL ? " ON CONFLICT (_idempotency_key) DO NOTHING" : "");
        int inserted;
        try {
            inserted = jdbi.withHandle(h -> h.createUpdate(insert)
                    .bind("id", id)
                    .bind("payload", payload)
                    .bind("provider", provider)
                    .bind("key", hasKey ? idempotencyKey : null)
                    .bind("now", now)
                    .execute());
        } catch (UnableToExecuteStatementException e) {
            if (!hasKey) {
                throw e;
            }
            inserted = 0;
        }
        if (inserted == 0 && hasKey) {
            return findByIdempotencyKey(idempotencyKey).orElse(id);
        }
        return id;
    }

    private Optional<UUID> findByIdempotencyKey(String idempotencyKey) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT _id FROM onec_mail_outbox WHERE _idempotency_key = :key")
                .bind("key", idempotencyKey)
                .mapTo(UUID.class)
                .findFirst());
    }

    private static final String DUE_WHERE =
            "WHERE (_status = 'NEW' AND _next_attempt_at <= :now) " +
                    "   OR (_status = 'SENDING' AND _claimed_at < :staleBefore) " +
                    "  ORDER BY _created_at LIMIT :limit ";

    /**
     * Atomically claims up to {@code limit} due messages for this worker, flipping them {@code NEW -> SENDING}.
     * On PostgreSQL this is a single statement; {@code FOR UPDATE SKIP LOCKED} lets concurrent relays
     * (multiple app instances) grab disjoint batches instead of all selecting the same rows and sending
     * duplicates. On H2 — which has neither {@code UPDATE ... RETURNING} nor {@code SKIP LOCKED} — the claim
     * is a transactional {@code SELECT ... FOR UPDATE} followed by the status flip: concurrent claimers
     * serialise on the row locks rather than skipping, which is fine for the single-node setups H2 backs.
     * Rows stuck in {@code SENDING} longer than {@code leaseTimeout} — a worker that crashed mid-send —
     * are reclaimed on either engine.
     */
    public List<Pending> claimBatch(int limit, Duration leaseTimeout) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minus(leaseTimeout);
        if (dialect() == SqlDialect.POSTGRESQL) {
            return jdbi.withHandle(h -> h.createQuery(
                            "UPDATE onec_mail_outbox SET _status = 'SENDING', _claimed_at = :now " +
                                    "WHERE _id IN (" +
                                    "  SELECT _id FROM onec_mail_outbox " + DUE_WHERE +
                                    "  FOR UPDATE SKIP LOCKED" +
                                    ") " +
                                    "RETURNING _id, _payload, _attempts")
                    .bind("now", now)
                    .bind("staleBefore", staleBefore)
                    .bind("limit", limit)
                    .map((rs, ctx) -> new Pending(
                            (UUID) rs.getObject("_id"),
                            rs.getString("_payload"),
                            rs.getInt("_attempts")))
                    .list());
        }
        return jdbi.inTransaction(h -> {
            List<Pending> due = h.createQuery(
                            "SELECT _id, _payload, _attempts FROM onec_mail_outbox " + DUE_WHERE +
                                    "FOR UPDATE")
                    .bind("now", now)
                    .bind("staleBefore", staleBefore)
                    .bind("limit", limit)
                    .map((rs, ctx) -> new Pending(
                            (UUID) rs.getObject("_id"),
                            rs.getString("_payload"),
                            rs.getInt("_attempts")))
                    .list();
            if (due.isEmpty()) {
                return due;
            }
            h.createUpdate("UPDATE onec_mail_outbox SET _status = 'SENDING', _claimed_at = :now " +
                            "WHERE _id IN (<ids>)")
                    .bind("now", now)
                    .bindList("ids", due.stream().map(Pending::id).toList())
                    .execute();
            return due;
        });
    }

    public void markDispatched(UUID id) {
        jdbi.useHandle(h -> h.createUpdate(
                        "UPDATE onec_mail_outbox SET _status = 'SENT', _dispatched_at = :now " +
                                "WHERE _id = :id")
                .bind("id", id)
                .bind("now", LocalDateTime.now())
                .execute());
    }

    public void recordFailure(UUID id, int attempts, String error, LocalDateTime nextAttempt, boolean exhausted) {
        jdbi.useHandle(h -> h.createUpdate(
                        "UPDATE onec_mail_outbox SET _attempts = :attempts, _last_error = :err, " +
                                "_next_attempt_at = :next, _status = :status WHERE _id = :id")
                .bind("id", id)
                .bind("attempts", attempts)
                .bind("err", error == null ? "" : error)
                .bind("next", nextAttempt)
                .bind("status", exhausted ? "FAILED" : "NEW")
                .execute());
    }

    public record Pending(UUID id, String payload, int attempts) {
    }
}
