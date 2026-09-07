package su.onno.crm.ui;

import org.springframework.stereotype.Component;
import su.onno.crm.domain.Conversation;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

@Component
public class InboxPage implements Page {
    private final su.onno.crm.service.CrmInboxWorkspaceService workspaces;
    public InboxPage(su.onno.crm.service.CrmInboxWorkspaceService workspaces){this.workspaces=workspaces;}

    @Override
    public String route() {
        return "/inbox";
    }

    @Override
    public void compose(PageBuilder page) {
        page.bare();
        if(workspaces.enabled()) page.widget("Unified inbox").type("crmInboxWorkspaces");
        else page.list(Conversation.class, list -> list.fill());
    }
}
