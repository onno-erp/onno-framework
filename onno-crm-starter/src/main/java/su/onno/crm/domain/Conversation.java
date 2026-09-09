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

@Catalog(name = "CrmConversations", title = "Conversation", codePrefix = "CV-", context = "CRM")
@AccessControl(readRoles = {"ADMIN"}, writeRoles = {"ADMIN"})
@Getter
@Setter
public class Conversation extends CatalogObject implements Validated {

    @Attribute(displayName = "Customer", required = true)
    // UUID is scoped to CrmCustomerBinding.catalog(); CRM never introduces a shadow customer.
    private java.util.UUID customer;

    @Attribute(displayName = "Inbox", required = true)
    private Ref<Inbox> inbox;

    @Attribute(displayName = "Channel", required = true)
    private String channel;

    @Attribute(displayName = "Assigned to")
    private java.util.UUID assignee;

    @Attribute(displayName = "Status")
    private java.util.UUID status;

    @Attribute(displayName = "Priority")
    private java.util.UUID priority;

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
