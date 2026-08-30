package su.onno.crm.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import su.onno.crm.domain.Agent;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.domain.ConversationStatus;
import su.onno.crm.domain.DeliveryStatus;
import su.onno.crm.domain.MessageDirection;
import su.onno.crm.domain.MessageKind;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.types.Ref;

/** Application commands behind the high-density inbox workspace. */
@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;

    public ConversationService(
            ConversationRepository conversations,
            ConversationMessageRepository messages
    ) {
        this.conversations = conversations;
        this.messages = messages;
    }

    public List<ConversationMessage> messages(UUID conversationId) {
        requireConversation(conversationId);
        return messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
                Ref.of(Conversation.class, conversationId));
    }

    @Transactional
    public ConversationMessage addMessage(
            UUID conversationId,
            String body,
            String authorName
    ) {
        Conversation conversation = requireConversation(conversationId);
        String normalized = body == null ? "" : body.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        LocalDateTime now = LocalDateTime.now();
        ConversationMessage message = new ConversationMessage();
        message.setConversation(Ref.of(Conversation.class, conversationId));
        message.setChannel(conversation.getChannel());
        message.setAuthorName(authorName == null || authorName.isBlank() ? "CRM agent" : authorName);
        message.setBody(normalized);
        message.setDescription(preview(normalized, 100));
        message.setSentAt(now);
        message.setKind(MessageKind.AGENT_REPLY);
        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(DeliveryStatus.QUEUED);
        conversation.setStatus(ConversationStatus.WAITING_CUSTOMER);
        ConversationMessage saved = messages.save(message);

        conversation.setLastMessageAt(now);
        conversation.setLastMessagePreview(preview(normalized, 180));
        conversation.setUnreadCount(0);
        conversations.save(conversation);
        return saved;
    }

    @Transactional
    public Conversation markRead(UUID conversationId) {
        Conversation conversation = requireConversation(conversationId);
        if (conversation.getUnreadCount() != 0) {
            conversation.setUnreadCount(0);
            conversations.save(conversation);
        }
        return conversation;
    }

    @Transactional
    public Conversation setClosed(UUID conversationId, boolean closed, String actorName) {
        Conversation conversation = requireConversation(conversationId);
        ConversationStatus next = closed ? ConversationStatus.CLOSED : ConversationStatus.OPEN;
        if (conversation.getStatus() == next) {
            return conversation;
        }
        conversation.setStatus(next);
        Conversation saved = conversations.save(conversation);
        addSystemEvent(conversation, displayActor(actorName) + (closed
                ? " closed the conversation"
                : " reopened the conversation"));
        return saved;
    }

    @Transactional
    public Conversation assign(UUID conversationId, UUID agentId, String actorName) {
        Conversation conversation = requireConversation(conversationId);
        UUID currentAgentId = conversation.getAssignee() == null ? null : conversation.getAssignee().id();
        if (java.util.Objects.equals(currentAgentId, agentId)) {
            return conversation;
        }
        conversation.setAssignee(agentId == null ? null : Ref.of(Agent.class, agentId));
        Conversation saved = conversations.save(conversation);
        addSystemEvent(conversation, agentId == null
                ? displayActor(actorName) + " removed the assignee"
                : displayActor(actorName) + " assigned the conversation to themselves");
        return saved;
    }

    private void addSystemEvent(Conversation conversation, String body) {
        ConversationMessage event = new ConversationMessage();
        event.setConversation(Ref.of(Conversation.class, conversation.getId()));
        event.setKind(MessageKind.SYSTEM_EVENT);
        event.setDirection(MessageDirection.INTERNAL);
        event.setChannel(conversation.getChannel());
        event.setAuthorName("CRM");
        event.setBody(body);
        event.setDescription(preview(body, 100));
        event.setSentAt(LocalDateTime.now());
        event.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);
        messages.save(event);
    }

    private static String displayActor(String actorName) {
        return actorName == null || actorName.isBlank() ? "A team member" : actorName;
    }

    private Conversation requireConversation(UUID id) {
        return conversations.findActiveById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + id));
    }

    private static String preview(String value, int limit) {
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= limit ? oneLine : oneLine.substring(0, limit - 1) + "…";
    }
}
