package su.onno.crm.domain;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;

@Catalog(name = "CrmConversationMessages", title = "Conversation message", codePrefix = "MSG-", context = "CRM")
@AccessControl(readRoles = {"ADMIN"}, writeRoles = {"ADMIN"})
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

    @Attribute(displayName = "Body", required = true, length = 8000)
    private String body;

    @Attribute(displayName = "Sent at", required = true)
    private LocalDateTime sentAt = LocalDateTime.now();

    @Attribute(displayName = "Delivery status", required = true)
    private DeliveryStatus deliveryStatus = DeliveryStatus.NOT_APPLICABLE;

    @Attribute(displayName = "External message id", length = 240)
    private String externalMessageId;

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                BusinessRule.onField("conversation", "Choose a conversation", () -> conversation != null),
                BusinessRule.onField("body", "Message cannot be empty", () -> body != null && !body.isBlank()));
    }
}
