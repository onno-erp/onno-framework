package com.onec.events;

/**
 * Narrow SPI the framework-starter write callbacks use to emit {@link EntityChangedEvent}s after a
 * {@code repository.save(...)}/{@code delete(...)}.
 *
 * <p>The core module deliberately does not depend on {@code spring-context}, so it cannot reference
 * {@code ApplicationEventPublisher} directly (the same pattern as {@code PostEventPublisher}).
 * {@code onec-framework-starter} provides the bridge implementation — a lambda over
 * {@code ApplicationEventPublisher::publishEvent} — so application code can simply
 * {@code @EventListener} for {@link EntityChangedEvent}.
 */
@FunctionalInterface
public interface EntityChangePublisher {

    void publish(EntityChangedEvent event);
}
