package su.onno.process;

/**
 * Narrow event bridge used by durable process engines without coupling the core process API to
 * Spring. The framework starter publishes these events through the application event bus.
 */
@FunctionalInterface
public interface ProcessEventPublisher {

    void publish(ProcessTasksChangedEvent event);
}
