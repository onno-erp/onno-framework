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

    /** Connectivity and reply capability are independent (a connected channel may be read-only). */
    record Connection(boolean connected, String label, int maxTextLength,
                      ReplyCapability replyCapability, String replyReason) {
        public Connection {
            java.util.Objects.requireNonNull(replyCapability);
            replyReason=replyReason==null?"":replyReason;
        }
        public Connection(boolean connected, String label, int maxTextLength) {
            this(connected,label,maxTextLength,ReplyCapability.AVAILABLE,"");
        }
        public boolean canSend() { return connected && replyCapability==ReplyCapability.AVAILABLE; }
        public String unavailableReason() {
            if(!connected)return label;
            if(!replyReason.isBlank())return replyReason;
            return replyCapability==ReplyCapability.READ_ONLY?"This channel is read-only.":"The reply window has closed.";
        }
    }
}
