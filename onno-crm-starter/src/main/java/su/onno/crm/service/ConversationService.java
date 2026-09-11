package su.onno.crm.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
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

    private final CrmMessageTransport transport;
    private final CrmConversationStatuses statuses;
    private final org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents;

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationService(ConversationRepository conversations, ConversationMessageRepository messages,
            List<CrmMessageTransport> transports, CrmConversationStatuses statuses,
            org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents) {
        this(conversations, messages, new CrmMessageRouter(transports), statuses, agents);
    }

    public ConversationService(
            ConversationRepository conversations,
            ConversationMessageRepository messages,
            CrmMessageTransport transport, CrmConversationStatuses statuses,
            org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents
    ) {
        this.conversations = conversations;
        this.messages = messages;
        this.transport = transport; this.statuses = statuses;this.agents = agents;
    }

    public List<ConversationMessage> messages(UUID conversationId) {
        requireConversation(conversationId);
        return messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
                Ref.of(Conversation.class, conversationId));
    }

    /** Kept for callers that have no signed-in agent to attribute the reply to. */
    @Transactional
    public ConversationMessage addMessage(
            UUID conversationId,
            String body,
            String authorName
    ) {
        return addMessage(conversationId, body, authorName, null);
    }

    @Transactional
    public ConversationMessage addMessage(
            UUID conversationId,
            String body,
            String authorName,
            String authorId
    ) {
        Conversation conversation = requireConversation(conversationId);
        String normalized = body == null ? "" : body.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        var connection = transport.connection(conversation);
        if (!connection.canSend()) throw new IllegalArgumentException(connection.unavailableReason());
        if (normalized.length() > Math.min(8000, connection.maxTextLength())) {
            throw new IllegalArgumentException("Message is too long (maximum " + connection.maxTextLength() + " characters)");
        }
        LocalDateTime now = LocalDateTime.now();
        ConversationMessage message = new ConversationMessage();
        message.setConversation(Ref.of(Conversation.class, conversationId));
        message.setChannel(conversation.getChannel());
        message.setAuthorName(authorName == null || authorName.isBlank() ? "CRM agent" : authorName);
        message.setAuthorId(authorId == null || authorId.isBlank() ? null : authorId);
        message.setBody(normalized);
        message.setDescription(preview(normalized, 100));
        message.setSentAt(now);
        message.setKind(MessageKind.AGENT_REPLY);
        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(DeliveryStatus.QUEUED);
        conversation.setStatus(statuses.reply());
        ConversationMessage saved = messages.save(message);
        transport.enqueue(conversation, saved);

        conversation.setLastMessageAt(now);
        conversation.setLastMessagePreview(preview(normalized, 180));
        conversation.setUnreadCount(0);
        conversations.save(conversation);
        return saved;
    }

    public CrmMessageTransport.Connection delivery(UUID conversationId) {
        return transport.connection(requireConversation(conversationId));
    }

    @Transactional
    public ConversationMessage retryMessage(UUID conversationId, UUID messageId) {
        Conversation conversation = requireConversation(conversationId);
        ConversationMessage message = messages.findActiveById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found"));
        if (message.getConversation() == null || !conversationId.equals(message.getConversation().id())
                || message.getKind() != MessageKind.AGENT_REPLY
                || message.getDirection() != MessageDirection.OUTBOUND
                || message.getDeliveryStatus() != DeliveryStatus.FAILED) {
            throw new IllegalArgumentException("Only a failed reply in this conversation can be retried");
        }
        var connection = transport.connection(conversation);
        if (!connection.canSend()) throw new IllegalArgumentException(connection.unavailableReason());
        transport.enqueue(conversation, message);
        message.setDeliveryStatus(DeliveryStatus.QUEUED);
        return messages.save(message);
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

    private Conversation requireConversation(UUID id) {
        return conversations.findActiveById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + id));
    }

    private static String preview(String value, int limit) {
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= limit ? oneLine : oneLine.substring(0, limit - 1) + "…";
    }
}
