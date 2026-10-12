package su.onno.crm.channels;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import su.onno.crm.domain.*;
import static org.assertj.core.api.Assertions.*;
class ExternalReplyReadStateTest {
    final LocalDateTime time=LocalDateTime.of(2026,10,5,12,0);
    ConversationMessage message(MessageDirection direction,int seconds) {
        var m=new ConversationMessage();m.setDirection(direction);m.setSentAt(time.plusSeconds(seconds));m.setDeliveryStatus(DeliveryStatus.SENT);return m;
    }
    @Test void clearsOnlyOlderUnreadMessagesAndNeverRestoresReadMessages() {
        var c=new Conversation();c.setUnreadCount(2);
        var history=List.of(message(MessageDirection.INBOUND,-10),message(MessageDirection.INBOUND,10));
        ExternalReplyReadState.answered(c,message(MessageDirection.OUTBOUND,0),history,ExternalReplyReadState.NO_TIE_BREAK);assertThat(c.getUnreadCount()).isEqualTo(1);
        c.setUnreadCount(0);ExternalReplyReadState.answered(c,message(MessageDirection.OUTBOUND,0),history,ExternalReplyReadState.NO_TIE_BREAK);assertThat(c.getUnreadCount()).isZero();
    }
    @Test void telegramIdsOrderRepliesAndIncomingWithinOneSecond() {
        var older=message(MessageDirection.INBOUND,0);older.setExternalMessageId("connection:500:1");
        var reply=message(MessageDirection.OUTBOUND,0);reply.setExternalMessageId("connection:500:2");
        var newer=message(MessageDirection.INBOUND,0);newer.setExternalMessageId("connection:500:3");
        var c=new Conversation();c.setUnreadCount(2);
        ExternalReplyReadState.answered(c,reply,List.of(older,newer),ExternalReplyReadState.CHAT_MESSAGE_ORDER);assertThat(c.getUnreadCount()).isEqualTo(1);
        assertThat(ExternalReplyReadState.unread(older,List.of(reply),ExternalReplyReadState.CHAT_MESSAGE_ORDER)).isFalse();
        assertThat(ExternalReplyReadState.unread(newer,List.of(reply),ExternalReplyReadState.CHAT_MESSAGE_ORDER)).isTrue();
    }
    @Test void delayedIncomingBeforeSentReplyDoesNotReopenUnreadButNewerDoes() {
        var history=List.of(message(MessageDirection.OUTBOUND,0));
        assertThat(ExternalReplyReadState.unread(message(MessageDirection.INBOUND,-1),history,ExternalReplyReadState.NO_TIE_BREAK)).isFalse();
        assertThat(ExternalReplyReadState.unread(message(MessageDirection.INBOUND,1),history,ExternalReplyReadState.NO_TIE_BREAK)).isTrue();
        history.getFirst().setDeliveryStatus(DeliveryStatus.FAILED);
        assertThat(ExternalReplyReadState.unread(message(MessageDirection.INBOUND,-1),history,ExternalReplyReadState.NO_TIE_BREAK)).isTrue();
    }
}
