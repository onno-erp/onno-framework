package com.onec.ui.notifications;

import com.onec.events.EntityChangedEvent;
import com.onec.ui.CurrentUserResolver;
import com.onec.ui.CurrentUserResolver.CurrentUser;
import com.onec.ui.UiAccessService;

import org.springframework.context.ApplicationEventPublisher;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The entry point application (and framework) code uses to push notifications and the backing for the
 * {@code /api/notifications} read/mutate endpoints. Producers address a notification with the fluent
 * {@link #notify()} builder — {@code service.notify().toUser("alice").title("...").send()} — or the
 * {@code notifyX} shortcuts; the inbox side resolves the authenticated principal to the set of
 * recipient keys it can see (its username, its roles, and its linked identity record) and scopes every
 * query to that set.
 *
 * <p>The {@code entityType} stamped on the live-sync {@link EntityChangedEvent} is its own kind
 * ({@code "notification"}, never {@code catalog}/{@code document}) so the list, map, and content
 * surfaces — which only react to the modelled kinds — ignore it; only the bell widget listens. The
 * event carries no recipient identity, so every connected browser simply refetches <em>its own</em>
 * unread count: there is nothing to leak about who was notified.
 */
public class NotificationService {

    /** {@link EntityChangedEvent#entityType()} for an inbox change; only the bell reacts to it. */
    static final String ENTITY_TYPE = "notification";

    private final NotificationStore store;
    private final CurrentUserResolver currentUser;
    private final UiAccessService access;
    private final ApplicationEventPublisher events;
    private final NotificationProperties properties;

    public NotificationService(NotificationStore store, CurrentUserResolver currentUser,
                               UiAccessService access, ApplicationEventPublisher events,
                               NotificationProperties properties) {
        this.store = store;
        this.currentUser = currentUser;
        this.access = access;
        this.events = events;
        this.properties = properties;
    }

    // ---------------------------------------------------------------------
    // Producing notifications
    // ---------------------------------------------------------------------

    /** Start composing a notification. Call {@code send()} on the returned builder to store it. */
    public Builder compose() {
        return new Builder(this);
    }

    /** Notify one named user. */
    public Notification notifyUser(String username, String title, String body) {
        return compose().toUser(username).title(title).body(body).send();
    }

    /** Notify everyone holding {@code role}. */
    public Notification notifyRole(String role, String title, String body) {
        return compose().toRole(role).title(title).body(body).send();
    }

    /** Notify whoever's account links to the catalog record {@code recordId}. */
    public Notification notifyRecord(String recordId, String title, String body) {
        return compose().toRecord(recordId).title(title).body(body).send();
    }

    Notification send(Builder b) {
        Objects.requireNonNull(b.recipient, "recipient is required");
        if (b.title == null || b.title.isBlank()) {
            throw new IllegalArgumentException("notification title is required");
        }
        Notification n = new Notification(
                UUID.randomUUID(),
                b.recipient,
                b.title.strip(),
                b.body,
                b.severity == null ? NotificationSeverity.INFO : b.severity,
                b.category,
                b.link,
                LocalDateTime.now(),
                false,
                null);
        store.insert(n);
        // The insert has committed (its own JDBI transaction), so a browser reacting to the ping reads
        // the new row back, not a phantom. Fan a recipient-free signal to every open UI stream and a
        // typed app event for other delivery channels (mail, push).
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.CREATED, ENTITY_TYPE, ENTITY_TYPE, n.id(), null));
        events.publishEvent(new NotificationCreatedEvent(n));
        return n;
    }

    // ---------------------------------------------------------------------
    // Reading / mutating the current user's inbox
    // ---------------------------------------------------------------------

    /** The caller's inbox, newest first, optionally unread-only, capped at {@code onec.notifications.inbox-limit}. */
    public List<Notification> inbox(Principal principal, boolean unreadOnly) {
        return store.list(recipientsFor(principal), unreadOnly, properties.getInboxLimit());
    }

    /** How many unread notifications the caller has. */
    public long unreadCount(Principal principal) {
        return store.countUnread(recipientsFor(principal));
    }

    /** Mark one of the caller's notifications read; false when it isn't theirs / already read / gone. */
    public boolean markRead(Principal principal, UUID id) {
        boolean changed = store.markRead(id, recipientsFor(principal)) > 0;
        if (changed) {
            pingInbox(id);
        }
        return changed;
    }

    /** Mark every unread notification in the caller's inbox read; returns how many changed. */
    public int markAllRead(Principal principal) {
        int changed = store.markAllRead(recipientsFor(principal));
        if (changed > 0) {
            pingInbox(null);
        }
        return changed;
    }

    /** Remove one of the caller's notifications; false when it isn't theirs / already gone. */
    public boolean dismiss(Principal principal, UUID id) {
        boolean changed = store.delete(id, recipientsFor(principal)) > 0;
        if (changed) {
            pingInbox(id);
        }
        return changed;
    }

    /**
     * The recipient keys the authenticated principal can see: their username, their linked identity
     * record (so {@code @}-mentions reach them), and each of their roles. Notifications addressed to any
     * of these land in this user's inbox.
     */
    Set<String> recipientsFor(Principal principal) {
        Set<String> keys = new LinkedHashSet<>();
        CurrentUser me = currentUser.resolve(principal);
        if (me.username() != null && !me.username().isBlank()) {
            keys.add(NotificationRecipients.user(me.username()));
        }
        if (me.recordId() != null && !me.recordId().isBlank()) {
            keys.add(NotificationRecipients.record(me.recordId()));
        }
        for (String role : access.roles(principal)) {
            keys.add(NotificationRecipients.role(role));
        }
        return keys;
    }

    /** Announce an inbox change (read/dismiss) so the bell re-syncs its count in other open tabs. */
    private void pingInbox(UUID id) {
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.CHANGED, ENTITY_TYPE, ENTITY_TYPE, id, null));
    }

    /** Fluent composer for a single notification. Obtain one from {@link NotificationService#notify()}. */
    public static final class Builder {

        private final NotificationService service;
        private String recipient;
        private String title;
        private String body;
        private NotificationSeverity severity = NotificationSeverity.INFO;
        private String category;
        private String link;

        private Builder(NotificationService service) {
            this.service = service;
        }

        /** Address by raw recipient key (see {@link NotificationRecipients}); the typed helpers below are preferred. */
        public Builder recipient(String recipientKey) {
            this.recipient = recipientKey;
            return this;
        }

        public Builder toUser(String username) {
            return recipient(NotificationRecipients.user(username));
        }

        public Builder toRole(String role) {
            return recipient(NotificationRecipients.role(role));
        }

        public Builder toRecord(String recordId) {
            return recipient(NotificationRecipients.record(recordId));
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder severity(NotificationSeverity severity) {
            this.severity = severity;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        /** Optional {@code onec://kind/name/id} route the bell deep-links to. */
        public Builder link(String link) {
            this.link = link;
            return this;
        }

        /** Persist the notification and return it as stored. */
        public Notification send() {
            return service.send(this);
        }
    }
}
