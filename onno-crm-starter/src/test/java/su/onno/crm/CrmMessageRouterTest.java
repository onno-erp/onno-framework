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
        when(gmail.supports(conversation)).thenReturn(true);
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
        when(one.supports(conversation)).thenReturn(true);
        when(two.supports(conversation)).thenReturn(true);
        when(one.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(true, "one", 8000));
        when(two.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(true, "two", 8000));
        assertThatThrownBy(() -> new CrmMessageRouter(List.of(one, two)).enqueue(conversation, new ConversationMessage()))
            .isInstanceOf(IllegalStateException.class);
        verify(one, never()).enqueue(any(), any());
        verify(two, never()).enqueue(any(), any());
    }
    @Test void preservesUnavailableReasonWithMultipleInstalledProviders() {
        var conversation = new Conversation();
        var unrelated = mock(CrmMessageTransport.class);
        var whatsapp = mock(CrmMessageTransport.class);
        when(whatsapp.supports(conversation)).thenReturn(true);
        when(whatsapp.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(false, "Reply window expired", 4096));
        var router = new CrmMessageRouter(List.of(whatsapp, unrelated));
        assertThat(router.connection(conversation).label()).isEqualTo("Reply window expired");
        verify(unrelated, never()).connection(any());
    }
    @Test void connectedReadOnlyProviderCannotEnqueue() {
        var conversation=new Conversation();
        var provider=mock(CrmMessageTransport.class);
        when(provider.supports(conversation)).thenReturn(true);
        when(provider.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(true,"Archive",8000,
            CrmMessageTransport.ReplyCapability.READ_ONLY,"This archive accepts incoming messages only."));
        var router=new CrmMessageRouter(List.of(provider));
        assertThat(router.connection(conversation).connected()).isTrue();
        assertThatThrownBy(()->router.enqueue(conversation,new ConversationMessage())).hasMessageContaining("incoming messages only");
        verify(provider,never()).enqueue(any(),any());
    }
    @Test void noProviderCannotSend() {
        assertThatThrownBy(() -> new CrmMessageRouter(List.of()).enqueue(new Conversation(), new ConversationMessage()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
