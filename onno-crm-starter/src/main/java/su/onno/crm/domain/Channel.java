package su.onno.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "CrmChannels", title = "Channel")
public enum Channel {
    @EnumLabel(value = "Telegram", color = "#229ED9") TELEGRAM,
    @EnumLabel(value = "WhatsApp", color = "#25D366") WHATSAPP,
    @EnumLabel(value = "Email", color = "#6366F1") EMAIL,
    @EnumLabel(value = "Web chat", color = "#8B5CF6") WEB_CHAT,
    @EnumLabel(value = "Phone", color = "#F59E0B") PHONE
}
