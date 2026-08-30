package su.onno.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "MessageDeliveryStatuses", title = "Delivery status")
public enum DeliveryStatus {
    @EnumLabel("Received") RECEIVED,
    @EnumLabel("Queued") QUEUED,
    @EnumLabel(value = "Sent", color = "#3B82F6") SENT,
    @EnumLabel(value = "Delivered", color = "#10B981") DELIVERED,
    @EnumLabel(value = "Failed", color = "#EF4444") FAILED,
    @EnumLabel("Not applicable") NOT_APPLICABLE
}
