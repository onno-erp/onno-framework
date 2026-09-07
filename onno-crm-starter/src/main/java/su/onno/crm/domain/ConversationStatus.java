package su.onno.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "ConversationStatuses", title = "Conversation status")
public enum ConversationStatus {
    @EnumLabel(value = "Open", color = "#3B82F6") OPEN,
    @EnumLabel(value = "Waiting for customer", color = "#F59E0B") WAITING_CUSTOMER,
    @EnumLabel(value = "Snoozed", color = "#8B5CF6") SNOOZED,
    @EnumLabel(value = "Closed", color = "#64748B") CLOSED
}
