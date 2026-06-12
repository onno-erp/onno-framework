package com.onec.mail.outbox;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine-independent behaviour contract for {@link MailOutbox}: the H2 test and the
 * PostgreSQL integration test both extend this, so the portable claim path and the
 * {@code SKIP LOCKED}/{@code ON CONFLICT} fast path must satisfy identical assertions.
 */
abstract class MailOutboxContract {

    protected static final Duration LEASE = Duration.ofMinutes(5);

    protected Jdbi jdbi;
    protected MailOutbox outbox;

    protected abstract Jdbi createJdbi();

    @BeforeEach
    void setUpOutbox() {
        jdbi = createJdbi();
        outbox = new MailOutbox(jdbi);
        outbox.initSchema();
        jdbi.useHandle(h -> h.execute("DELETE FROM onec_mail_outbox"));
    }

    @Test
    void enqueueIsIdempotentByKey() {
        UUID first = outbox.enqueue("{\"subject\":\"a\"}", "test", "order-42");
        UUID second = outbox.enqueue("{\"subject\":\"b\"}", "test", "order-42");

        assertThat(second).isEqualTo(first);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void enqueueWithoutKeyAlwaysInserts() {
        outbox.enqueue("{}", "test");
        outbox.enqueue("{}", "test");

        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void claimBatchFlipsDueRowsToSendingExactlyOnce() {
        UUID id = outbox.enqueue("{}", "test");

        List<MailOutbox.Pending> claimed = outbox.claimBatch(10, LEASE);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).id()).isEqualTo(id);
        assertThat(status(id)).isEqualTo("SENDING");
        // Already claimed and within its lease: a second claimer gets nothing.
        assertThat(outbox.claimBatch(10, LEASE)).isEmpty();
    }

    @Test
    void claimBatchRespectsLimit() {
        outbox.enqueue("{}", "test");
        outbox.enqueue("{}", "test");
        outbox.enqueue("{}", "test");

        assertThat(outbox.claimBatch(2, LEASE)).hasSize(2);
        assertThat(outbox.claimBatch(2, LEASE)).hasSize(1);
    }

    @Test
    void failedMessageWaitsForItsBackoffWindow() {
        UUID id = outbox.enqueue("{}", "test");
        outbox.claimBatch(10, LEASE);

        outbox.recordFailure(id, 1, "smtp down", LocalDateTime.now().plusMinutes(1), false);
        assertThat(outbox.claimBatch(10, LEASE)).isEmpty();

        outbox.recordFailure(id, 1, "smtp down", LocalDateTime.now().minusSeconds(1), false);
        List<MailOutbox.Pending> reclaimed = outbox.claimBatch(10, LEASE);
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).attempts()).isEqualTo(1);
    }

    @Test
    void exhaustedMessageIsNeverClaimedAgain() {
        UUID id = outbox.enqueue("{}", "test");
        outbox.claimBatch(10, LEASE);

        outbox.recordFailure(id, 5, "smtp down", LocalDateTime.now().minusSeconds(1), true);

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(outbox.claimBatch(10, LEASE)).isEmpty();
    }

    @Test
    void staleSendingRowIsReclaimedAfterLeaseExpiry() {
        UUID id = outbox.enqueue("{}", "test");
        outbox.claimBatch(10, LEASE);

        // Simulate a worker that died mid-send: the claim is older than the lease.
        jdbi.useHandle(h -> h.createUpdate(
                        "UPDATE onec_mail_outbox SET _claimed_at = :stale WHERE _id = :id")
                .bind("stale", LocalDateTime.now().minusMinutes(10))
                .bind("id", id)
                .execute());

        List<MailOutbox.Pending> reclaimed = outbox.claimBatch(10, Duration.ofMinutes(1));
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).id()).isEqualTo(id);
    }

    private int rowCount() {
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM onec_mail_outbox")
                .mapTo(Integer.class).one());
    }

    private String status(UUID id) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT _status FROM onec_mail_outbox WHERE _id = :id")
                .bind("id", id).mapTo(String.class).one());
    }
}
