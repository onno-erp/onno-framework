package com.onec.cluster.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onec.cluster.ClusterEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterEventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsThroughJsonIncludingNulls() throws Exception {
        ClusterEvent event = ClusterEvent.entityChanged("deleted", "catalog", "Customers", null, null)
                .withOrigin("node-A");

        String json = PostgresClusterEventBus.serialize(mapper, event, 7000);
        ClusterEvent back = mapper.readValue(json, ClusterEvent.class);

        assertThat(back).isEqualTo(event);
    }

    @Test
    void dropsNaturalKeyWhenOverTheCap() throws Exception {
        String hugeKey = "x".repeat(500);
        ClusterEvent event = ClusterEvent.entityChanged("updated", "document", "Invoices", "id-1", hugeKey)
                .withOrigin("node-A");

        // Cap large enough for the event without the 500-char key, but not with it.
        String json = PostgresClusterEventBus.serialize(mapper, event, 200);
        ClusterEvent back = mapper.readValue(json, ClusterEvent.class);

        assertThat(back.naturalKey()).isNull();
        assertThat(back.entityName()).isEqualTo("Invoices");
        assertThat(back.id()).isEqualTo("id-1");
    }

    @Test
    void degradesToCoarseNoticeWhenStillTooLarge() throws Exception {
        String hugeName = "N".repeat(500);
        ClusterEvent event = ClusterEvent.entityChanged("changed", "register", hugeName, "id-1", "key-1")
                .withOrigin("node-A");

        // Cap big enough for the coarse "*" envelope (~141 bytes) but not the 500-char entityName.
        String json = PostgresClusterEventBus.serialize(mapper, event, 200);
        ClusterEvent back = mapper.readValue(json, ClusterEvent.class);

        assertThat(back.entityName()).isEqualTo("*");
        assertThat(back.id()).isNull();
        assertThat(back.naturalKey()).isNull();
        assertThat(back.changeType()).isEqualTo("changed");
    }

    @Test
    void neverThrowsAndReturnsNullWhenEvenCoarseEnvelopeOverflows() {
        ClusterEvent event = ClusterEvent.entityChanged("created", "catalog", "C", "id", "k").withOrigin("node-A");

        // A cap below any possible JSON forces the give-up branch; it must return null, not throw.
        String json = PostgresClusterEventBus.serialize(mapper, event, 1);

        assertThat(json).isNull();
    }
}
