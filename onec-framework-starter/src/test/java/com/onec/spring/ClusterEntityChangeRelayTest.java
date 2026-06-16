package com.onec.spring;

import com.onec.cluster.ClusterEvent;
import com.onec.cluster.ClusterEventBus;
import com.onec.events.EntityChangedEvent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterEntityChangeRelayTest {

    /** Captures what the relay publishes, without a real transport. */
    static final class CapturingBus implements ClusterEventBus {
        final List<ClusterEvent> published = new ArrayList<>();

        @Override
        public void publish(ClusterEvent event) {
            published.add(event);
        }

        @Override
        public void subscribe(Consumer<ClusterEvent> sink) {
            // not exercised here
        }
    }

    @Test
    void relaysEntityChangedWithFieldsMapped() {
        CapturingBus bus = new CapturingBus();
        UUID id = UUID.randomUUID();

        new ClusterEntityChangeRelay(bus)
                .onEntityChanged(new EntityChangedEvent("created", "catalog", "Customers", id, "C-1"));

        assertThat(bus.published).hasSize(1);
        ClusterEvent event = bus.published.get(0);
        assertThat(event.kind()).isEqualTo(ClusterEvent.KIND_ENTITY_CHANGED);
        assertThat(event.changeType()).isEqualTo("created");
        assertThat(event.entityType()).isEqualTo("catalog");
        assertThat(event.entityName()).isEqualTo("Customers");
        assertThat(event.id()).isEqualTo(id.toString());
        assertThat(event.naturalKey()).isEqualTo("C-1");
        // The relay leaves origin unset; the bus stamps its own node id on publish.
        assertThat(event.originNodeId()).isNull();
    }

    @Test
    void mapsNullIdToNull() {
        CapturingBus bus = new CapturingBus();

        new ClusterEntityChangeRelay(bus)
                .onEntityChanged(new EntityChangedEvent("deleted", "document", "Invoices", null, null));

        assertThat(bus.published.get(0).id()).isNull();
        assertThat(bus.published.get(0).naturalKey()).isNull();
    }
}
