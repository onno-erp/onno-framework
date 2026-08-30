package su.onno.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "ConversationPriorities", title = "Priority")
public enum ConversationPriority {
    @EnumLabel("Low") LOW,
    @EnumLabel(value = "Normal", color = "#64748B") NORMAL,
    @EnumLabel(value = "High", color = "#F97316") HIGH,
    @EnumLabel(value = "Urgent", color = "#DC2626") URGENT
}
