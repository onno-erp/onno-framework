package su.onno.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/** Legacy identifiers retained for migration compatibility; choices now live in LifecycleStage. */
@Enumeration(name = "CustomerStages", title = "Lifecycle stage")
public enum CustomerStage {
    @EnumLabel(value = "New lead", color = "#60A5FA") NEW_LEAD,
    @EnumLabel(value = "Qualified", color = "#A78BFA") QUALIFIED,
    @EnumLabel(value = "Hot lead", color = "#F97316") HOT_LEAD,
    @EnumLabel(value = "Customer", color = "#10B981") CUSTOMER,
    @EnumLabel(value = "Churn risk", color = "#EF4444") CHURN_RISK
}
