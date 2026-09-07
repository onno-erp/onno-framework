package su.onno.crm.ui;

import org.springframework.stereotype.Component;
import su.onno.crm.domain.ConversationMessage;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class ConversationMessageView implements EntityView<ConversationMessage> {

    @Override public Class<ConversationMessage> entity() { return ConversationMessage.class; }

    @Override
    public void list(ListSpec<ConversationMessage> list) {
        list.columns(ConversationMessage::getSentAt, ConversationMessage::getConversation,
                        ConversationMessage::getKind, ConversationMessage::getAuthorName,
                        ConversationMessage::getBody, ConversationMessage::getDeliveryStatus)
                .sortBy(ConversationMessage::getSentAt, true);
    }

    @Override
    public void fields(EntityConfigBuilder<ConversationMessage> fields) {
        fields.field(ConversationMessage::getConversation).order(0)
                .field(ConversationMessage::getKind).order(1).width("1/2")
                .field(ConversationMessage::getDirection).order(2).width("1/2")
                .field(ConversationMessage::getChannel).order(3).width("1/2")
                .field(ConversationMessage::getDeliveryStatus).order(4).width("1/2")
                .field(ConversationMessage::getAuthorName).order(5).width("1/2")
                .field(ConversationMessage::getSentAt).order(6).width("1/2").format("dd MMM yyyy HH:mm")
                .field(ConversationMessage::getBody).order(7).widget("textarea")
                .field(ConversationMessage::getExternalMessageId).order(8);
    }
}
