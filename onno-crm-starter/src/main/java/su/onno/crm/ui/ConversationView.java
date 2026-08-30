package su.onno.crm.ui;

import org.springframework.stereotype.Component;
import su.onno.crm.domain.Conversation;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class ConversationView implements EntityView<Conversation> {

    @Override public Class<Conversation> entity() { return Conversation.class; }

    @Override
    public void list(ListSpec<Conversation> list) {
        list.title("Unified inbox");
        list.columns(Conversation::getCustomer, Conversation::getChannel, Conversation::getSubject,
                        Conversation::getStatus, Conversation::getPriority, Conversation::getAssignee,
                        Conversation::getLastMessageAt, Conversation::getUnreadCount)
                .sortBy(Conversation::getLastMessageAt, true);
        list.filter(Conversation::getStatus).label("Status").multiOptions();
        list.filter(Conversation::getChannel).label("Channel").multiOptions();
        list.filter(Conversation::getPriority).label("Priority").multiOptions();
        list.custom("crmInbox").label("Inbox").defaultView();
    }

    @Override
    public void fields(EntityConfigBuilder<Conversation> fields) {
        fields.field(Conversation::getCustomer).order(0).group("Conversation").width("1/2")
                .field(Conversation::getInbox).order(1).group("Conversation").width("1/2")
                .field(Conversation::getSubject).order(2).group("Conversation")
                .field(Conversation::getChannel).order(3).group("Routing").width("1/2")
                .field(Conversation::getAssignee).order(4).group("Routing").width("1/2")
                .field(Conversation::getStatus).order(5).group("Routing").width("1/2")
                .field(Conversation::getPriority).order(6).group("Routing").width("1/2")
                .field(Conversation::getLastMessageAt).order(20).group("Activity").format("dd MMM yyyy HH:mm")
                .field(Conversation::getLastMessagePreview).order(21).group("Activity").widget("textarea")
                .field(Conversation::getUnreadCount).order(22).group("Activity");
    }

    @Override public boolean comments() { return true; }
}
