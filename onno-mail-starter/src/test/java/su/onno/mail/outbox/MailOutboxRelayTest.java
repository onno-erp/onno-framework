package su.onno.mail.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.mail.MailMessage;
import su.onno.mail.MailProperties;
import su.onno.mail.dispatch.MailDispatcher;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relay-logic tests against an in-memory outbox double. {@link MailOutbox} itself uses
 * PostgreSQL-only SQL ({@code ON CONFLICT}, {@code UPDATE ... RETURNING},
 * {@code FOR UPDATE SKIP LOCKED}), so its persistence belongs in a Postgres
 * integration test; what's covered here is the relay's claim → dispatch → mark /
 * failure → backoff → exhausted behaviour.
 */
class MailOutboxRelayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MailProperties properties = new MailProperties();
    private final RecordingDispatcher dispatcher = new RecordingDispatcher();
    private final InMemoryMailOutbox outbox = new InMemoryMailOutbox();

    private static final class RecordingDispatcher implements MailDispatcher {
        final List<MailMessage> dispatched = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void dispatch(MailMessage message) {
            if (failWith != null) throw failWith;
            dispatched.add(message);
        }
    }

    private static final class InMemoryMailOutbox extends MailOutbox {
        record Row(UUID id, String payload, int attempts, String status, LocalDateTime nextAttempt) {
        }

        final Map<UUID, Row> rows = new LinkedHashMap<>();

        InMemoryMailOutbox() {
            super(null);
        }

        UUID add(String payload) {
            UUID id = UUID.randomUUID();
            rows.put(id, new Row(id, payload, 0, "NEW", LocalDateTime.now()));
            return id;
        }

        @Override
        public List<Pending> claimBatch(int limit, Duration leaseTimeout) {
            LocalDateTime now = LocalDateTime.now();
            return rows.values().stream()
                    .filter(r -> r.status().equals("NEW") && !r.nextAttempt().isAfter(now))
                    .limit(limit)
                    .map(r -> new Pending(r.id(), r.payload(), r.attempts()))
                    .toList();
        }

        @Override
        public void markDispatched(UUID id) {
            Row r = rows.get(id);
            rows.put(id, new Row(r.id(), r.payload(), r.attempts(), "SENT", r.nextAttempt()));
        }

        @Override
        public void recordFailure(UUID id, int attempts, String error, LocalDateTime nextAttempt,
                                  boolean exhausted) {
            rows.put(id, new Row(id, rows.get(id).payload(), attempts,
                    exhausted ? "FAILED" : "NEW", nextAttempt));
        }
    }

    private MailOutboxRelay relay() {
        return new MailOutboxRelay(outbox, dispatcher, objectMapper, properties);
    }

    private UUID enqueue() throws Exception {
        MailMessage msg = MailMessage.builder()
                .from("noreply@example.com")
                .to("user@example.com")
                .subject("Hello")
                .text("Hi there")
                .build();
        return outbox.add(objectMapper.writeValueAsString(msg));
    }

    @Test
    void dispatchesPendingMailAndMarksItSent() throws Exception {
        UUID id = enqueue();

        assertThat(relay().relayPending()).isEqualTo(1);

        assertThat(dispatcher.dispatched).hasSize(1);
        assertThat(dispatcher.dispatched.get(0).subject()).isEqualTo("Hello");
        assertThat(outbox.rows.get(id).status()).isEqualTo("SENT");
    }

    @Test
    void failureSchedulesRetryWithBackoff() throws Exception {
        UUID id = enqueue();
        dispatcher.failWith = new RuntimeException("smtp down");

        assertThat(relay().relayPending()).isZero();

        InMemoryMailOutbox.Row row = outbox.rows.get(id);
        assertThat(row.status()).isEqualTo("NEW");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.nextAttempt()).isAfter(LocalDateTime.now());

        // Not due yet — a second relay run must not re-attempt the message.
        assertThat(relay().relayPending()).isZero();
        assertThat(outbox.rows.get(id).attempts()).isEqualTo(1);
    }

    @Test
    void exhaustedMessageIsMarkedFailedAndNeverRetried() throws Exception {
        properties.getRelay().setMaxAttempts(1);
        UUID id = enqueue();
        dispatcher.failWith = new RuntimeException("smtp down");

        assertThat(relay().relayPending()).isZero();
        assertThat(outbox.rows.get(id).status()).isEqualTo("FAILED");

        dispatcher.failWith = null;
        assertThat(relay().relayPending()).isZero();
        assertThat(dispatcher.dispatched).isEmpty();
    }

    @Test
    void undeserializablePayloadIsRetiredViaFailurePathNotCrash() {
        UUID id = outbox.add("this is not json");
        properties.getRelay().setMaxAttempts(1);

        assertThat(relay().relayPending()).isZero();
        assertThat(outbox.rows.get(id).status()).isEqualTo("FAILED");
    }
}
