package su.onno.cluster.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.cluster.ClusterEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterEventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsThroughJsonIncludingNulls() {
        ClusterEvent event = ClusterEvent.entityChanged("deleted", "catalog", "Customers", null, null)
                .withOrigin("node-A");

        String json = PostgresClusterEventBus.serialize(mapper, event, 7000);
        ClusterEvent back = PostgresClusterEventBus.deserialize(mapper, json);

        assertThat(back).isEqualTo(event);
    }

    @Test
    void dropsNaturalKeyWhenOverTheCap() {
        String hugeKey = "x".repeat(500);
        ClusterEvent event = ClusterEvent.entityChanged("updated", "document", "Invoices", "id-1", hugeKey)
                .withOrigin("node-A");

        // Cap large enough for the event without the 500-char key, but not with it.
        String json = PostgresClusterEventBus.serialize(mapper, event, 200);
        ClusterEvent.EntityChanged back = (ClusterEvent.EntityChanged) PostgresClusterEventBus.deserialize(mapper, json);

        assertThat(back.naturalKey()).isNull();
        assertThat(back.entityName()).isEqualTo("Invoices");
        assertThat(back.id()).isEqualTo("id-1");
    }

    @Test
    void degradesToCoarseNoticeWhenStillTooLarge() {
        String hugeName = "N".repeat(500);
        ClusterEvent event = ClusterEvent.entityChanged("changed", "register", hugeName, "id-1", "key-1")
                .withOrigin("node-A");

        // Cap big enough for the coarse "*" envelope (~141 bytes) but not the 500-char entityName.
        String json = PostgresClusterEventBus.serialize(mapper, event, 200);
        ClusterEvent.EntityChanged back = (ClusterEvent.EntityChanged) PostgresClusterEventBus.deserialize(mapper, json);

        assertThat(back.entityName()).isEqualTo("*");
        assertThat(back.id()).isNull();
        assertThat(back.naturalKey()).isNull();
        assertThat(back.changeType()).isEqualTo("changed");
    }

    @Test
    void roundTripsAPresenceEvent() {
        ClusterEvent event = ClusterEvent.presence(ClusterEvent.Presence.ENTER,
                "document", "Invoices", "id-7", "u-42", "Ada Lovelace", "https://pics/u-42.png").withOrigin("node-A");

        String json = PostgresClusterEventBus.serialize(mapper, event, 7000);
        ClusterEvent back = PostgresClusterEventBus.deserialize(mapper, json);

        assertThat(back).isEqualTo(event);
        assertThat(back).isInstanceOf(ClusterEvent.Presence.class);
        assertThat(((ClusterEvent.Presence) back).displayName()).isEqualTo("Ada Lovelace");
        assertThat(((ClusterEvent.Presence) back).avatarUrl()).isEqualTo("https://pics/u-42.png");
    }

    @Test
    void roundTripsANotificationEvent() {
        ClusterEvent event = ClusterEvent.notification("u-42", "n-1", "assignment",
                "You were assigned SO-1", "the order body", "documents/orders/id-7", "Ada Lovelace")
                .withOrigin("node-A");

        String json = PostgresClusterEventBus.serialize(mapper, event, 7000);
        ClusterEvent back = PostgresClusterEventBus.deserialize(mapper, json);

        assertThat(back).isEqualTo(event);
        assertThat(back).isInstanceOf(ClusterEvent.Notification.class);
        assertThat(((ClusterEvent.Notification) back).title()).isEqualTo("You were assigned SO-1");
        assertThat(((ClusterEvent.Notification) back).recipientId()).isEqualTo("u-42");
    }

    @Test
    void dropsNotificationDisplayFieldsWhenOverTheCap() {
        String hugeBody = "b".repeat(500);
        ClusterEvent event = ClusterEvent.notification("u-42", "n-1", "mention",
                "title", hugeBody, "documents/orders/id", "Ada").withOrigin("node-A");

        // Cap large enough for the recipient/id envelope but not the 500-char body; the durable copy
        // already exists, so the peer keeps only the routing fields and its browser refetches.
        String json = PostgresClusterEventBus.serialize(mapper, event, 200);
        ClusterEvent.Notification back = (ClusterEvent.Notification) PostgresClusterEventBus.deserialize(mapper, json);

        assertThat(back.recipientId()).isEqualTo("u-42");
        assertThat(back.notificationId()).isEqualTo("n-1");
        assertThat(back.title()).isNull();
        assertThat(back.body()).isNull();
    }

    @Test
    void neverThrowsAndReturnsNullWhenEvenCoarseEnvelopeOverflows() {
        ClusterEvent event = ClusterEvent.entityChanged("created", "catalog", "C", "id", "k").withOrigin("node-A");

        // A cap below any possible JSON forces the give-up branch; it must return null, not throw.
        String json = PostgresClusterEventBus.serialize(mapper, event, 1);

        assertThat(json).isNull();
    }
}
