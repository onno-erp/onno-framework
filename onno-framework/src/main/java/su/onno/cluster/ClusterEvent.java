package su.onno.cluster;

/**
 * Wire form of a cross-node notice carried by a {@link ClusterEventBus}.
 *
 * <p>It is a <strong>sealed family discriminated by {@link #kind()}</strong>: each permitted variant
 * is a distinct payload shape that shares the one bus channel without a wire break. {@link EntityChanged}
 * mirrors {@code su.onno.events.EntityChangedEvent}; future kinds add a sibling record and a new
 * {@code KIND_*} tag, and receivers switch on the type.
 *
 * <p>The variants are kept as plain strings with no Jackson annotations so the core module stays free of
 * {@code spring-context} and Jackson — the {@link ClusterEventBus} implementation owns the JSON encoding,
 * writing the {@link #kind()} tag explicitly so a receiver can pick the right variant (see
 * {@code PostgresClusterEventBus}).
 *
 * <p>{@link #originNodeId()} is the id of the node that published the event. A bus delivers an event back
 * to its own subscribers (Postgres {@code NOTIFY} echoes to the publisher's {@code LISTEN}), so receivers
 * drop events whose origin equals their own node id: the originating node already fanned the change out to
 * its local SSE clients synchronously.
 */
public sealed interface ClusterEvent {

    /** {@link #kind()} tag for an {@link EntityChanged} notice mirroring {@code EntityChangedEvent}. */
    String KIND_ENTITY_CHANGED = "entity-changed";

    /** {@link #kind()} tag for a {@link Presence} notice — a user entering/leaving a record's live view. */
    String KIND_PRESENCE = "presence";

    /** The payload-shape tag identifying which permitted variant this event is. */
    String kind();

    /**
     * The publishing node's id, used by receivers to filter out their own echoes. {@code null} until the
     * publishing {@link ClusterEventBus} stamps it via {@link #withOrigin(String)} on {@code publish}.
     */
    String originNodeId();

    /** A copy of this event with {@link #originNodeId()} set — used by a bus to stamp its identity on send. */
    ClusterEvent withOrigin(String originNodeId);

    /**
     * Build an {@link EntityChanged} event with no {@link #originNodeId()} set — the publishing
     * {@link ClusterEventBus} stamps that during {@link ClusterEventBus#publish}. This is the form the
     * change-relay constructs, so it never needs to know the local node id.
     */
    static EntityChanged entityChanged(String changeType, String entityType, String entityName,
                                       String id, String naturalKey) {
        return new EntityChanged(null, changeType, entityType, entityName, id, naturalKey);
    }

    /**
     * Build a {@link Presence} event with no {@link #originNodeId()} set — the publishing
     * {@link ClusterEventBus} stamps that during {@link ClusterEventBus#publish}.
     */
    static Presence presence(String action, String entityType, String entityName, String id,
                             String userId, String displayName) {
        return new Presence(null, action, entityType, entityName, id, userId, displayName);
    }

    /**
     * An entity create/update/delete/post notice mirroring the fields of
     * {@code su.onno.events.EntityChangedEvent}. A best-effort live-UI signal: a peer that misses one
     * re-fetches the affected surface on its next interaction.
     *
     * @param originNodeId the publishing node's id, or {@code null} before a bus stamps it.
     * @param changeType   {@code created}/{@code updated}/{@code deleted}/{@code posted}/{@code unposted}/{@code changed}.
     * @param entityType   {@code catalog}/{@code document}/{@code register}.
     * @param entityName   the entity's logical name, or {@code *} for a non-specific signal.
     * @param id           the affected row's id as a string, or {@code null}.
     * @param naturalKey   the business key (catalog code / document number), or {@code null}.
     */
    record EntityChanged(
            String originNodeId,
            String changeType,
            String entityType,
            String entityName,
            String id,
            String naturalKey) implements ClusterEvent {

        @Override
        public String kind() {
            return KIND_ENTITY_CHANGED;
        }

        @Override
        public EntityChanged withOrigin(String originNodeId) {
            return new EntityChanged(originNodeId, changeType, entityType, entityName, id, naturalKey);
        }
    }

    /**
     * A user-presence notice for record-level collaboration markers: one user has entered, refreshed, or
     * left the live view of a specific record. Receivers mirror the {@code (entityType, entityName, id)}
     * record's viewer set into their own presence registry so a viewer on any node sees who else is here.
     *
     * <p>It carries no timestamp: a receiver stamps {@code lastSeen} from its own clock on receipt, so a
     * stale entry expires by local TTL without depending on cross-node clock agreement. {@link #ENTER} and
     * {@link #HEARTBEAT} are both an upsert (refresh the viewer); {@link #LEAVE} removes them. Like every
     * event on this bus it is best-effort — a missed ping self-heals on the next heartbeat or by TTL.
     *
     * @param originNodeId the publishing node's id, or {@code null} before a bus stamps it.
     * @param action       {@link #ENTER}, {@link #HEARTBEAT}, or {@link #LEAVE}.
     * @param entityType   the viewed record's entity type ({@code catalog}/{@code document}).
     * @param entityName   the viewed record's logical name.
     * @param id           the viewed record's id as a string.
     * @param userId       a stable id for the viewing user (their domain record id, or username).
     * @param displayName  the viewing user's display name, for rendering the avatar/marker.
     */
    record Presence(
            String originNodeId,
            String action,
            String entityType,
            String entityName,
            String id,
            String userId,
            String displayName) implements ClusterEvent {

        /** A user opened the record's live view. Treated as an upsert by the registry. */
        public static final String ENTER = "enter";
        /** A periodic liveness refresh from a user still on the record. Treated as an upsert. */
        public static final String HEARTBEAT = "heartbeat";
        /** A user left the record's live view. Removes them from the registry. */
        public static final String LEAVE = "leave";

        @Override
        public String kind() {
            return KIND_PRESENCE;
        }

        @Override
        public Presence withOrigin(String originNodeId) {
            return new Presence(originNodeId, action, entityType, entityName, id, userId, displayName);
        }
    }
}
