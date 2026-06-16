package com.onec.cluster;

import java.util.function.Consumer;

/**
 * Local-only {@link ClusterEventBus} used when no cross-node transport is configured — a single-node
 * deployment, a non-Postgres (e.g. H2) datasource, or {@code onec.cluster.enabled=false}.
 *
 * <p>{@link #publish} is inert (nothing leaves the JVM) and {@link #subscribe} never fires (no remote
 * events ever arrive), so wiring it changes nothing: the local SSE fan-out on the change path is the
 * whole story, exactly as before this bus existed.
 */
public final class NoOpClusterEventBus implements ClusterEventBus {

    @Override
    public void publish(ClusterEvent event) {
        // Local-only: the change was already fanned out to this node's SSE clients; nothing to relay.
    }

    @Override
    public void subscribe(Consumer<ClusterEvent> sink) {
        // No remote events are ever delivered, so the sink is intentionally never invoked.
    }

    @Override
    public boolean isDistributed() {
        return false;
    }
}
