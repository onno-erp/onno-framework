package com.onec.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;

/**
 * Kafka message listener that decodes the CloudEvents envelope, de-duplicates via the {@link Inbox},
 * and dispatches to application {@link EventHandler}s through the {@link InboundEventRouter}.
 *
 * <p>Failures during handling mark the inbox row FAILED and either publish to the configured
 * dead-letter topic (then commit) or rethrow to let the container redeliver.
 */
public class KafkaEventConsumer implements MessageListener<String, String> {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);

    private final InboundEventRouter router;
    private final ObjectMapper objectMapper;
    private final Inbox inbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OneCKafkaProperties properties;

    public KafkaEventConsumer(InboundEventRouter router,
                              ObjectMapper objectMapper,
                              Inbox inbox,
                              KafkaTemplate<String, String> kafkaTemplate,
                              OneCKafkaProperties properties) {
        this.router = router;
        this.objectMapper = objectMapper;
        this.inbox = inbox;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void onMessage(ConsumerRecord<String, String> record) {
        CloudEvent cloudEvent;
        try {
            cloudEvent = objectMapper.readValue(record.value(), CloudEvent.class);
        } catch (Exception e) {
            log.error("Discarding malformed CloudEvent at {}-{}@{}: {}",
                    record.topic(), record.partition(), record.offset(), summarize(e));
            deadLetter(record);
            return;
        }

        if (inbox != null && !inbox.markReceivedIfNew(cloudEvent.id(), cloudEvent.type(), cloudEvent.source())) {
            log.debug("Skipping duplicate inbound event id={}", cloudEvent.id());
            return;
        }

        InboundEvent event = new InboundEvent(
                cloudEvent.id(),
                cloudEvent.source(),
                cloudEvent.type(),
                cloudEvent.subject(),
                cloudEvent.time(),
                cloudEvent.data());

        try {
            int handled = router.dispatch(event);
            if (inbox != null) {
                inbox.markProcessed(cloudEvent.id());
            }
            log.debug("Processed inbound event id={} type={} ({} handler(s))",
                    event.id(), event.type(), handled);
        } catch (RuntimeException e) {
            if (inbox != null) {
                inbox.markFailed(cloudEvent.id(), summarize(e));
            }
            String dlt = properties.getInbound().getDeadLetterTopic();
            if (dlt != null && !dlt.isBlank()) {
                log.error("Handler failed for event id={}, dead-lettering to {}: {}",
                        event.id(), dlt, summarize(e));
                deadLetter(record);
                return;
            }
            log.error("Handler failed for event id={}, will redeliver: {}", event.id(), summarize(e));
            throw e;
        }
    }

    private void deadLetter(ConsumerRecord<String, String> record) {
        String dlt = properties.getInbound().getDeadLetterTopic();
        if (dlt == null || dlt.isBlank() || kafkaTemplate == null) {
            return;
        }
        kafkaTemplate.send(dlt, record.key(), record.value());
    }

    private String summarize(Exception e) {
        String s = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
