package com.onec.ui.notifications;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link NotificationStore} against H2 — chiefly the recipient scoping, which is the
 * security boundary: a caller must never read, mark, or delete a notification that isn't addressed to
 * one of their keys.
 */
class NotificationStoreTest {

    private NotificationStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:notif-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        store = new NotificationStore(Jdbi.create(ds));
    }

    private Notification draft(String recipient, String title) {
        return new Notification(UUID.randomUUID(), recipient, title, "body",
                NotificationSeverity.INFO, "test", null, LocalDateTime.now(), false, null);
    }

    @Test
    void listReturnsNewestFirstForTheRecipientOnly() {
        store.insert(draft(NotificationRecipients.user("alice"), "first"));
        store.insert(draft(NotificationRecipients.user("alice"), "second"));
        store.insert(draft(NotificationRecipients.user("bob"), "bob-only"));

        List<Notification> alice = store.list(List.of(NotificationRecipients.user("alice")), false, 50);

        assertThat(alice).extracting(Notification::title).containsExactly("second", "first");
    }

    @Test
    void listAcrossMultipleRecipientKeysUnionsThem() {
        store.insert(draft(NotificationRecipients.user("alice"), "to-alice"));
        store.insert(draft(NotificationRecipients.role("FINANCE"), "to-finance"));
        store.insert(draft(NotificationRecipients.record("rec-1"), "to-record"));
        store.insert(draft(NotificationRecipients.role("HR"), "unseen"));

        List<Notification> inbox = store.list(List.of(
                NotificationRecipients.user("alice"),
                NotificationRecipients.role("FINANCE"),
                NotificationRecipients.record("rec-1")), false, 50);

        assertThat(inbox).extracting(Notification::title)
                .containsExactlyInAnyOrder("to-alice", "to-finance", "to-record");
    }

    @Test
    void unreadFilterAndCount() {
        Notification n = draft(NotificationRecipients.user("alice"), "unread-one");
        store.insert(n);
        store.insert(draft(NotificationRecipients.user("alice"), "unread-two"));

        assertThat(store.countUnread(List.of(NotificationRecipients.user("alice")))).isEqualTo(2);

        store.markRead(n.id(), List.of(NotificationRecipients.user("alice")));

        assertThat(store.countUnread(List.of(NotificationRecipients.user("alice")))).isEqualTo(1);
        assertThat(store.list(List.of(NotificationRecipients.user("alice")), true, 50))
                .extracting(Notification::title).containsExactly("unread-two");
    }

    @Test
    void markReadIsScopedToRecipient() {
        Notification bobs = draft(NotificationRecipients.user("bob"), "bobs");
        store.insert(bobs);

        // Alice cannot mark Bob's notification read.
        int changed = store.markRead(bobs.id(), List.of(NotificationRecipients.user("alice")));

        assertThat(changed).isZero();
        assertThat(store.countUnread(List.of(NotificationRecipients.user("bob")))).isEqualTo(1);
    }

    @Test
    void markAllReadOnlyTouchesTheCallersInbox() {
        store.insert(draft(NotificationRecipients.user("alice"), "a1"));
        store.insert(draft(NotificationRecipients.user("alice"), "a2"));
        store.insert(draft(NotificationRecipients.user("bob"), "b1"));

        int changed = store.markAllRead(List.of(NotificationRecipients.user("alice")));

        assertThat(changed).isEqualTo(2);
        assertThat(store.countUnread(List.of(NotificationRecipients.user("bob")))).isEqualTo(1);
    }

    @Test
    void deleteIsScopedToRecipient() {
        Notification bobs = draft(NotificationRecipients.user("bob"), "bobs");
        store.insert(bobs);

        assertThat(store.delete(bobs.id(), List.of(NotificationRecipients.user("alice")))).isZero();
        assertThat(store.delete(bobs.id(), List.of(NotificationRecipients.user("bob")))).isEqualTo(1);
        assertThat(store.list(List.of(NotificationRecipients.user("bob")), false, 50)).isEmpty();
    }

    @Test
    void emptyRecipientSetSeesNothing() {
        store.insert(draft(NotificationRecipients.user("alice"), "a1"));

        assertThat(store.list(List.of(), false, 50)).isEmpty();
        assertThat(store.countUnread(List.of())).isZero();
        assertThat(store.markAllRead(List.of())).isZero();
    }

    @Test
    void listHonoursLimit() {
        for (int i = 0; i < 5; i++) {
            store.insert(draft(NotificationRecipients.user("alice"), "n" + i));
        }
        assertThat(store.list(List.of(NotificationRecipients.user("alice")), false, 3)).hasSize(3);
    }
}
