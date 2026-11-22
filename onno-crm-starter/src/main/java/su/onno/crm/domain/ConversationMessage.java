package su.onno.crm.domain;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;

@Catalog(name = "CrmConversationMessages", title = "Conversation message", codePrefix = "MSG-", context = "CRM")
@Getter
@Setter
public class ConversationMessage extends CatalogObject implements Validated {

    @Attribute(displayName = "Conversation", required = true)
    private Ref<Conversation> conversation;

    @Attribute(displayName = "Kind", required = true)
    private MessageKind kind;

    @Attribute(displayName = "Direction", required = true)
    private MessageDirection direction;

    @Attribute(displayName = "Channel", required = true)
    private String channel;

    @Attribute(displayName = "Author", required = true, length = 200)
    private String authorName;

    /**
     * The identity record behind an agent reply, when there is one — the same id
     * {@code CurrentUserResolver.CurrentUser.recordId()} carries and the same key comments store, so
     * an author's avatar resolves through {@code CommentAuthorAvatars} for messages exactly as it
     * already does for internal notes. Null for anything not written by a signed-in agent: every
     * inbound customer message, and system events.
     *
     * <p>Deliberately an id rather than a lookup on {@link #authorName}: a display name is neither
     * unique nor stable, so matching on it would attach the wrong face to a message the day two
     * people share a name or one of them renames.</p>
     */
    @Attribute(displayName = "Author id", length = 64)
    private String authorId;

    /** Optional email subject override, retained for queued delivery and retries. */
    @Attribute(displayName = "Subject", length = 998)
    private String subject;

    /** Recipient captured when queued, so retries cannot switch address. */
    @Attribute(displayName = "Recipient", length = 320)
    private String recipient;

    /**
     * The account an outbound reply is sent from, as the transport's {@code Connection.Sender} id
     * (for Gmail, the mailbox address). Captured when queued, so asynchronous delivery and retries
     * send from the same account. Null for inbound messages and channels that offer no choice.
     */
    @Attribute(displayName = "Sent from", length = 320)
    private String senderAccount;

    @Attribute(displayName = "Body", length = 8000)
    private String body;

    /**
     * Sanitized HTML of an email, when the provider supplied one. {@link #body} keeps the plain-text
     * version for previews, search and channels without HTML; the inbox renders this instead when
     * present, isolated in a sandboxed frame. Transports must sanitize before storing.
     */
    @Attribute(displayName = "HTML body", length = 1_000_000)
    private String bodyHtml;

    @Attribute(displayName = "Sent at", required = true)
    private LocalDateTime sentAt = LocalDateTime.now();

    @Attribute(displayName = "Delivery status", required = true)
    private DeliveryStatus deliveryStatus = DeliveryStatus.NOT_APPLICABLE;

    @Attribute(displayName = "External message id", length = 240)
    private String externalMessageId;

    /**
     * Files sent or received with this message, as newline-joined media URLs — the same shape the
     * framework's {@code gallery} field widget persists, so an attachment is a reference to stored
     * bytes rather than bytes inlined into the row.
     */
    @Attribute(displayName = "Attachments", length = 4000)
    private String attachments;

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                BusinessRule.onField("conversation", "Choose a conversation", () -> conversation != null),
                // A file with no covering note is a message; a message with neither is not.
                BusinessRule.onField("body", "Message cannot be empty",
                        () -> (body != null && !body.isBlank())
                                || (attachments != null && !attachments.isBlank())));
    }
}
