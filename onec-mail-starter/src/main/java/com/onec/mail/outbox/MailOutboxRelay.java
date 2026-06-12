package com.onec.mail.outbox;

import com.onec.mail.MailMessage;
import com.onec.mail.MailProperties;
import com.onec.mail.dispatch.MailDispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;

public class MailOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(MailOutboxRelay.class);

    private final MailOutbox outbox;
    private final MailDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final MailProperties properties;

    public MailOutboxRelay(MailOutbox outbox,
                           MailDispatcher dispatcher,
                           ObjectMapper objectMapper,
                           MailProperties properties) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public int relayPending() {
        int dispatched = 0;
        Duration lease = Duration.ofMillis(properties.getRelay().getLeaseTimeoutMs());
        for (MailOutbox.Pending p : outbox.claimBatch(properties.getRelayBatchSize(), lease)) {
            try {
                MailMessage msg = objectMapper.readValue(p.payload(), MailMessage.class);
                dispatcher.dispatch(msg);
                outbox.markDispatched(p.id());
                dispatched++;
            } catch (Exception e) {
                int attempts = p.attempts() + 1;
                boolean exhausted = attempts >= properties.getRelay().getMaxAttempts();
                LocalDateTime next = LocalDateTime.now().plus(backoff(attempts));
                outbox.recordFailure(p.id(), attempts, summarize(e), next, exhausted);
                if (exhausted) {
                    log.error("Mail outbox message {} failed permanently after {} attempts: {}",
                            p.id(), attempts, summarize(e));
                } else {
                    log.warn("Mail outbox dispatch failed for message {} (attempt {}/{}), retrying at {}: {}",
                            p.id(), attempts, properties.getRelay().getMaxAttempts(), next, summarize(e));
                }
            }
        }
        if (dispatched > 0) {
            log.debug("Dispatched {} mail outbox message(s)", dispatched);
        }
        return dispatched;
    }

    private Duration backoff(int attempts) {
        // 1m, 5m, 15m, 60m, 4h
        return switch (attempts) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(15);
            case 4 -> Duration.ofHours(1);
            default -> Duration.ofHours(4);
        };
    }

    private String summarize(Exception e) {
        String s = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
