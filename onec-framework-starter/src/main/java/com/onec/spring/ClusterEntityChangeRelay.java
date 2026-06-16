package com.onec.spring;

import com.onec.cluster.ClusterEvent;
import com.onec.cluster.ClusterEventBus;
import com.onec.events.EntityChangedEvent;

import org.springframework.context.event.EventListener;

/**
 * Publish side of cross-node live-UI sync: forwards every locally-originated {@link EntityChangedEvent}
 * onto the {@link ClusterEventBus} so peer nodes can fan it out to their own SSE clients.
 *
 * <p>It listens for the same {@link EntityChangedEvent} every write path already emits (the generic
 * controllers and {@code repository.save}/{@code delete}), so one relay covers all of them. Events that
 * arrived <em>from</em> other nodes are delivered straight to the local SSE publisher (see the UI
 * starter's cluster bridge) and are never re-published as a Spring event, so this listener only ever
 * sees local originals — there is no relay loop. With the default {@code NoOpClusterEventBus} the
 * {@code publish} call is inert, so single-node behaviour is unchanged.
 */
public class ClusterEntityChangeRelay {

    private final ClusterEventBus bus;

    public ClusterEntityChangeRelay(ClusterEventBus bus) {
        this.bus = bus;
    }

    @EventListener
    public void onEntityChanged(EntityChangedEvent event) {
        bus.publish(ClusterEvent.entityChanged(
                event.changeType(),
                event.entityType(),
                event.entityName(),
                event.id() == null ? null : event.id().toString(),
                event.naturalKey()));
    }
}
