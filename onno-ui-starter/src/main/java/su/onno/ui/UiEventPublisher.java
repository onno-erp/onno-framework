package su.onno.ui;

import su.onno.events.EntityChangedEvent;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes entity-change notifications to browser {@link SseEmitter}s for live UI updates. It is one
 * listener of the framework's {@link EntityChangedEvent} — the single funnel both write paths (the
 * generic controllers and {@code repository.save}) publish to (issues #28, #29) — so the live stream
 * reflects programmatic saves too, not just back-office edits.
 *
 * <p><strong>Per-subscriber RBAC (#190).</strong> Each stream is keyed by the read-authorities its
 * viewer held at subscribe time, and an event is delivered only when those roles grant <em>read</em>
 * access to the event's entity — so a viewer never receives change- or presence-notifications for
 * entities their role can't read. Roles are captured up front because the fan-out runs off the request
 * thread (the event-publishing thread, or the {@link ClusterUiBridge} relay for peer-node events),
 * where {@code SecurityContextHolder} no longer holds the subscriber's authentication; see
 * {@link UiAccessService#canReceiveEvent(java.util.Set, String, String)}.
 */
public class UiEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UiEventPublisher.class);

    /**
     * How often to write a keepalive comment to each open stream. The browser, and any
     * proxy in between, will silently drop a connection that sits idle for long enough;
     * a ping well under the usual idle thresholds keeps the long-lived stream healthy and
     * lets us prune emitters whose socket has already gone away.
     */
    private static final long KEEPALIVE_SECONDS = 20;

    /** An open stream plus the read-authorities its viewer held when it was opened. */
    private record Subscriber(SseEmitter emitter, Set<String> roles) {}

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final UiAccessService access;
    private final ScheduledExecutorService keepalive =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "onno-ui-events-keepalive");
                t.setDaemon(true);
                return t;
            });

    public UiEventPublisher(UiAccessService access) {
        this.access = access;
        keepalive.scheduleWithFixedDelay(this::ping,
                KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Open a stream for a viewer holding {@code roles} (capture them with
     * {@link UiAccessService#roles(java.security.Principal)} on the request thread). The role set is
     * snapshotted: every event is filtered against it for the life of the connection.
     */
    public SseEmitter subscribe(Set<String> roles) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter, roles == null ? Set.of() : Set.copyOf(roles));
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(error -> subscribers.remove(subscriber));
        // The "ready" ack carries no entity data, so it is sent unconditionally to the new stream.
        send(subscriber, "ready", Map.of("type", "ready", "timestamp", Instant.now().toString()));
        return emitter;
    }

    /**
     * Fans an {@link EntityChangedEvent} out to every open SSE stream whose viewer may read it.
     * Registered as a Spring {@code @EventListener}, so anything that publishes the event (both write
     * paths) reaches the browser — no direct coupling to the controllers.
     */
    @EventListener
    public void onEntityChanged(EntityChangedEvent event) {
        publish(event.changeType(), event.entityType(), event.entityName(), event.id(), event.naturalKey());
    }

    public void publish(String type, String entityType, String entityName, Object id) {
        publish(type, entityType, entityName, id, null);
    }

    public void publish(String type, String entityType, String entityName, Object id, String naturalKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("entityType", entityType);
        payload.put("entityName", entityName);
        payload.put("id", id == null ? null : id.toString());
        payload.put("naturalKey", naturalKey);
        payload.put("timestamp", Instant.now().toString());

        for (Subscriber subscriber : subscribers) {
            if (access.canReceiveEvent(subscriber.roles(), entityType, entityName)) {
                send(subscriber, type, payload);
            }
        }
    }

    /**
     * Fans the current viewer set of one record out as a {@code presence} event, to every open stream
     * whose viewer may read that record — record-level collaboration markers. Each viewer is a
     * {@code {userId, displayName}} map. Sent only when a record's viewer set changes (a join or a
     * leave), never on a bare heartbeat.
     *
     * <p>Its {@code entityType} is the distinct sentinel {@code "presence"}, <strong>not</strong> the
     * record's {@code catalog}/{@code document} kind — exactly as comment events use {@code "comment"} —
     * so the list/detail/dashboard surfaces, which refetch on a row change to their entity, never mistake
     * a presence ping for one. The record's route {@code kind} ({@code catalogs}/{@code documents}) and
     * {@code entityName} ride alongside so the ambient-presence store can map a viewed record to its nav
     * item and list rows; the marker surfaces match on {@code id} (globally unique). The read check uses
     * that {@code kind}/{@code entityName} (the {@code presence} sentinel is not a real entity type), so a
     * viewer is never told that someone is viewing a record in an entity they can't open (#190).
     */
    public void publishPresence(String kind, String entityName, String id, List<Map<String, String>> viewers) {
        String entityType = entityTypeForKind(kind);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "presence");
        payload.put("entityType", "presence");
        payload.put("kind", kind);
        payload.put("entityName", entityName);
        payload.put("id", id);
        payload.put("viewers", viewers);
        payload.put("timestamp", Instant.now().toString());

        for (Subscriber subscriber : subscribers) {
            if (access.canRead(subscriber.roles(), entityType, entityName)) {
                send(subscriber, "presence", payload);
            }
        }
    }

    /** Map a presence route {@code kind} ("catalogs"/"documents") to the access-check entity type. */
    private static String entityTypeForKind(String kind) {
        return switch (kind == null ? "" : kind) {
            case "catalogs" -> "catalog";
            case "documents" -> "document";
            default -> "";
        };
    }

    /**
     * Write a comment line ({@code : keepalive}) to every open stream. SSE comments carry
     * no event and the client parser ignores them, so this never surfaces as a UI event —
     * it just keeps the socket warm. A write that throws means the peer is gone, so we drop
     * the subscriber.
     */
    private void ping() {
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.emitter().send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(subscriber);
                subscriber.emitter().completeWithError(e);
                log.debug("Dropped dead SSE stream on keepalive ({} remaining): {}",
                        subscribers.size(), e.getMessage());
            }
        }
    }

    private void send(Subscriber subscriber, String name, Object payload) {
        try {
            subscriber.emitter().send(SseEmitter.event().name(name).data(payload));
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(subscriber);
            subscriber.emitter().completeWithError(e);
            log.debug("Dropped dead SSE stream on send ({} remaining): {}",
                    subscribers.size(), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        keepalive.shutdownNow();
    }
}
