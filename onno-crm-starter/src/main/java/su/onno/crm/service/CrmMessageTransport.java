package su.onno.crm.service;

import java.security.Principal;
import java.util.List;
import java.util.Objects;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;

/** Provider boundary. Implementations enqueue durably in the caller's database transaction. */
public interface CrmMessageTransport {
    /** Whether this provider handles the conversation's channel, even when sending is unavailable. */
    default boolean supports(Conversation conversation) { return true; }

    Connection connection(Conversation conversation);

    /**
     * The connection as {@code principal} would reply on it. Override when a choice depends on who
     * is replying: Gmail adds the principal's own personal mailbox to {@link Connection#senders()}.
     * Defaults to {@link #connection(Conversation)}.
     */
    default Connection connection(Conversation conversation, Principal principal) {
        return connection(conversation);
    }

    /** No network I/O here. A worker delivers after commit. Also used for explicit failed-message retry. */
    void enqueue(Conversation conversation, ConversationMessage message);

    /**
     * The CRM has just marked this conversation read (its unread count dropped to zero). A provider
     * may mirror that in the remote mailbox. Called inside the caller's transaction: do no network
     * I/O here; defer it until after commit, and treat it as best-effort. Does nothing by default.
     */
    default void markedRead(Conversation conversation) {}

    enum ReplyCapability { AVAILABLE, READ_ONLY, WINDOW_CLOSED }

    /**
     * A mailbox or account a reply may be sent from. {@code id} is what the composer sends back
     * ({@code from}) and what {@code ConversationMessage.senderAccount} keeps; {@code label} is
     * what a person reads.
     */
    record Sender(String id, String label) {
        public Sender {
            Objects.requireNonNull(id, "id");
            label = label == null || label.isBlank() ? id : label;
        }
    }

    /**
     * Connectivity and reply capability are independent (a connected channel may be read-only).
     *
     * <p>{@code maxAttachments} is how many files this provider will carry on one outbound message;
     * zero means the composer offers no paperclip at all, and {@code attachmentReason} says why, so
     * a channel that simply cannot take files is distinguishable from one that is misconfigured.
     *
     * <p>{@code recipient} is the address a reply goes to by default (may be {@code null}) and
     * {@code recipients} the addresses the composer may choose from; set both with {@link #withRecipients}.
     *
     * <p>{@code senders} are the accounts a reply may be sent from (empty means the provider offers
     * no choice) and {@code sender} the default one's id; set both with {@link #withSenders}.
     *
     * <p>{@code cc} is who a reply copies by default, as RFC 5322 entries ({@code "Name <address>"}): for an email,
     * everyone else on the latest received email (reply-all), never the studio's own mailboxes or the recipient.
     * Empty when there is nobody, or the channel has no Cc; set it with {@link #withCc}. The composer offers it as a
     * suggestion; a reply copies only the Cc it sends.
     */
    record Connection(boolean connected, String label, int maxTextLength,
                      ReplyCapability replyCapability, String replyReason,
                      int maxAttachments, String attachmentReason,
                      String recipient, List<String> recipients,
                      List<Sender> senders, String sender, List<String> cc) {
        public Connection {
            Objects.requireNonNull(replyCapability);
            replyReason=replyReason==null?"":replyReason;
            attachmentReason=attachmentReason==null?"":attachmentReason;
            maxAttachments=Math.max(0,maxAttachments);
            recipients=recipients==null?List.of():List.copyOf(recipients);
            senders=senders==null?List.of():List.copyOf(senders);
            if(sender==null && !senders.isEmpty())sender=senders.getFirst().id();
            cc=cc==null?List.of():List.copyOf(cc);
        }
        public Connection(boolean connected, String label, int maxTextLength,
                          ReplyCapability replyCapability, String replyReason,
                          int maxAttachments, String attachmentReason,
                          String recipient, List<String> recipients,
                          List<Sender> senders, String sender) {
            this(connected,label,maxTextLength,replyCapability,replyReason,maxAttachments,attachmentReason,recipient,recipients,senders,sender,List.of());
        }
        public Connection(boolean connected, String label, int maxTextLength,
                          ReplyCapability replyCapability, String replyReason,
                          int maxAttachments, String attachmentReason,
                          String recipient, List<String> recipients) {
            this(connected,label,maxTextLength,replyCapability,replyReason,maxAttachments,attachmentReason,recipient,recipients,List.of(),null);
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
            return new Connection(connected,label,maxTextLength,replyCapability,replyReason,limit,reason,recipient,recipients,senders,sender,cc);
        }
        /** The same connection, declaring the default reply address and the addresses to choose from. */
        public Connection withRecipients(String recipient, List<String> recipients) {
            return new Connection(connected,label,maxTextLength,replyCapability,replyReason,maxAttachments,attachmentReason,recipient,recipients,senders,sender,cc);
        }
        /**
         * The same connection, declaring the accounts a reply may be sent from and the default one
         * ({@code current}, an id among {@code options}; the first option when null).
         */
        public Connection withSenders(String current, List<Sender> options) {
            return new Connection(connected,label,maxTextLength,replyCapability,replyReason,maxAttachments,attachmentReason,recipient,recipients,options,current,cc);
        }
        /** The same connection, suggesting who a reply copies (see {@link Connection}). */
        public Connection withCc(List<String> cc) {
            return new Connection(connected,label,maxTextLength,replyCapability,replyReason,maxAttachments,attachmentReason,recipient,recipients,senders,sender,cc);
        }
        /** The offered sender with this id, matched without regard to case; null when none is. */
        public Sender offeredSender(String id) {
            if(id==null)return null;
            String wanted=id.strip();
            return senders.stream().filter(s->s.id().equalsIgnoreCase(wanted)).findFirst().orElse(null);
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
