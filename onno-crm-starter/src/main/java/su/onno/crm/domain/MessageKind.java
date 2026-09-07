package su.onno.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "CrmMessageKinds", title = "Message kind")
public enum MessageKind {
    @EnumLabel("Customer message") CUSTOMER_MESSAGE,
    @EnumLabel("Agent reply") AGENT_REPLY,
    @EnumLabel("System event") SYSTEM_EVENT
}
