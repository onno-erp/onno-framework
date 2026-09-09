package su.onno.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "CrmMessageDirections", title = "Direction")
public enum MessageDirection {
    @EnumLabel("Inbound") INBOUND,
    @EnumLabel("Outbound") OUTBOUND,
    @EnumLabel("Internal") INTERNAL
}
