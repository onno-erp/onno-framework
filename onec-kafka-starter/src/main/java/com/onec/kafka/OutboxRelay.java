package com.onec.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onec.messaging.OutboxEvent;
import com.onec.messaging.OutboxWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.OffsetDateTime;

public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxWriter outboxWriter;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OnecKafkaProperties properties;

    public OutboxRelay(OutboxWriter outboxWriter,
                       KafkaTemplate<String, String> kafkaTemplate,
                       ObjectMapper objectMapper,
                       OnecKafkaProperties properties) {
        this.outboxWriter = outboxWriter;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public int relayPending() {
        int count = 0;
        for (OutboxEvent event : outboxWriter.findPending(properties.getRelayBatchSize())) {
            try {
                // Wait for broker acknowledgement before marking the event published —
                // send() is asynchronous, and marking on submit would lose the event if
                // the broker rejects it.
                kafkaTemplate.send(properties.getTopic(), event.aggregateId(), toCloudEvent(event)).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while publishing outbox event {} ({}); "
                        + "it stays pending and will be retried on the next relay run",
                        event.id(), event.eventType(), e);
                break;
            } catch (Exception e) {
                log.error("Failed to publish outbox event {} ({}); it stays pending and will be "
                        + "retried on the next relay run", event.id(), event.eventType(), e);
                // Stop the batch so later events are not published ahead of a failed one.
                break;
            }
            outboxWriter.markPublished(event.id());
            count++;
        }
        if (count > 0) {
            log.debug("Relayed {} outbox event(s) to topic {}", count, properties.getTopic());
        }
        return count;
    }

    private String toCloudEvent(OutboxEvent event) {
        try {
            return objectMapper.writeValueAsString(new CloudEvent(
                    "1.0",
                    event.id().toString(),
                    properties.getServiceName(),
                    event.eventType(),
                    event.aggregateType() + "/" + event.aggregateId(),
                    OffsetDateTime.now(),
                    "application/json",
                    event.payload()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize CloudEvent", e);
        }
    }
}
