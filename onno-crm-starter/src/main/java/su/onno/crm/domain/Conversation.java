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
import su.onno.ui.notifications.AssigneeField;

@Catalog(name = "CrmConversations", title = "Conversation", codePrefix = "CV-", context = "CRM")
@AccessControl(readRoles = {"CRM_AGENT", "CRM_MANAGER"}, writeRoles = {"CRM_AGENT", "CRM_MANAGER"})
@Getter
@Setter
public class Conversation extends CatalogObject implements Validated {

    @Attribute(displayName = "Customer", required = true)
    private Ref<Customer> customer;

    @Attribute(displayName = "Inbox", required = true)
    private Ref<Inbox> inbox;

    @Attribute(displayName = "Channel", required = true)
    private Channel channel;

    @Attribute(displayName = "Assigned to")
    @AssigneeField
    private Ref<Agent> assignee;

    @Attribute(displayName = "Status")
    private Ref<ChatStatus> status;

    @Attribute(displayName = "Priority", required = true)
    private ConversationPriority priority = ConversationPriority.NORMAL;

    @Attribute(displayName = "Subject", required = true, length = 240)
    private String subject;

    @Attribute(displayName = "Last message at", required = true)
    private LocalDateTime lastMessageAt = LocalDateTime.now();

    @Attribute(displayName = "Last message", length = 500)
    private String lastMessagePreview;

    @Attribute(displayName = "Unread messages", min = 0)
    private int unreadCount;

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                BusinessRule.onField("customer", "Choose a customer", () -> customer != null),
                BusinessRule.onField("inbox", "Choose an inbox", () -> inbox != null),
                BusinessRule.onField("subject", "Enter a subject", () -> subject != null && !subject.isBlank()));
    }
}
