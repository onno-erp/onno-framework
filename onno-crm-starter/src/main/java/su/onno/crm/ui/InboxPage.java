package su.onno.crm.ui;

import org.springframework.stereotype.Component;
import su.onno.crm.domain.Conversation;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

@Component
public class InboxPage implements Page {

    @Override
    public String route() {
        return "/inbox";
    }

    @Override
    public void compose(PageBuilder page) {
        page.bare();
        page.list(Conversation.class, list -> list.fill());
    }
}
