package su.onno.ui.notifications;

import su.onno.cluster.ClusterEvent;
import su.onno.cluster.ClusterEventBus;
import su.onno.ui.UiEventPublisher;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The framework's notification hub and public producer API. It persists a notification to the shared
 * {@link NotificationStore}, pushes it to the recipient's live SSE streams on this node, and relays it
 * over the {@link ClusterEventBus} so streams the recipient holds on peer nodes light up too.
 *
 * <p>Two inputs feed the same delivery, mirroring {@link su.onno.ui.presence.PresenceRegistry}:
 * <ul>
 *   <li><b>Local</b> — an app or a built-in producer calls {@link #notify}; the row is stored, pushed to
 *       this node's recipient streams, and broadcast to peers.</li>
 *   <li><b>Remote</b> — a peer relayed a {@link ClusterEvent.Notification} ({@link #onRemote}); the durable
 *       copy already exists in the shared table, so the event only wakes this node's recipient streams and
 *       is never re-stored or re-broadcast (no relay loop).</li>
 * </ul>
 *
 * <p>A daily sweep prunes read notifications older than {@link NotificationProperties#getRetentionDays()};
 * unread ones are kept indefinitely.
 */
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final long SWEEP_INTERVAL_HOURS = 24;

    private final NotificationStore store;
    private final UiEventPublisher publisher;
    private final ClusterEventBus bus;
    private final NotificationProperties properties;

    private ScheduledExecutorService sweeper;

    public NotificationService(NotificationStore store, UiEventPublisher publisher, ClusterEventBus bus,
                               NotificationProperties properties) {
        this.store = store;
        this.publisher = publisher;
        this.bus = bus;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        bus.subscribe(event -> {
            if (event instanceof ClusterEvent.Notification n) {
                onRemote(n);
            }
        });
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "onno-ui-notifications-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::prune, SWEEP_INTERVAL_HOURS, SWEEP_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    @PreDestroy
    public void stop() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    /**
     * Raise a notification: persist it, push it to the recipient's live streams here, and relay it to
     * peer nodes. A request with no recipient or no title is a silent no-op, so producers need not guard.
     * Returns the stored notification, or {@code null} when the request was not deliverable.
     */
    public Notification notify(NotificationRequest req) {
        if (req == null || !req.valid()) {
            return null;
        }
        Notification stored = store.insert(req.recipientId(), req.type(), req.title(), req.body(),
                req.link(), req.actorId(), req.actorName(), req.actorAvatar());
        publisher.publishNotification(stored.recipientId(), payload(stored));
        bus.publish(ClusterEvent.notification(stored.recipientId(), stored.id().toString(), stored.type(),
                stored.title(), stored.body(), stored.link(), stored.actorName()));
        return stored;
    }

    /** A peer node raised a notification whose recipient may be connected here — wake their streams only. */
    public void onRemote(ClusterEvent.Notification event) {
        if (event.recipientId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "notification");
        payload.put("entityType", "notification");
        payload.put("id", event.notificationId());
        payload.put("notificationType", event.type());
        // A peer event trimmed to fit the cluster payload cap arrives without its display fields; the
        // client treats a title-less notification event as "something arrived, refetch the feed".
        payload.put("title", event.title());
        payload.put("body", event.body());
        payload.put("link", event.link());
        payload.put("actorName", event.actorName());
        payload.put("unread", true);
        payload.put("timestamp", Instant.now().toString());
        publisher.publishNotification(event.recipientId(), payload);
    }

    // ---- read/mutate surface used by NotificationController -------------------------------------

    public NotificationStore.Page list(String recipientId, boolean unreadOnly, String cursor) {
        return store.list(recipientId, unreadOnly, cursor, properties.getPageSize());
    }

    public int unreadCount(String recipientId) {
        return store.unreadCount(recipientId);
    }

    public boolean markRead(UUID id, String recipientId) {
        return store.markRead(id, recipientId);
    }

    public int markAllRead(String recipientId) {
        return store.markAllRead(recipientId);
    }

    /** The SSE wire shape the client's notification store consumes for a locally-raised notification. */
    static Map<String, Object> payload(Notification n) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "notification");
        payload.put("entityType", "notification");
        payload.put("id", n.id().toString());
        payload.put("notificationType", n.type());
        payload.put("title", n.title());
        payload.put("body", n.body());
        payload.put("link", n.link());
        payload.put("actorId", n.actorId());
        payload.put("actorName", n.actorName());
        payload.put("actorAvatar", n.actorAvatar());
        payload.put("createdAt", n.createdAt() == null ? null : n.createdAt().toString());
        payload.put("unread", true);
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    private void prune() {
        int days = properties.getRetentionDays();
        if (days <= 0) {
            return; // retention disabled — keep read history forever
        }
        try {
            int removed = store.pruneReadBefore(Instant.now().minus(days, ChronoUnit.DAYS));
            if (removed > 0) {
                log.debug("onno-notifications: pruned {} read notifications older than {} days", removed, days);
            }
        } catch (RuntimeException e) {
            log.debug("onno-notifications: retention sweep failed: {}", e.toString());
        }
    }
}
