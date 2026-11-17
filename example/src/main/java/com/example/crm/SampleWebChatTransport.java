package com.example.crm;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import su.onno.crm.domain.Channel;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.domain.DeliveryStatus;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.service.CrmMessageTransport;

/**
 * The example's website chat: a stand-in channel that "delivers" every reply at once, so the inbox
 * and its composer can be tried locally without connecting Gmail, Telegram or WhatsApp. A real
 * website-chat widget would push the reply to the visitor's browser here instead.
 */
@Component
@Profile("crm")
class SampleWebChatTransport implements CrmMessageTransport {
    private final ConversationMessageRepository messages;

    SampleWebChatTransport(ConversationMessageRepository messages) {
        this.messages = messages;
    }

    @Override
    public boolean supports(Conversation conversation) {
        return Channel.WEB_CHAT.equals(conversation.getChannel());
    }

    @Override
    public Connection connection(Conversation conversation) {
        return new Connection(true, "Website chat", 4000);
    }

    @Override
    public void enqueue(Conversation conversation, ConversationMessage message) {
        message.setDeliveryStatus(DeliveryStatus.DELIVERED);
        messages.save(message);
    }
}
