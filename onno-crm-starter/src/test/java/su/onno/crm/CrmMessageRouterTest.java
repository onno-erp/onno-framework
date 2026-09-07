package su.onno.crm;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import su.onno.crm.domain.*;
import su.onno.crm.service.*;

class CrmMessageRouterTest {
    @Test void routesOnlyToTheConnectedProvider() {
        var conversation = new Conversation();
        var message = new ConversationMessage();
        var telegram = mock(CrmMessageTransport.class);
        var gmail = mock(CrmMessageTransport.class);
        when(telegram.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(false, "Not Telegram", 4096));
        when(gmail.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(true, "Gmail", 8000));
        var router = new CrmMessageRouter(List.of(telegram, gmail));
        assertThat(router.connection(conversation).label()).isEqualTo("Gmail");
        router.enqueue(conversation, message);
        verify(gmail).enqueue(conversation, message);
        verify(telegram, never()).enqueue(any(), any());
    }
    @Test void rejectsAmbiguousRoutesBeforeSending() {
        var conversation = new Conversation();
        var one = mock(CrmMessageTransport.class);
        var two = mock(CrmMessageTransport.class);
        when(one.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(true, "one", 8000));
        when(two.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(true, "two", 8000));
        assertThatThrownBy(() -> new CrmMessageRouter(List.of(one, two)).enqueue(conversation, new ConversationMessage()))
            .isInstanceOf(IllegalStateException.class);
        verify(one, never()).enqueue(any(), any());
        verify(two, never()).enqueue(any(), any());
    }
    @Test void noProviderCannotSend() {
        assertThatThrownBy(() -> new CrmMessageRouter(List.of()).enqueue(new Conversation(), new ConversationMessage()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
