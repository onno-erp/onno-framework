package com.example.domain.enumerations;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/** Code-controlled CRM status shown as a colored pill on customer surfaces. */
@Enumeration(name = "Customer Statuses", title = "Client status")
public enum CustomerStatus {

    @EnumLabel(value = "Lead", color = "#2563EB") LEAD,

    @EnumLabel(value = "Active", color = "#059669") ACTIVE,

    @EnumLabel(value = "VIP", color = "#7C3AED") VIP,

    @EnumLabel(value = "At risk", color = "#D97706") AT_RISK,

    @EnumLabel(value = "Inactive", color = "#6B7280") INACTIVE
}
