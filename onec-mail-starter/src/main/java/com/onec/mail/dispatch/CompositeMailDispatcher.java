package com.onec.mail.dispatch;

import com.onec.mail.MailDeliveryException;
import com.onec.mail.MailMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Failover dispatcher ({@code provider=failover}). Tries an ordered list of delegate providers,
 * moving to the next only when the current one throws. Succeeds as soon as one delegate accepts the message;
 * throws {@link MailDeliveryException} (carrying the last failure) only if every delegate fails.
 */
public class CompositeMailDispatcher implements MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CompositeMailDispatcher.class);

    private final List<MailDispatcher> delegates;

    public CompositeMailDispatcher(List<MailDispatcher> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("CompositeMailDispatcher requires at least one delegate");
        }
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public String name() {
        return "failover";
    }

    @Override
    public void dispatch(MailMessage message) {
        RuntimeException last = null;
        for (MailDispatcher delegate : delegates) {
            try {
                delegate.dispatch(message);
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("[mail:failover] provider '{}' failed, trying next: {}", delegate.name(), e.getMessage());
            }
        }
        throw new MailDeliveryException(
                "All failover providers failed: " + delegates.stream().map(MailDispatcher::name).toList(), last);
    }
}
