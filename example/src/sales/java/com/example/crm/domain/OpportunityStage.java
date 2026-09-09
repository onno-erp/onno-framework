package com.example.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "OpportunityStages", title = "Opportunity stage")
public enum OpportunityStage {
    @EnumLabel(value = "Discovery", color = "#60A5FA") DISCOVERY,
    @EnumLabel(value = "Qualified", color = "#818CF8") QUALIFIED,
    @EnumLabel(value = "Proposal", color = "#A78BFA") PROPOSAL,
    @EnumLabel(value = "Negotiation", color = "#F59E0B") NEGOTIATION,
    @EnumLabel(value = "Won", color = "#10B981") WON,
    @EnumLabel(value = "Lost", color = "#EF4444") LOST
}
