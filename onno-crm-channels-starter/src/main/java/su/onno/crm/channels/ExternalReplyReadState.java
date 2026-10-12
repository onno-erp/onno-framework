package su.onno.crm.channels;

import java.util.List;
import java.util.function.BiPredicate;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.domain.DeliveryStatus;
import su.onno.crm.domain.MessageDirection;

/**
 * Reconciles replies sent outside the CRM (from the provider's own app) with the unread count,
 * without restoring messages already read in the CRM.
 *
 * <p><b>Internal to the onno channel adapters.</b> It is public only so adapters in sibling packages
 * can share it; it is not a supported extension API and may change without notice.
 *
 * <p>Messages are ordered by {@code sentAt}. When two timestamps are equal, the caller-supplied
 * tie-break decides; it must answer {@code true} only when it can prove the first message is at or
 * before the second. Ambiguous ties count as "not before", so a potentially newer message is never
 * marked read.
 */
public final class ExternalReplyReadState {
    /** For providers whose timestamps are precise enough that ties are not resolved. */
    public static final BiPredicate<ConversationMessage, ConversationMessage> NO_TIE_BREAK = (a, b) -> false;

    /**
     * For external ids laid out as {@code <scope>:<chat>:<numeric message id>} (Telegram): within one
     * scope and chat, message ids grow monotonically, which orders messages sent in the same second.
     */
    public static final BiPredicate<ConversationMessage, ConversationMessage> CHAT_MESSAGE_ORDER =
            ExternalReplyReadState::chatMessageAtOrBefore;

    private ExternalReplyReadState() {}

    /** An external reply was imported: clear unread messages older than it, never raise the count. */
    public static void answered(Conversation conversation, ConversationMessage reply, List<ConversationMessage> history,
                                BiPredicate<ConversationMessage, ConversationMessage> tieBreak) {
        long newer = history.stream()
                .filter(m -> m.getDirection() == MessageDirection.INBOUND && !atOrBefore(m, reply, tieBreak))
                .count();
        conversation.setUnreadCount(Math.min(conversation.getUnreadCount(), (int) newer));
    }

    /** Whether an incoming message is unread: no sent reply in the history is at or after it. */
    public static boolean unread(ConversationMessage incoming, List<ConversationMessage> history,
                                 BiPredicate<ConversationMessage, ConversationMessage> tieBreak) {
        return history.stream().noneMatch(m -> m.getDirection() == MessageDirection.OUTBOUND
                && m.getDeliveryStatus() == DeliveryStatus.SENT && atOrBefore(incoming, m, tieBreak));
    }

    private static boolean atOrBefore(ConversationMessage incoming, ConversationMessage reply,
                                      BiPredicate<ConversationMessage, ConversationMessage> tieBreak) {
        if (incoming.getSentAt() == null || reply.getSentAt() == null) return false;
        int order = incoming.getSentAt().compareTo(reply.getSentAt());
        if (order != 0) return order < 0;
        return tieBreak.test(incoming, reply);
    }

    private static boolean chatMessageAtOrBefore(ConversationMessage first, ConversationMessage second) {
        String a = first.getExternalMessageId();
        String b = second.getExternalMessageId();
        if (a == null || b == null) return false;
        int splitA = a.lastIndexOf(':');
        int splitB = b.lastIndexOf(':');
        if (splitA <= 0 || splitB <= 0 || !a.substring(0, splitA).equals(b.substring(0, splitB))) return false;
        try {
            return Long.parseLong(a.substring(splitA + 1)) <= Long.parseLong(b.substring(splitB + 1));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
