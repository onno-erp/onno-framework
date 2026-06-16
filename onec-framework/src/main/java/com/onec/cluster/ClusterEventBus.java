package com.onec.cluster;

import java.util.function.Consumer;

/**
 * Pluggable SPI for delivering {@link ClusterEvent}s across the nodes of a horizontally-scaled
 * deployment — the mechanism that keeps live-UI updates in sync when more than one instance runs
 * behind a load balancer.
 *
 * <p>It is swappable exactly like {@code com.onec.ui.media.MediaStorage}: the framework wires a
 * {@link NoOpClusterEventBus} when nothing better is available, {@code onec-cluster-starter} supplies
 * a Postgres {@code LISTEN}/{@code NOTIFY} default on a Postgres datasource, and a commercial build
 * can replace either by exposing its own {@code ClusterEventBus} bean (e.g. Kafka- or Redis-backed),
 * which the auto-configuration's {@code @ConditionalOnMissingBean} default then steps aside for.
 *
 * <p><strong>This is a best-effort live-UI signal, not a guaranteed-delivery event log.</strong> An
 * event dropped during a reconnect is acceptable — the browser re-fetches the affected surface on its
 * next interaction. Durable, exactly-once domain-event distribution is the job of the outbox relay
 * ({@code com.onec.messaging.OutboxWriter} + {@code onec-kafka-starter}), not this bus.
 */
public interface ClusterEventBus {

    /**
     * Publish a change to peer nodes. Fan-out to the publishing node's own SSE clients has already
     * happened synchronously on the local event path, so an implementation that echoes the event back
     * to its own subscribers must let them filter it by {@link ClusterEvent#originNodeId()}.
     *
     * <p>The implementation stamps the event's {@link ClusterEvent#originNodeId()} with its own node id
     * before sending (see {@link ClusterEvent#withOrigin}); callers may leave that field {@code null}
     * (as {@link ClusterEvent#entityChanged} does).
     */
    void publish(ClusterEvent event);

    /**
     * Register a sink invoked for events that originated on <em>other</em> nodes. A bus must not invoke
     * the sink for the local node's own events (see {@link ClusterEvent#originNodeId()}). May be called
     * more than once to attach multiple sinks.
     */
    void subscribe(Consumer<ClusterEvent> sink);

    /**
     * Whether this bus actually crosses node boundaries. {@code false} for {@link NoOpClusterEventBus}
     * (single-node / non-Postgres dev), {@code true} for a real distributed implementation. Useful for
     * diagnostics and for callers that want to log the active topology at startup.
     */
    default boolean isDistributed() {
        return true;
    }
}
