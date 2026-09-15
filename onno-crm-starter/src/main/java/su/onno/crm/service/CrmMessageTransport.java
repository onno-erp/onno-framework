package su.onno.crm.service;

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
     */
    record Connection(boolean connected, String label, int maxTextLength,
                      ReplyCapability replyCapability, String replyReason,
                      int maxAttachments, String attachmentReason) {
        public Connection {
            java.util.Objects.requireNonNull(replyCapability);
            replyReason=replyReason==null?"":replyReason;
            attachmentReason=attachmentReason==null?"":attachmentReason;
            maxAttachments=Math.max(0,maxAttachments);
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
            return new Connection(connected,label,maxTextLength,replyCapability,replyReason,limit,reason);
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
