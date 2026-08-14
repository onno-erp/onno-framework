package su.onno.messaging;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class OutboxWriter {

    private final Jdbi jdbi;

    public OutboxWriter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public UUID append(String aggregateType, String aggregateId, String eventType, String payload) {
        return jdbi.withHandle(handle ->
                append(handle, aggregateType, aggregateId, eventType, payload));
    }

    /**
     * Appends an event using the caller's transaction.
     *
     * @param handle the transaction-bound handle that owns the aggregate mutation
     * @param aggregateType the aggregate's stable type
     * @param aggregateId the aggregate identifier
     * @param eventType the domain event type
     * @param payload the serialized event payload
     * @return the new outbox event identifier
     */
    public UUID append(Handle handle, String aggregateType, String aggregateId,
                       String eventType, String payload) {
        UUID id = UUID.randomUUID();
        handle.createUpdate(
                "INSERT INTO onno_outbox " +
                        "(_id, _aggregate_type, _aggregate_id, _event_type, _payload, " +
                        "_created_at, _status) " +
                        "VALUES (:id, :aggregateType, :aggregateId, :eventType, :payload, " +
                        ":createdAt, 'NEW')")
                .bind("id", id)
                .bind("aggregateType", aggregateType)
                .bind("aggregateId", aggregateId)
                .bind("eventType", eventType)
                .bind("payload", payload)
                .bind("createdAt", LocalDateTime.now())
                .execute();
        return id;
    }

    public List<OutboxEvent> findPending(int limit) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT * FROM onno_outbox WHERE _status = 'NEW' " +
                                "ORDER BY _created_at LIMIT :limit")
                .bind("limit", limit)
                .map((rs, ctx) -> new OutboxEvent(
                        (UUID) rs.getObject("_id"),
                        rs.getString("_aggregate_type"),
                        rs.getString("_aggregate_id"),
                        rs.getString("_event_type"),
                        rs.getString("_payload"),
                        rs.getTimestamp("_created_at").toLocalDateTime(),
                        rs.getTimestamp("_published_at") == null
                                ? null : rs.getTimestamp("_published_at").toLocalDateTime(),
                        rs.getString("_status")))
                .list());
    }

    public void markPublished(UUID id) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE onno_outbox SET _status = 'PUBLISHED', _published_at = :publishedAt " +
                                "WHERE _id = :id")
                .bind("id", id)
                .bind("publishedAt", LocalDateTime.now())
                .execute());
    }
}
