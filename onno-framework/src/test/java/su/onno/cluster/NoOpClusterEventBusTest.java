package su.onno.cluster;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpClusterEventBusTest {

    private final NoOpClusterEventBus bus = new NoOpClusterEventBus();

    @Test
    void publishIsInertAndNeverThrows() {
        assertThatCode(() -> bus.publish(ClusterEvent.entityChanged("created", "catalog", "Customers", "id", "C-1")))
                .doesNotThrowAnyException();
    }

    @Test
    void subscribedSinkIsNeverInvoked() {
        AtomicInteger calls = new AtomicInteger();
        bus.subscribe(event -> calls.incrementAndGet());

        bus.publish(ClusterEvent.entityChanged("updated", "document", "Invoices", "id", "INV-9"));

        assertThat(calls).hasValue(0);
    }

    @Test
    void isNotDistributed() {
        assertThat(bus.isDistributed()).isFalse();
    }
}
