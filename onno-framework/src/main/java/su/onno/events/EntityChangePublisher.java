package su.onno.events;

/**
 * Narrow SPI the framework-starter write callbacks use to emit {@link EntityChangedEvent}s after a
 * {@code repository.save(...)}/{@code delete(...)}.
 *
 * <p>The core module deliberately does not depend on {@code spring-context}, so it cannot reference
 * {@code ApplicationEventPublisher} directly (the same pattern as {@code PostEventPublisher}).
 * {@code onno-framework-starter} provides the bridge implementation, publishing after commit of an
 * active Spring transaction (never on rollback), or immediately without a transaction. Application code can simply
 * {@code @EventListener} for {@link EntityChangedEvent}.
 */
@FunctionalInterface
public interface EntityChangePublisher {

    void publish(EntityChangedEvent event);
}
