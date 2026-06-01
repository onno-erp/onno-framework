package com.onec.kafka;

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDateTime;

/**
 * Idempotency ledger for inbound CloudEvents. Kafka delivers at-least-once, so the same event id
 * may arrive more than once; the inbox records each id and lets the consumer skip duplicates.
 *
 * <p>The CloudEvent {@code id} is stored as text rather than UUID so events from external,
 * non-onec producers (whose ids may be arbitrary strings) interoperate without translation.
 */
public class Inbox {

    private final Jdbi jdbi;

    public Inbox(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void initSchema() {
        jdbi.useHandle(handle -> handle.execute(
                "CREATE TABLE IF NOT EXISTS onec_inbox (" +
                        "_id VARCHAR(255) PRIMARY KEY, " +
                        "_source VARCHAR(255), " +
                        "_event_type VARCHAR(255), " +
                        "_received_at TIMESTAMP NOT NULL, " +
                        "_processed_at TIMESTAMP, " +
                        "_status VARCHAR(32) NOT NULL, " +
                        "_error VARCHAR(1000))"));
    }

    /**
     * Records the event as RECEIVED if its id has not been seen before.
     *
     * @return {@code true} if this is the first sighting (caller should process it),
     *         {@code false} if it is a duplicate (caller should skip).
     */
    public boolean markReceivedIfNew(String id, String eventType, String source) {
        return jdbi.inTransaction(handle -> {
            boolean exists = handle.createQuery("SELECT 1 FROM onec_inbox WHERE _id = :id")
                    .bind("id", id)
                    .mapTo(Integer.class)
                    .findFirst()
                    .isPresent();
            if (exists) {
                return false;
            }
            handle.createUpdate(
                            "INSERT INTO onec_inbox (_id, _source, _event_type, _received_at, _status) " +
                                    "VALUES (:id, :source, :eventType, :receivedAt, 'RECEIVED')")
                    .bind("id", id)
                    .bind("source", source)
                    .bind("eventType", eventType)
                    .bind("receivedAt", LocalDateTime.now())
                    .execute();
            return true;
        });
    }

    public void markProcessed(String id) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE onec_inbox SET _status = 'PROCESSED', _processed_at = :processedAt " +
                                "WHERE _id = :id")
                .bind("id", id)
                .bind("processedAt", LocalDateTime.now())
                .execute());
    }

    public void markFailed(String id, String error) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE onec_inbox SET _status = 'FAILED', _error = :error WHERE _id = :id")
                .bind("id", id)
                .bind("error", error)
                .execute());
    }
}
