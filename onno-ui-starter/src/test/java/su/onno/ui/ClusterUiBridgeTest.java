package su.onno.ui;

import su.onno.cluster.ClusterEvent;
import su.onno.cluster.ClusterEventBus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterUiBridgeTest {

    /** Captures the consumer the bridge subscribes, so the test can feed it a "remote" event. */
    static final class CapturingBus implements ClusterEventBus {
        Consumer<ClusterEvent> sink;

        @Override
        public void publish(ClusterEvent event) {
            // not exercised here
        }

        @Override
        public void subscribe(Consumer<ClusterEvent> sink) {
            this.sink = sink;
        }
    }

    /** Records calls to the plain SSE {@code publish(...)} sink — and proves no Spring event is involved. */
    static final class RecordingPublisher extends UiEventPublisher {
        record Call(String type, String entityType, String entityName, Object id, String naturalKey) { }

        final List<Call> calls = new ArrayList<>();

        RecordingPublisher() {
            // publish(...) is overridden to record, so the access service is never consulted.
            super(new UiAccessService(null));
        }

        @Override
        public void publish(String type, String entityType, String entityName, Object id, String naturalKey) {
            calls.add(new Call(type, entityType, entityName, id, naturalKey));
        }
    }

    @Test
    void forwardsRemoteEventsToTheLocalSseSink() {
        CapturingBus bus = new CapturingBus();
        RecordingPublisher publisher = new RecordingPublisher();

        ClusterUiBridge bridge = new ClusterUiBridge(bus, publisher);
        bridge.wire();
        assertThat(bus.sink).as("bridge subscribes to the bus").isNotNull();

        bus.sink.accept(ClusterEvent.entityChanged("updated", "document", "Invoices", "id-9", "INV-9")
                .withOrigin("node-B"));

        // Exactly one call, straight to the SSE publish sink — never re-fired as a Spring ApplicationEvent.
        assertThat(publisher.calls).hasSize(1);
        RecordingPublisher.Call call = publisher.calls.get(0);
        assertThat(call.type()).isEqualTo("updated");
        assertThat(call.entityType()).isEqualTo("document");
        assertThat(call.entityName()).isEqualTo("Invoices");
        assertThat(call.id()).isEqualTo("id-9");
        assertThat(call.naturalKey()).isEqualTo("INV-9");
    }
}
