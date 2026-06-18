package su.onno.mail.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drives {@link MailOutboxRelay#relayPending()} on a fixed delay so queued mail actually leaves the outbox.
 * Without this, {@code MailService.queue(...)} would persist messages that are never dispatched.
 */
public class MailOutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(MailOutboxRelayScheduler.class);

    private final MailOutboxRelay relay;

    public MailOutboxRelayScheduler(MailOutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${onno.mail.relay.interval-ms:30000}",
            initialDelayString = "${onno.mail.relay.interval-ms:30000}")
    public void run() {
        try {
            int dispatched = relay.relayPending();
            if (dispatched > 0) {
                log.debug("[mail:relay] dispatched {} message(s)", dispatched);
            }
        } catch (Exception e) {
            log.warn("[mail:relay] relay run failed: {}", e.toString());
        }
    }
}
