package com.onec.ui.notifications;

import com.onec.model.DocumentObject;
import com.onec.posting.DocumentPostedEvent;

import org.springframework.context.event.EventListener;

/**
 * Notifies a configured set of roles when a document is posted. This is the framework-prescribed way
 * to react to a post with Spring access (an {@code @EventListener} on {@link DocumentPostedEvent}),
 * rather than from inside {@code handlePosting} — which runs in the posting transaction and should only
 * write register movements.
 *
 * <p>Off by default and inert until {@code onec.notifications.posting.roles} names at least one role,
 * because whether a post is noteworthy — and to whom — is entirely app-specific. An app that wants
 * richer rules (notify a specific user, only certain document types) should turn this off and register
 * its own listener calling {@link NotificationService}.
 */
public class PostNotificationListener {

    private final NotificationService notifications;
    private final NotificationProperties properties;

    public PostNotificationListener(NotificationService notifications, NotificationProperties properties) {
        this.notifications = notifications;
        this.properties = properties;
    }

    @EventListener
    public void onPosted(DocumentPostedEvent event) {
        NotificationProperties.Posting posting = properties.getPosting();
        if (!posting.isEnabled() || posting.getRoles().isEmpty()) {
            return;
        }
        DocumentObject document = event.document();
        if (document == null) {
            return;
        }
        String label = document.getClass().getSimpleName();
        String number = document.getNumber();
        String body = (number == null || number.isBlank())
                ? "A " + label + " was posted."
                : label + " " + number + " was posted.";

        for (String role : posting.getRoles()) {
            notifications.compose()
                    .toRole(role)
                    .title("Document posted")
                    .body(body)
                    .severity(NotificationSeverity.SUCCESS)
                    .category("posting")
                    .send();
        }
    }
}
