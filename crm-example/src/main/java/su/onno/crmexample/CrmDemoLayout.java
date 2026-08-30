package su.onno.crmexample;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.crm.domain.Agent;
import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;
import su.onno.ui.NavStyle;

/** Demo-only shell choices; the reusable CRM module deliberately does not own these. */
@Component
@Order(-100)
public class CrmDemoLayout implements Layout {

    @Override
    public void configure(LayoutSpec layout) {
        layout.shell()
                .nav(NavStyle.SIDEBAR)
                .brand("Onno CRM")
                .light(colors -> colors.primary("#6D5EF7").primarySoft("#F0EEFF"))
                .dark(colors -> colors.primary("#8B7CFF").primarySoft("#27224A"));
        layout.identity(Agent.class, "email");
    }
}
