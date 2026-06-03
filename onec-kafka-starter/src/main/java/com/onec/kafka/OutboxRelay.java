package com.onec.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onec.messaging.OutboxEvent;
import com.onec.messaging.OutboxWriter;

import org.springframework.kafka.core.KafkaTemplate;

import java.time.OffsetDateTime;

public class OutboxRelay {

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
            kafkaTemplate.send(properties.getTopic(), event.aggregateId(), toCloudEvent(event));
            outboxWriter.markPublished(event.id());
            count++;
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
