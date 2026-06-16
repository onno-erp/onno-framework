package com.onec.cluster.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onec.cluster.ClusterEvent;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * Exercises the receive-side dispatch logic (self-filter, sink fan-out, malformed payloads) without a
 * database — the listener thread (started only in {@code afterPropertiesSet}) is never launched here.
 */
class PostgresClusterEventBusUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private PostgresClusterEventBus busForNode(String nodeId) {
        OnecClusterProperties props = new OnecClusterProperties();
        props.setNodeId(nodeId);
        return new PostgresClusterEventBus(mock(DataSource.class), mapper, props);
    }

    private String json(ClusterEvent event) throws Exception {
        return mapper.writeValueAsString(event);
    }

    @Test
    void deliversEventsThatOriginatedOnOtherNodes() throws Exception {
        PostgresClusterEventBus bus = busForNode("node-A");
        List<ClusterEvent> received = new ArrayList<>();
        bus.subscribe(received::add);

        bus.dispatch(json(ClusterEvent.entityChanged("created", "catalog", "Customers", "id-1", "C-1")
                .withOrigin("node-B")));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).entityName()).isEqualTo("Customers");
    }

    @Test
    void dropsItsOwnEchoedEvents() throws Exception {
        PostgresClusterEventBus bus = busForNode("node-A");
        List<ClusterEvent> received = new ArrayList<>();
        bus.subscribe(received::add);

        bus.dispatch(json(ClusterEvent.entityChanged("created", "catalog", "Customers", "id-1", "C-1")
                .withOrigin("node-A")));

        assertThat(received).isEmpty();
    }

    @Test
    void ignoresMalformedPayloadsWithoutThrowing() {
        PostgresClusterEventBus bus = busForNode("node-A");
        List<ClusterEvent> received = new ArrayList<>();
        bus.subscribe(received::add);

        assertThatCode(() -> {
            bus.dispatch("not json");
            bus.dispatch("");
            bus.dispatch(null);
        }).doesNotThrowAnyException();
        assertThat(received).isEmpty();
    }
}
