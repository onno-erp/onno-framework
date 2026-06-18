package su.onno.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Routes a decoded {@link InboundEvent} to every {@link EventHandler} that supports its type. */
public class InboundEventRouter {

    private static final Logger log = LoggerFactory.getLogger(InboundEventRouter.class);

    private final List<EventHandler> handlers;

    public InboundEventRouter(List<EventHandler> handlers) {
        this.handlers = handlers;
    }

    /** @return the number of handlers that processed the event. */
    public int dispatch(InboundEvent event) {
        int handled = 0;
        for (EventHandler handler : handlers) {
            if (handler.supports(event.type())) {
                handler.handle(event);
                handled++;
            }
        }
        if (handled == 0) {
            log.debug("No handler for inbound event type '{}' (id={})", event.type(), event.id());
        }
        return handled;
    }
}
