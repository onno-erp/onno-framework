package su.onno.crm.service;

import java.security.Principal;
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
    private final CrmAttachments attachments;
    private final CrmConversationStatuses statuses;
    private final org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents;

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationService(ConversationRepository conversations, ConversationMessageRepository messages,
            List<CrmMessageTransport> transports, CrmAttachments attachments, CrmConversationStatuses statuses,
            org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents) {
        this(conversations, messages, new CrmMessageRouter(transports), attachments, statuses, agents);
    }

    public ConversationService(
            ConversationRepository conversations,
            ConversationMessageRepository messages,
            CrmMessageTransport transport, CrmAttachments attachments, CrmConversationStatuses statuses,
            org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents
    ) {
        this.conversations = conversations;
        this.messages = messages;
        this.transport = transport; this.attachments = attachments;
        this.statuses = statuses;this.agents = agents;
    }

    @Transactional
    public Conversation changeStatus(UUID id, UUID status) {
        var conversation = requireConversation(id);
        conversation.setStatus(statuses.active(status));
        return conversations.save(conversation);
    }

    public List<ConversationMessage> messages(UUID conversationId) {
        requireConversation(conversationId);
        return messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
                Ref.of(Conversation.class, conversationId));
    }

    /**
     * A reply as the agent composed it.
     *
     * <p>A record rather than four positional arguments: {@code authorName} and {@code authorId} are
     * both strings and mean entirely different things, so a transposition at a call site would
     * compile and quietly attach the wrong identity to a message.
     *
     * @param files media URLs this application's upload endpoint issued; a reply may be files alone,
     *              because a quote sent with no covering note is still a message. The channel
     *              decides whether files travel at all, and how many.
     */
    /**
     * @param sender the id of one of the connection's {@link CrmMessageTransport.Connection#senders()}
     *               to send from; null sends from the connection's default sender.
     */
    public record Reply(String body, List<String> files, String authorName, String authorId, String subject, String recipient,
                        String sender) {
        public Reply {
            files = files == null ? List.of() : List.copyOf(files);
        }
        public Reply(String body, List<String> files, String authorName, String authorId, String subject, String recipient) {
            this(body, files, authorName, authorId, subject, recipient, null);
        }
        public Reply(String body, List<String> files, String authorName, String authorId, String subject) { this(body,files,authorName,authorId,subject,null); }
        public Reply(String body, List<String> files, String authorName, String authorId) { this(body, files, authorName, authorId, null); }
        public Reply(String body, String authorName) { this(body, List.of(), authorName, null); }
    }

    /** Kept for callers that have no signed-in agent to attribute the reply to. */
    @Transactional
    public ConversationMessage addMessage(UUID conversationId, String body, String authorName) {
        return addMessage(conversationId, new Reply(body, authorName));
    }

    /** Kept for callers that attribute the reply but carry no files. */
    @Transactional
    public ConversationMessage addMessage(UUID conversationId, String body, String authorName, String authorId) {
        return addMessage(conversationId, new Reply(body, List.of(), authorName, authorId));
    }

    @Transactional
    public ConversationMessage addMessage(UUID conversationId, Reply reply) {
        return addMessage(conversationId, reply, null);
    }

    /**
     * A reply composed by {@code principal}, checked against the connection as that principal sees
     * it ({@link CrmMessageTransport#connection(Conversation, Principal)}), so a sender offered only
     * to them (their own personal mailbox) is accepted. A null principal uses the plain connection.
     */
    @Transactional
    public ConversationMessage addMessage(UUID conversationId, Reply reply, Principal principal) {
        Conversation conversation = requireConversation(conversationId);
        String subject = reply.subject();
        if (subject != null) {
            if (!su.onno.crm.domain.Channel.EMAIL.equals(conversation.getChannel()))
                throw new IllegalArgumentException("Only email messages have a subject");
            if (subject.contains("\r") || subject.contains("\n") || subject.length() > 998)
                throw new IllegalArgumentException("Subject must be one line, at most 998 characters");
            subject = subject.strip();
        }
        String normalized = reply.body() == null ? "" : reply.body().trim();
        List<String> accepted = attachments.accept(reply.files());
        if (normalized.isEmpty() && accepted.isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        var connection = connection(conversation, principal);
        if (!connection.canSend()) throw new IllegalArgumentException(connection.unavailableReason());
        String sender = sender(connection, reply.sender());
        String recipient=reply.recipient()==null?connection.recipient():reply.recipient().strip();
        if(recipient!=null) {
            if(!su.onno.crm.domain.Channel.EMAIL.equals(conversation.getChannel()))throw new IllegalArgumentException("Only email replies have a recipient");
            if(recipient.length()>320 || recipient.contains("\r") || recipient.contains("\n"))throw new IllegalArgumentException("Invalid email recipient");
            String chosen=recipient;
            recipient=connection.recipients().stream().filter(address->address.equalsIgnoreCase(chosen)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Choose an email address linked to this contact"));
        }
        if (normalized.length() > Math.min(8000, connection.maxTextLength())) {
            throw new IllegalArgumentException("Message is too long (maximum " + connection.maxTextLength() + " characters)");
        }
        if (!accepted.isEmpty()) {
            if (!connection.canAttach()) throw new IllegalArgumentException(connection.attachmentUnavailableReason());
            if (accepted.size() > connection.maxAttachments())
                throw new IllegalArgumentException("This channel carries at most "
                        + connection.maxAttachments() + " file(s) per message");
        }
        LocalDateTime now = LocalDateTime.now();
        ConversationMessage message = new ConversationMessage();
        message.setConversation(Ref.of(Conversation.class, conversationId));
        message.setChannel(conversation.getChannel());
        message.setAuthorName(reply.authorName() == null || reply.authorName().isBlank()
                ? "CRM agent" : reply.authorName());
        message.setAuthorId(reply.authorId() == null || reply.authorId().isBlank() ? null : reply.authorId());
        message.setBody(normalized);
        message.setSubject(subject);
        message.setRecipient(recipient);
        message.setSenderAccount(sender);
        message.setAttachments(attachments.store(accepted));
        // A files-only message still needs something to read in a conversation list, so the preview
        // names the files rather than leaving the row blank.
        String summary = normalized.isEmpty() ? filesSummary(accepted) : normalized;
        message.setDescription(preview(summary, 100));
        message.setSentAt(now);
        message.setKind(MessageKind.AGENT_REPLY);
        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(DeliveryStatus.QUEUED);
        conversation.setStatus(statuses.reply());
        ConversationMessage saved = messages.save(message);
        transport.enqueue(conversation, saved);

        conversation.setLastMessageAt(now);
        conversation.setLastMessagePreview(preview(summary, 180));
        conversation.setUnreadCount(0);
        conversations.save(conversation);
        return saved;
    }

    private String filesSummary(List<String> accepted) {
        if (accepted.size() == 1) return attachments.describe(accepted.getFirst()).filename();
        return accepted.size() + " files";
    }

    public CrmMessageTransport.Connection delivery(UUID conversationId) {
        return transport.connection(requireConversation(conversationId));
    }

    /** The connection as {@code principal} would reply on it; see {@link #addMessage(UUID, Reply, Principal)}. */
    public CrmMessageTransport.Connection delivery(UUID conversationId, Principal principal) {
        return connection(requireConversation(conversationId), principal);
    }

    private CrmMessageTransport.Connection connection(Conversation conversation, Principal principal) {
        return principal == null ? transport.connection(conversation) : transport.connection(conversation, principal);
    }

    /**
     * The sender a reply is queued with: the chosen one when the connection offers it, otherwise the
     * connection's default (null when it offers no choice). Kept on the message, like the recipient,
     * so delivery and retries send from the same account.
     */
    private static String sender(CrmMessageTransport.Connection connection, String chosen) {
        if (chosen == null || chosen.isBlank()) return connection.senders().isEmpty() ? null : connection.sender();
        if (chosen.length() > 320 || chosen.contains("\r") || chosen.contains("\n"))
            throw new IllegalArgumentException("Choose one of the offered sender accounts");
        var offered = connection.offeredSender(chosen);
        if (offered == null) throw new IllegalArgumentException("Choose one of the offered sender accounts");
        return offered.id();
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
            // Providers mirror it after commit (Gmail drops its UNREAD label); best-effort.
            transport.markedRead(conversation);
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
