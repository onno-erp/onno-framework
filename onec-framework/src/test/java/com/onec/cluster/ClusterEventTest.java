package com.onec.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterEventTest {

    @Test
    void entityChangedFactorySetsKindAndLeavesOriginUnset() {
        ClusterEvent event = ClusterEvent.entityChanged("created", "catalog", "Customers", "id-1", "C-1");

        assertThat(event.kind()).isEqualTo(ClusterEvent.KIND_ENTITY_CHANGED);
        assertThat(event.originNodeId()).isNull();
        assertThat(event.changeType()).isEqualTo("created");
        assertThat(event.entityType()).isEqualTo("catalog");
        assertThat(event.entityName()).isEqualTo("Customers");
        assertThat(event.id()).isEqualTo("id-1");
        assertThat(event.naturalKey()).isEqualTo("C-1");
    }

    @Test
    void withOriginStampsTheNodeIdAndPreservesEverythingElse() {
        ClusterEvent stamped = ClusterEvent.entityChanged("updated", "document", "Invoices", "id-2", "INV-2")
                .withOrigin("node-A");

        assertThat(stamped.originNodeId()).isEqualTo("node-A");
        assertThat(stamped.kind()).isEqualTo(ClusterEvent.KIND_ENTITY_CHANGED);
        assertThat(stamped.changeType()).isEqualTo("updated");
        assertThat(stamped.entityType()).isEqualTo("document");
        assertThat(stamped.entityName()).isEqualTo("Invoices");
        assertThat(stamped.id()).isEqualTo("id-2");
        assertThat(stamped.naturalKey()).isEqualTo("INV-2");
    }
}
