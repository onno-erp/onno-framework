package su.onno.ui.notifications;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link NotificationStore} persistence contract: a feed is scoped to one recipient, newest-first,
 * keyset-paginated with no overlap; unread state is a nullable read timestamp; mark-read is recipient-
 * scoped; and the retention sweep only removes read rows. The table is created by the store constructor,
 * so the only setup is an in-memory H2.
 */
class NotificationStoreTest {

    private NotificationStore store;
    private final String alice = UUID.randomUUID().toString();
    private final String bob = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:notifications" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        store = new NotificationStore(Jdbi.create(ds));
    }

    private Notification insert(String recipient, String title) {
        return store.insert(recipient, "mention", title, "body of " + title, "documents/orders/x",
                bob, "Bob", null);
    }

    @Test
    void listsOnlyTheRecipientsOwnNotifications() {
        insert(alice, "A1");
        insert(alice, "A2");
        insert(bob, "B1");

        NotificationStore.Page page = store.list(alice, false, null, 30);

        assertThat(page.items()).extracting(Notification::title).containsExactlyInAnyOrder("A1", "A2");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void unreadCountAndMarkReadAreRecipientScoped() {
        Notification a1 = insert(alice, "A1");
        insert(alice, "A2");
        insert(bob, "B1");

        assertThat(store.unreadCount(alice)).isEqualTo(2);

        // Bob can't mark Alice's notification read — wrong recipient, no effect.
        assertThat(store.markRead(a1.id(), bob)).isFalse();
        assertThat(store.unreadCount(alice)).isEqualTo(2);

        assertThat(store.markRead(a1.id(), alice)).isTrue();
        assertThat(store.unreadCount(alice)).isEqualTo(1);
        // Marking an already-read one again is a no-op.
        assertThat(store.markRead(a1.id(), alice)).isFalse();

        // The unread filter now hides the read one.
        assertThat(store.list(alice, true, null, 30).items()).extracting(Notification::title)
                .containsExactly("A2");
    }

    @Test
    void markAllReadFlipsEveryUnreadForTheRecipientOnly() {
        insert(alice, "A1");
        insert(alice, "A2");
        insert(bob, "B1");

        assertThat(store.markAllRead(alice)).isEqualTo(2);
        assertThat(store.unreadCount(alice)).isZero();
        assertThat(store.unreadCount(bob)).isEqualTo(1); // Bob's is untouched
    }

    @Test
    void keysetPaginationWalksTheWholeFeedWithoutOverlap() {
        int total = 25;
        for (int i = 0; i < total; i++) {
            insert(alice, "N" + i);
        }

        Set<String> seen = new HashSet<>();
        List<String> order = new ArrayList<>();
        String cursor = null;
        int windows = 0;
        do {
            NotificationStore.Page page = store.list(alice, false, cursor, 10);
            for (Notification n : page.items()) {
                assertThat(seen.add(n.id().toString())).as("no id appears in two windows").isTrue();
                order.add(n.title());
            }
            cursor = page.nextCursor();
            windows++;
        } while (cursor != null);

        assertThat(seen).hasSize(total);         // every row visited exactly once
        assertThat(windows).isEqualTo(3);        // 10 + 10 + 5
    }

    @Test
    void pruneRemovesOnlyReadRowsOlderThanTheCutoff() {
        Notification read = insert(alice, "old-read");
        insert(alice, "old-unread");
        store.markRead(read.id(), alice);

        // A cutoff in the future makes every row "older than cutoff"; only the read one is pruned.
        int removed = store.pruneReadBefore(Instant.now().plusSeconds(60));

        assertThat(removed).isEqualTo(1);
        assertThat(store.list(alice, false, null, 30).items()).extracting(Notification::title)
                .containsExactly("old-unread");
    }
}
