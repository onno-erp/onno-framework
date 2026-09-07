package su.onno.crm.ui;
import su.onno.crm.domain.ChatStatus;
import su.onno.ui.*;
public class ChatStatusView implements EntityView<ChatStatus> {
    @Override public Class<ChatStatus> entity(){return ChatStatus.class;}
    @Override public void list(ListSpec<ChatStatus> list){list.title("Conversation statuses");list.columns(ChatStatus::getDescription,ChatStatus::getColor,ChatStatus::isClosed).label(ChatStatus::getDescription,"Name").sortBy(ChatStatus::getDescription);}
    @Override public void fields(EntityConfigBuilder<ChatStatus> fields){fields.field(ChatStatus::getDescription).label("Name").order(0)
        .field(ChatStatus::getColor).widget("color").order(1)
        .field(ChatStatus::isClosed).order(2)
        .field(ChatStatus::isAfterIncoming).group("Automatic transitions").order(10)
        .field(ChatStatus::isAfterReply).group("Automatic transitions").order(11)
        .field(ChatStatus::isAfterClose).group("Automatic transitions").order(12)
        .field(ChatStatus::isAfterReopen).group("Automatic transitions").order(13);}
}
