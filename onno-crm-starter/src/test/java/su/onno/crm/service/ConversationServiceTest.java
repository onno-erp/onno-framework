package su.onno.crm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import su.onno.crm.domain.Channel;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.domain.ConversationStatus;
import su.onno.crm.domain.DeliveryStatus;
import su.onno.crm.domain.MessageDirection;
import su.onno.crm.domain.MessageKind;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock ConversationRepository conversations;
    @Mock ConversationMessageRepository messages;

    private ConversationService service;
    private UUID conversationId;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversations, messages);
        conversationId = UUID.randomUUID();
        conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setChannel(Channel.EMAIL);
        conversation.setStatus(ConversationStatus.OPEN);
        conversation.setUnreadCount(3);
        when(conversations.findActiveById(conversationId)).thenReturn(Optional.of(conversation));
        lenient().when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void replyAppendsOutboundMessageAndMovesConversationToWaiting() {
        ConversationMessage saved = service.addMessage(
                conversationId,
                "  Annual pricing is attached.  ",
                "Alice Morgan");

        assertThat(saved.getKind()).isEqualTo(MessageKind.AGENT_REPLY);
        assertThat(saved.getDirection()).isEqualTo(MessageDirection.OUTBOUND);
        assertThat(saved.getDeliveryStatus()).isEqualTo(DeliveryStatus.QUEUED);
        assertThat(saved.getChannel()).isEqualTo(Channel.EMAIL);
        assertThat(saved.getBody()).isEqualTo("Annual pricing is attached.");
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.WAITING_CUSTOMER);
        assertThat(conversation.getUnreadCount()).isZero();
        assertThat(conversation.getLastMessagePreview()).isEqualTo("Annual pricing is attached.");
        verify(conversations).save(conversation);
    }

    @Test
    void blankMessageIsRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.addMessage(conversationId, "   ", "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message cannot be empty");

        verify(messages, never()).save(any());
        verify(conversations, never()).save(any());
    }

    @Test
    void closingConversationAddsAVisibleSystemEvent() {
        service.setClosed(conversationId, true, "Alice Morgan");

        ArgumentCaptor<ConversationMessage> event = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(messages).save(event.capture());
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.CLOSED);
        assertThat(event.getValue().getKind()).isEqualTo(MessageKind.SYSTEM_EVENT);
        assertThat(event.getValue().getDirection()).isEqualTo(MessageDirection.INTERNAL);
        assertThat(event.getValue().getBody()).isEqualTo("Alice Morgan closed the conversation");
    }

    @Test
    void assignmentAddsAVisibleLeadActivityEvent() {
        UUID agentId = UUID.randomUUID();

        service.assign(conversationId, agentId, "Alice Morgan");

        ArgumentCaptor<ConversationMessage> event = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(messages).save(event.capture());
        assertThat(conversation.getAssignee().id()).isEqualTo(agentId);
        assertThat(event.getValue().getKind()).isEqualTo(MessageKind.SYSTEM_EVENT);
        assertThat(event.getValue().getBody())
                .isEqualTo("Alice Morgan assigned the conversation to themselves");
    }

    @Test
    void assigningToCurrentAgentIsIdempotent() {
        UUID agentId = UUID.randomUUID();
        conversation.setAssignee(su.onno.types.Ref.of(su.onno.crm.domain.Agent.class, agentId));

        assertThat(service.assign(conversationId, agentId, "Alice Morgan")).isSameAs(conversation);

        verify(conversations, never()).save(any());
        verify(messages, never()).save(any());
    }
}
