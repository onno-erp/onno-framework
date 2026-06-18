package su.onno.ui;

import su.onno.cluster.ClusterEventBus;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receive side of cross-node live-UI sync: subscribes to the {@link ClusterEventBus} and pushes events
 * that originated on <em>other</em> nodes to this node's SSE clients via
 * {@link UiEventPublisher#publish(String, String, String, Object, String)}.
 *
 * <p>Crucially it calls the plain {@code publish(...)} sink, <strong>not</strong> a Spring
 * {@code ApplicationEventPublisher} — a received remote event is never re-emitted as an
 * {@link su.onno.events.EntityChangedEvent}. That keeps business {@code @EventListener}s (cache
 * revalidation, search indexing, outbox relays, post-hooks) firing exactly once, on the originating
 * node, while every node's browsers still see the change. With the default {@code NoOpClusterEventBus}
 * the subscription never fires, so this bridge is harmless on a single node.
 */
public class ClusterUiBridge {

    private static final Logger log = LoggerFactory.getLogger(ClusterUiBridge.class);

    private final ClusterEventBus bus;
    private final UiEventPublisher publisher;

    public ClusterUiBridge(ClusterEventBus bus, UiEventPublisher publisher) {
        this.bus = bus;
        this.publisher = publisher;
    }

    @PostConstruct
    void wire() {
        bus.subscribe(event -> publisher.publish(
                event.changeType(), event.entityType(), event.entityName(), event.id(), event.naturalKey()));
        if (bus.isDistributed()) {
            log.info("Cluster live-UI sync active: forwarding peer-node entity changes to local SSE clients.");
        }
    }
}
