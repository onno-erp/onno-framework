package su.onno.ui.notifications;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import su.onno.cluster.ClusterEvent;
import su.onno.cluster.ClusterEventBus;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NotificationService} delivery: a valid {@code notify} persists the row, pushes it to the
 * recipient's local SSE streams, and relays it to peers; an invalid request is a silent no-op; and a
 * remote (peer-relayed) notification only wakes local streams — it is never re-stored (the durable copy
 * already lives in the shared table) nor re-broadcast (no relay loop).
 */
class NotificationServiceTest {

    private NotificationStore store;
    private CapturingPublisher publisher;
    private CapturingBus bus;
    private NotificationService service;

    private final String alice = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:notifsvc" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        store = new NotificationStore(Jdbi.create(ds));
        publisher = new CapturingPublisher();
        bus = new CapturingBus();
        service = new NotificationService(store, publisher, bus, new NotificationProperties());
    }

    @Test
    void notifyPersistsPushesLocallyAndRelaysToPeers() {
        Notification stored = service.notify(NotificationRequest.to(alice)
                .type("assignment").title("You were assigned SO-1").link("documents/orders/x").build());

        assertThat(stored).isNotNull();
        // Persisted for the recipient.
        assertThat(store.list(alice, false, null, 30).items()).extracting(Notification::title)
                .containsExactly("You were assigned SO-1");
        // Pushed to local streams, addressed to the recipient, with the display fields.
        assertThat(publisher.recipientId).isEqualTo(alice);
        assertThat(publisher.payload).containsEntry("title", "You were assigned SO-1");
        assertThat(publisher.payload).containsEntry("type", "notification");
        // Relayed to peers as a Notification cluster event.
        assertThat(bus.published).hasSize(1);
        assertThat(bus.published.get(0)).isInstanceOf(ClusterEvent.Notification.class);
        ClusterEvent.Notification relayed = (ClusterEvent.Notification) bus.published.get(0);
        assertThat(relayed.recipientId()).isEqualTo(alice);
        assertThat(relayed.title()).isEqualTo("You were assigned SO-1");
    }

    @Test
    void notifyIgnoresARequestWithNoRecipientOrTitle() {
        assertThat(service.notify(NotificationRequest.to(null).title("orphan").build())).isNull();
        assertThat(service.notify(NotificationRequest.to(alice).build())).isNull(); // no title

        assertThat(store.list(alice, false, null, 30).items()).isEmpty();
        assertThat(publisher.calls).isZero();
        assertThat(bus.published).isEmpty();
    }

    @Test
    void onRemoteWakesLocalStreamsWithoutStoringOrRebroadcasting() {
        ClusterEvent.Notification peer = ClusterEvent.notification(alice, UUID.randomUUID().toString(),
                "mention", "Ada mentioned you", "see the thread", "documents/orders/x", "Ada");

        service.onRemote(peer);

        // Pushed to this node's recipient streams...
        assertThat(publisher.recipientId).isEqualTo(alice);
        assertThat(publisher.payload).containsEntry("title", "Ada mentioned you");
        // ...but not persisted here (the origin node owns the durable write to the shared table)...
        assertThat(store.list(alice, false, null, 30).items()).isEmpty();
        // ...and not re-broadcast (no relay loop).
        assertThat(bus.published).isEmpty();
    }

    /** Captures the recipient/payload of the last local SSE push instead of writing to a real emitter. */
    private static final class CapturingPublisher extends UiEventPublisher {
        String recipientId;
        Map<String, Object> payload;
        int calls;

        CapturingPublisher() {
            super(new UiAccessService(new MetadataRegistry()));
        }

        @Override
        public void publishNotification(String recipientId, Map<String, Object> payload) {
            this.recipientId = recipientId;
            this.payload = payload;
            this.calls++;
        }
    }

    /** Records published cluster events; never delivers (single-node test). */
    private static final class CapturingBus implements ClusterEventBus {
        final List<ClusterEvent> published = new ArrayList<>();

        @Override
        public void publish(ClusterEvent event) {
            published.add(event);
        }

        @Override
        public void subscribe(Consumer<ClusterEvent> sink) {
            // no-op — the service subscribes in @PostConstruct, which this unit test doesn't invoke
        }

        @Override
        public boolean isDistributed() {
            return true;
        }
    }
}
