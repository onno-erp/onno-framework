package su.onno.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One recorded event on a record: a write (with its field changes) or a lifecycle transition.
 *
 * @param id         the change id grouping this event's rows
 * @param entityType {@code catalog} or {@code document}
 * @param entityName the entity's logical name when the event was recorded
 * @param recordId   the record the event belongs to
 * @param event      one of the {@link RecordHistory} event constants
 * @param at         when it happened
 * @param actor      who did it (login, or {@code system})
 * @param actorId    the actor's identity record id, if any
 * @param changes    the changed values; empty for a bare transition such as a post
 */
public record HistoryEntry(UUID id, String entityType, String entityName, UUID recordId, String event,
                           Instant at, String actor, UUID actorId, List<HistoryChange> changes) {
}
