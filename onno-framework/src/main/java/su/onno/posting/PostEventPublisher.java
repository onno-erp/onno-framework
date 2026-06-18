package su.onno.posting;

/**
 * Narrow SPI the {@link PostingEngine} uses to emit {@link DocumentPostedEvent}/
 * {@link DocumentUnpostedEvent} after a post/unpost transaction commits.
 *
 * <p>The core module deliberately does not depend on {@code spring-context}, so it cannot reference
 * {@code ApplicationEventPublisher} directly. {@code onno-framework-starter} provides the bridge
 * implementation (a lambda over {@code ApplicationEventPublisher::publishEvent}) so application code
 * can simply {@code @EventListener} for these events — the first-class, Spring-bean-reachable
 * "after post" extension point that doesn't require the Kafka outbox.
 */
@FunctionalInterface
public interface PostEventPublisher {

    void publish(Object event);
}
