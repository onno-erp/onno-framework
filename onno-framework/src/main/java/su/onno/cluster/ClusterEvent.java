package su.onno.cluster;

/**
 * Wire form of a cross-node change notice carried by a {@link ClusterEventBus}.
 *
 * <p>It mirrors the fields of {@code su.onno.events.EntityChangedEvent} (kept as plain strings so
 * the record is trivial to serialize and the core module stays free of {@code spring-context} and
 * Jackson annotations), plus two transport-only fields:
 *
 * <ul>
 *   <li>{@link #kind} — a tag identifying the payload shape, so a future event kind can share the
 *       same channel without a wire break. Today the only value is {@link #KIND_ENTITY_CHANGED}.</li>
 *   <li>{@link #originNodeId} — the id of the node that published the event. A bus delivers an event
 *       back to its own subscribers (Postgres {@code NOTIFY} echoes to the publisher's {@code LISTEN}),
 *       so receivers drop events whose origin equals their own node id: the originating node already
 *       fanned the change out to its local SSE clients synchronously.</li>
 * </ul>
 *
 * @param kind         payload shape tag; see {@link #KIND_ENTITY_CHANGED}.
 * @param originNodeId the publishing node's id, used by receivers to filter out their own echoes.
 * @param changeType   {@code created}/{@code updated}/{@code deleted}/{@code posted}/{@code unposted}/{@code changed}.
 * @param entityType   {@code catalog}/{@code document}/{@code register}.
 * @param entityName   the entity's logical name, or {@code *} for a non-specific signal.
 * @param id           the affected row's id as a string, or {@code null}.
 * @param naturalKey   the business key (catalog code / document number), or {@code null}.
 */
public record ClusterEvent(
        String kind,
        String originNodeId,
        String changeType,
        String entityType,
        String entityName,
        String id,
        String naturalKey) {

    /** {@link #kind} value for an entity-change notice mirroring {@code EntityChangedEvent}. */
    public static final String KIND_ENTITY_CHANGED = "entity-changed";

    /**
     * Build an {@link #KIND_ENTITY_CHANGED} event with no {@link #originNodeId} set — the publishing
     * {@link ClusterEventBus} stamps that during {@link ClusterEventBus#publish}. This is the form the
     * change-relay constructs, so it never needs to know the local node id.
     */
    public static ClusterEvent entityChanged(String changeType, String entityType, String entityName,
                                             String id, String naturalKey) {
        return new ClusterEvent(KIND_ENTITY_CHANGED, null, changeType, entityType, entityName, id, naturalKey);
    }

    /** A copy of this event with {@link #originNodeId} set — used by a bus to stamp its identity on send. */
    public ClusterEvent withOrigin(String originNodeId) {
        return new ClusterEvent(kind, originNodeId, changeType, entityType, entityName, id, naturalKey);
    }
}
