package su.onno.kafka;

/**
 * Application-defined handler for inbound CloudEvents. Register one or more as Spring beans;
 * every handler whose {@link #supports(String)} matches an event's {@code type} is invoked.
 *
 * <p>Handlers should be idempotent. The framework de-duplicates by CloudEvent id via the inbox,
 * but a handler may still be retried if it throws after partial work.
 */
public interface EventHandler {

    /** @return {@code true} if this handler should process the given CloudEvent {@code type}. */
    boolean supports(String eventType);

    /** Process the event. Throwing signals failure: the inbox row is marked FAILED and the
     *  message is dead-lettered (if configured) or redelivered. */
    void handle(InboundEvent event);
}
