package com.onec.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.onec.messaging.OutboxEvent;
import com.onec.messaging.OutboxWriter;
import com.onec.schema.SchemaGenerator;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private OutboxWriter writer;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxRelay relay;
    private final OnecKafkaProperties properties = new OnecKafkaProperties();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Jdbi jdbi = Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi.useHandle(h -> h.execute(SchemaGenerator.generateOutboxTableDDL()));
        writer = new OutboxWriter(jdbi);
        kafkaTemplate = mock(KafkaTemplate.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        relay = new OutboxRelay(writer, kafkaTemplate, mapper, properties);
    }

    @Test
    void publishesPendingEventAsCloudEventAndMarksIt() throws Exception {
        writer.append("Order", "o-1", "OrderPosted", "{\"total\":5}");
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        int relayed = relay.relayPending();

        assertThat(relayed).isEqualTo(1);
        assertThat(writer.findPending(10)).isEmpty();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(properties.getTopic()), eq("o-1"), payload.capture());
        JsonNode event = new ObjectMapper().readTree(payload.getValue());
        assertThat(event.get("specversion").asText()).isEqualTo("1.0");
        assertThat(event.get("type").asText()).isEqualTo("OrderPosted");
        assertThat(event.get("source").asText()).isEqualTo(properties.getServiceName());
        assertThat(event.get("subject").asText()).isEqualTo("Order/o-1");
        assertThat(event.get("data").asText()).isEqualTo("{\"total\":5}");
    }

    @Test
    void failedSendLeavesEventPendingForRetry() {
        writer.append("Order", "o-1", "OrderPosted", "{}");
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThat(relay.relayPending()).isZero();
        assertThat(writer.findPending(10)).hasSize(1);
    }

    @Test
    void batchStopsAtFirstFailureSoLaterEventsAreNotReordered() {
        writer.append("Order", "o-1", "OrderPosted", "{}");
        writer.append("Order", "o-2", "OrderPosted", "{}");
        writer.append("Order", "o-3", "OrderPosted", "{}");
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThat(relay.relayPending()).isEqualTo(1);

        ArgumentCaptor<String> sentKeys = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, atLeastOnce()).send(anyString(), sentKeys.capture(), anyString());
        String published = sentKeys.getAllValues().get(0);

        List<OutboxEvent> pending = writer.findPending(10);
        assertThat(pending).hasSize(2);
        assertThat(pending).extracting(OutboxEvent::aggregateId).doesNotContain(published);
    }
}
