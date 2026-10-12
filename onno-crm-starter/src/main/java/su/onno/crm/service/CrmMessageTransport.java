package su.onno.crm.service;

import java.util.List;
import java.util.Objects;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;

/** Provider boundary. Implementations enqueue durably in the caller's database transaction. */
public interface CrmMessageTransport {
    /** Whether this provider handles the conversation's channel, even when sending is unavailable. */
    default boolean supports(Conversation conversation) { return true; }

    Connection connection(Conversation conversation);

    /** No network I/O here. A worker delivers after commit. Also used for explicit failed-message retry. */
    void enqueue(Conversation conversation, ConversationMessage message);

    enum ReplyCapability { AVAILABLE, READ_ONLY, WINDOW_CLOSED }

    /**
     * Connectivity and reply capability are independent (a connected channel may be read-only).
     *
     * <p>{@code maxAttachments} is how many files this provider will carry on one outbound message;
     * zero means the composer offers no paperclip at all, and {@code attachmentReason} says why, so
     * a channel that simply cannot take files is distinguishable from one that is misconfigured.
     *
     * <p>{@code recipient} is the address a reply goes to by default (may be {@code null}) and
     * {@code recipients} the addresses the composer may choose from; set both with {@link #withRecipients}.
     */
    record Connection(boolean connected, String label, int maxTextLength,
                      ReplyCapability replyCapability, String replyReason,
                      int maxAttachments, String attachmentReason,
                      String recipient, List<String> recipients) {
        public Connection {
            Objects.requireNonNull(replyCapability);
            replyReason=replyReason==null?"":replyReason;
            attachmentReason=attachmentReason==null?"":attachmentReason;
            maxAttachments=Math.max(0,maxAttachments);
            recipients=recipients==null?List.of():List.copyOf(recipients);
        }
        public Connection(boolean connected, String label, int maxTextLength,
                          ReplyCapability replyCapability, String replyReason,
                          int maxAttachments, String attachmentReason) {
            this(connected,label,maxTextLength,replyCapability,replyReason,maxAttachments,attachmentReason,null,List.of());
        }
        public Connection(boolean connected, String label, int maxTextLength,
                          ReplyCapability replyCapability, String replyReason) {
            this(connected,label,maxTextLength,replyCapability,replyReason,0,"");
        }
        public Connection(boolean connected, String label, int maxTextLength) {
            this(connected,label,maxTextLength,ReplyCapability.AVAILABLE,"");
        }
        /** The same connection, declaring how many files it carries. */
        public Connection withAttachments(int limit, String reason) {
            return new Connection(connected,label,maxTextLength,replyCapability,replyReason,limit,reason,recipient,recipients);
        }
        /** The same connection, declaring the default reply address and the addresses to choose from. */
        public Connection withRecipients(String recipient, List<String> recipients) {
            return new Connection(connected,label,maxTextLength,replyCapability,replyReason,maxAttachments,attachmentReason,recipient,recipients);
        }
        public boolean canSend() { return connected && replyCapability==ReplyCapability.AVAILABLE; }
        public boolean canAttach() { return canSend() && maxAttachments>0; }
        public String unavailableReason() {
            if(!connected)return label;
            if(!replyReason.isBlank())return replyReason;
            return replyCapability==ReplyCapability.READ_ONLY?"This channel is read-only.":"The reply window has closed.";
        }
        public String attachmentUnavailableReason() {
            if(!canSend())return unavailableReason();
            return attachmentReason.isBlank()?"This channel does not carry attachments.":attachmentReason;
        }
    }
}
