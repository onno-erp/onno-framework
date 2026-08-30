package su.onno.crm.ui;

import org.springframework.stereotype.Component;
import su.onno.crm.domain.Agent;
import su.onno.crm.domain.Customer;
import su.onno.crm.domain.Inbox;
import su.onno.crm.domain.Opportunity;
import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;

@Component
public class CrmLayout implements Layout {

    @Override
    public void configure(LayoutSpec layout) {
        // Additive module navigation: the host application continues to own shell branding,
        // identity, theme, and any other bounded workspaces.
        layout.section("Inbox").order(0).icon("messages-square")
                .page("/inbox", "Inbox", "inbox")
                .catalog(Customer.class, "users");

        layout.section("Sales").order(10).icon("chart-no-axes-column-increasing")
                .page("/pipeline", "Pipeline", "columns-3")
                .catalog(Opportunity.class, "badge-dollar-sign");

        layout.section("Configuration").order(20).icon("settings")
                .catalog(Inbox.class, "mail")
                .catalog(Agent.class, "user-cog");

    }
}
