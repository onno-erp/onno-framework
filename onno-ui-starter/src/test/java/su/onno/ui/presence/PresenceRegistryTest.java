package su.onno.ui.presence;

import su.onno.cluster.ClusterEvent;
import su.onno.cluster.ClusterEventBus;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiEventPublisher;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceRegistryTest {

    private static final long TTL = 45_000;
    private static final long SWEEP = 15_000;

    private final TestClock clock = new TestClock(1_000_000);
    private final CapturingBus bus = new CapturingBus();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final PresenceRegistry registry = new PresenceRegistry(bus, publisher, clock, TTL, SWEEP);

    @Test
    void enterAddsAViewerBroadcastsAndPushesSnapshot() {
        registry.onLocal(ClusterEvent.Presence.ENTER, "document", "Invoices", "id-7", "u1", "Ada");

        // Broadcast to peers, and one SSE snapshot because the viewer set changed.
        assertThat(bus.published).hasSize(1);
        assertThat(bus.published.get(0)).isInstanceOf(ClusterEvent.Presence.class);
        assertThat(publisher.pushes).hasSize(1);
        assertThat(userIds(publisher.last())).containsExactly("u1");
        assertThat(userIds(registry.viewers("document", "Invoices", "id-7"))).containsExactly("u1");
    }

    @Test
    void heartbeatBroadcastsButDoesNotRepushSnapshot() {
        registry.onLocal(ClusterEvent.Presence.ENTER, "document", "Invoices", "id-7", "u1", "Ada");
        registry.onLocal(ClusterEvent.Presence.HEARTBEAT, "document", "Invoices", "id-7", "u1", "Ada");

        // The heartbeat still relays to peers (keeps their TTL fresh) but the local viewer set is unchanged.
        assertThat(bus.published).hasSize(2);
        assertThat(publisher.pushes).hasSize(1);
    }

    @Test
    void secondViewerPushesAgainWithBothViewers() {
        registry.onLocal(ClusterEvent.Presence.ENTER, "document", "Invoices", "id-7", "u1", "Ada");
        registry.onLocal(ClusterEvent.Presence.ENTER, "document", "Invoices", "id-7", "u2", "Babbage");

        assertThat(publisher.pushes).hasSize(2);
        assertThat(userIds(publisher.last())).containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    void sweepExpiresAStaleViewerAndPushesTheRemainder() {
        registry.onLocal(ClusterEvent.Presence.ENTER, "document", "Invoices", "id-7", "u1", "Ada");
        registry.onLocal(ClusterEvent.Presence.ENTER, "document", "Invoices", "id-7", "u2", "Babbage");
        int pushesBefore = publisher.pushes.size();

        // u2 heartbeats just before the cutoff; u1 goes silent and ages past the TTL.
        clock.advance(TTL - 1);
        registry.onLocal(ClusterEvent.Presence.HEARTBEAT, "document", "Invoices", "id-7", "u2", "Babbage");
        clock.advance(2);
        registry.sweepExpired();

        assertThat(publisher.pushes).hasSize(pushesBefore + 1);
        assertThat(userIds(publisher.last())).containsExactly("u2");
        assertThat(userIds(registry.viewers("document", "Invoices", "id-7"))).containsExactly("u2");
    }

    @Test
    void sweepWithNothingStalePushesNothing() {
        registry.onLocal(ClusterEvent.Presence.ENTER, "document", "Invoices", "id-7", "u1", "Ada");
        int pushesBefore = publisher.pushes.size();

        clock.advance(TTL - 1);
        registry.sweepExpired();

        assertThat(publisher.pushes).hasSize(pushesBefore);
        assertThat(userIds(registry.viewers("document", "Invoices", "id-7"))).containsExactly("u1");
    }

    @Test
    void leaveRemovesTheViewerAndPushesEmpty() {
        registry.onLocal(ClusterEvent.Presence.ENTER, "document", "Invoices", "id-7", "u1", "Ada");
        registry.onLocal(ClusterEvent.Presence.LEAVE, "document", "Invoices", "id-7", "u1", "Ada");

        assertThat(userIds(publisher.last())).isEmpty();
        assertThat(registry.viewers("document", "Invoices", "id-7")).isEmpty();
    }

    @Test
    void remoteEventAppliesAndPushesButNeverRebroadcasts() {
        registry.onRemote((ClusterEvent.Presence)
                ClusterEvent.presence(ClusterEvent.Presence.ENTER, "document", "Invoices", "id-7", "u9", "Hopper")
                        .withOrigin("node-B"));

        assertThat(bus.published).as("a relayed event must not loop back onto the bus").isEmpty();
        assertThat(publisher.pushes).hasSize(1);
        assertThat(userIds(publisher.last())).containsExactly("u9");
    }

    private static List<String> userIds(List<Map<String, String>> viewers) {
        return viewers.stream().map(v -> v.get("userId")).toList();
    }

    static final class CapturingBus implements ClusterEventBus {
        final List<ClusterEvent> published = new ArrayList<>();

        @Override
        public void publish(ClusterEvent event) {
            published.add(event);
        }

        @Override
        public void subscribe(Consumer<ClusterEvent> sink) {
            // The test drives onRemote() directly, so the live subscription is not exercised here.
        }
    }

    static final class RecordingPublisher extends UiEventPublisher {
        record Push(String kind, String entityName, String id, List<Map<String, String>> viewers) {}

        final List<Push> pushes = new ArrayList<>();

        RecordingPublisher() {
            // publishPresence(...) is overridden to record, so the access service is never consulted.
            super(new UiAccessService(null));
        }

        @Override
        public void publishPresence(String kind, String entityName, String id, List<Map<String, String>> viewers) {
            pushes.add(new Push(kind, entityName, id, viewers));
        }

        List<Map<String, String>> last() {
            return pushes.get(pushes.size() - 1).viewers();
        }
    }

    static final class TestClock extends Clock {
        private long millis;

        TestClock(long startMillis) {
            this.millis = startMillis;
        }

        void advance(long deltaMillis) {
            this.millis += deltaMillis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
