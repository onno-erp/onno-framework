package su.onno.spring;

import org.springframework.context.event.EventListener;
import su.onno.cluster.ClusterEvent;
import su.onno.cluster.ClusterEventBus;
import su.onno.process.ProcessTasksChangedEvent;

/**
 * Relays locally committed process-task inbox invalidations to peer nodes. Remote events go
 * directly to those nodes' SSE publishers and are not re-emitted through Spring.
 */
public final class ClusterProcessTaskRelay {

    private final ClusterEventBus bus;

    public ClusterProcessTaskRelay(ClusterEventBus bus) {
        this.bus = bus;
    }

    @EventListener
    public void onTasksChanged(ProcessTasksChangedEvent event) {
        bus.publish(ClusterEvent.processTasksChanged(
                event.instanceId().toString(), event.audienceUsers(), event.audienceRoles()));
    }
}
