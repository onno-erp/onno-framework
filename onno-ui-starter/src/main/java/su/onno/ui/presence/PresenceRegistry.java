package su.onno.ui.presence;

import su.onno.cluster.ClusterEvent;
import su.onno.cluster.ClusterEventBus;
import su.onno.ui.UiEventPublisher;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory registry of who is currently viewing each record, for record-level collaboration markers
 * ("3 people are here", like the avatars in a shared document).
 *
 * <p>State is per-node and ephemeral: a {@code (entityType, entityName, id)} record maps to its set of
 * viewers, each carrying a {@code lastSeen} stamped from <em>this</em> node's clock. A viewer is kept
 * alive by heartbeats and expires by TTL when they stop arriving — so a closed tab or a crashed peer
 * self-heals without an explicit leave, and no cross-node clock agreement is needed.
 *
 * <p>Two inputs feed the same map:
 * <ul>
 *   <li><b>Local</b> — a browser on this node calls the presence endpoint ({@link #onLocal}); the change is
 *       applied, broadcast to peer nodes over the {@link ClusterEventBus}, and pushed to this node's SSE
 *       clients.</li>
 *   <li><b>Remote</b> — a peer relayed a {@link ClusterEvent.Presence} over the bus ({@link #onRemote}); it
 *       is applied and pushed to this node's SSE clients, but never re-broadcast (no relay loop).</li>
 * </ul>
 *
 * <p>An SSE snapshot is pushed only when a record's <em>viewer set</em> changes (a join or a leave), not on
 * every heartbeat, so liveness traffic stays off the browser stream. Like everything on the cluster bus
 * this is best-effort: a dropped ping costs at most one TTL of staleness.
 *
 * <p>All per-record mutations run inside the outer {@link ConcurrentHashMap#compute} callback, so the inner
 * plain maps are only ever touched while the bin lock is held — no separate synchronization is required,
 * and an emptied record is dropped atomically without the classic remove-empty-inner-map race.
 */
public class PresenceRegistry {

    /** Default viewer time-to-live: a viewer with no heartbeat for this long is dropped. ~3 missed beats. */
    public static final long DEFAULT_TTL_MILLIS = 45_000;
    /** Default interval between expiry sweeps. */
    public static final long DEFAULT_SWEEP_INTERVAL_MILLIS = 15_000;

    private record RecordKey(String entityType, String entityName, String id) {}
    private record Entry(String displayName, long lastSeen) {}

    private final Map<RecordKey, Map<String, Entry>> byRecord = new ConcurrentHashMap<>();

    private final ClusterEventBus bus;
    private final UiEventPublisher publisher;
    private final Clock clock;
    private final long ttlMillis;
    private final long sweepIntervalMillis;

    private ScheduledExecutorService sweeper;

    public PresenceRegistry(ClusterEventBus bus, UiEventPublisher publisher) {
        this(bus, publisher, Clock.systemUTC(), DEFAULT_TTL_MILLIS, DEFAULT_SWEEP_INTERVAL_MILLIS);
    }

    PresenceRegistry(ClusterEventBus bus, UiEventPublisher publisher, Clock clock,
                     long ttlMillis, long sweepIntervalMillis) {
        this.bus = bus;
        this.publisher = publisher;
        this.clock = clock;
        this.ttlMillis = ttlMillis;
        this.sweepIntervalMillis = sweepIntervalMillis;
    }

    @PostConstruct
    public void start() {
        bus.subscribe(event -> {
            if (event instanceof ClusterEvent.Presence presence) {
                onRemote(presence);
            }
        });
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "onno-ui-presence-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::sweepExpired,
                sweepIntervalMillis, sweepIntervalMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    /** A browser on this node entered/refreshed/left a record. Apply, relay to peers, fan out locally. */
    public void onLocal(String action, String entityType, String entityName, String id,
                        String userId, String displayName) {
        if (userId == null || id == null) {
            return; // can't track an anonymous viewer or a record without an id
        }
        RecordKey key = new RecordKey(entityType, entityName, id);
        boolean membershipChanged = apply(action, key, userId, displayName);
        bus.publish(ClusterEvent.presence(action, entityType, entityName, id, userId, displayName));
        if (membershipChanged) {
            publishSnapshot(key);
        }
    }

    /** A peer node relayed a presence change. Apply and fan out locally; never re-broadcast. */
    public void onRemote(ClusterEvent.Presence presence) {
        if (presence.userId() == null || presence.id() == null) {
            return;
        }
        RecordKey key = new RecordKey(presence.entityType(), presence.entityName(), presence.id());
        boolean membershipChanged = apply(presence.action(), key, presence.userId(), presence.displayName());
        if (membershipChanged) {
            publishSnapshot(key);
        }
    }

    /** Apply one change atomically per record. Returns whether the viewer set changed (a join or a leave). */
    private boolean apply(String action, RecordKey key, String userId, String displayName) {
        boolean[] changed = {false};
        if (ClusterEvent.Presence.LEAVE.equals(action)) {
            byRecord.compute(key, (k, users) -> {
                if (users == null) {
                    return null;
                }
                changed[0] = users.remove(userId) != null;
                return users.isEmpty() ? null : users;
            });
        } else {
            // ENTER or HEARTBEAT — upsert the viewer and refresh lastSeen.
            long now = clock.millis();
            byRecord.compute(key, (k, users) -> {
                Map<String, Entry> viewers = users == null ? new HashMap<>() : users;
                changed[0] = viewers.put(userId, new Entry(displayName, now)) == null;
                return viewers;
            });
        }
        return changed[0];
    }

    /** Remove viewers whose last heartbeat predates the TTL, pushing a fresh snapshot for each affected record. */
    public void sweepExpired() {
        long cutoff = clock.millis() - ttlMillis;
        List<RecordKey> changed = new ArrayList<>();
        for (RecordKey key : byRecord.keySet()) {
            byRecord.compute(key, (k, users) -> {
                if (users == null) {
                    return null;
                }
                if (users.values().removeIf(entry -> entry.lastSeen() < cutoff)) {
                    changed.add(k);
                }
                return users.isEmpty() ? null : users;
            });
        }
        for (RecordKey key : changed) {
            publishSnapshot(key);
        }
    }

    /** The current viewers of a record as {@code {userId, displayName}} maps — what the enter response returns. */
    public List<Map<String, String>> viewers(String entityType, String entityName, String id) {
        return viewers(new RecordKey(entityType, entityName, id));
    }

    private void publishSnapshot(RecordKey key) {
        // entityName + id scope the bar to its record; the SSE event's entityType is the "presence"
        // sentinel (set in publishPresence), not the record's kind, so no list/detail surface refetches.
        publisher.publishPresence(key.entityName(), key.id(), viewers(key));
    }

    private List<Map<String, String>> viewers(RecordKey key) {
        List<Map<String, String>> result = new ArrayList<>();
        byRecord.computeIfPresent(key, (k, users) -> {
            users.forEach((userId, entry) -> {
                Map<String, String> viewer = new LinkedHashMap<>();
                viewer.put("userId", userId);
                viewer.put("displayName", entry.displayName());
                result.add(viewer);
            });
            return users;
        });
        return result;
    }
}
