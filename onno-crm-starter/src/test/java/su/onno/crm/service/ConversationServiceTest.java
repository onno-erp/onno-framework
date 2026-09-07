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

    @Mock CrmMessageTransport transport;
    @Mock ConversationRepository conversations;
    @Mock ConversationMessageRepository messages;

    private ConversationService service;
    private final CrmConversationStatuses statuses = org.mockito.Mockito.mock(CrmConversationStatuses.class);
    private UUID conversationId;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversations, messages, transport, statuses);
        lenient().when(transport.connection(any())).thenReturn(new CrmMessageTransport.Connection(true, "Test", 4096));
        conversationId = UUID.randomUUID();
        conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setChannel(Channel.EMAIL);
        conversation.setStatus(statusRef(ConversationStatus.OPEN));
        when(statuses.reply()).thenReturn(statusRef(ConversationStatus.WAITING_CUSTOMER));
        when(statuses.close()).thenReturn(statusRef(ConversationStatus.CLOSED));
        when(statuses.reopen()).thenReturn(statusRef(ConversationStatus.OPEN));
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
        assertThat(conversation.getStatus()).isEqualTo(statusRef(ConversationStatus.WAITING_CUSTOMER));
        assertThat(conversation.getUnreadCount()).isZero();
        assertThat(conversation.getLastMessagePreview()).isEqualTo("Annual pricing is attached.");
        verify(transport).enqueue(conversation, saved);
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
    void disconnectedChannelRejectsReplyWithoutWriting() {
        when(transport.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(false, "Not connected", 4096));
        assertThatThrownBy(() -> service.addMessage(conversationId, "hello", "Alice"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Not connected");
        verify(messages, never()).save(any());
        verify(transport, never()).enqueue(any(), any());
    }

    @Test
    void oversizedMessageIsRejectedBeforeEnqueue() {
        assertThatThrownBy(() -> service.addMessage(conversationId, "x".repeat(4097), "Alice"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("4096");
        verify(messages, never()).save(any());
    }

    @Test
    void retryCannotSendMessageFromAnotherConversation() {
        ConversationMessage message = new ConversationMessage();
        message.setConversation(su.onno.types.Ref.of(Conversation.class, UUID.randomUUID()));
        message.setDeliveryStatus(DeliveryStatus.FAILED);
        UUID messageId = UUID.randomUUID();
        when(messages.findActiveById(messageId)).thenReturn(Optional.of(message));
        assertThatThrownBy(() -> service.retryMessage(conversationId, messageId)).isInstanceOf(IllegalArgumentException.class);
        verify(transport, never()).enqueue(any(), any());
    }

    @Test
    void closingConversationAddsAVisibleSystemEvent() {
        service.setClosed(conversationId, true, "Alice Morgan");

        ArgumentCaptor<ConversationMessage> event = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(messages).save(event.capture());
        assertThat(conversation.getStatus()).isEqualTo(statusRef(ConversationStatus.CLOSED));
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
    private static su.onno.types.Ref<su.onno.crm.domain.ChatStatus> statusRef(ConversationStatus status) {
        return su.onno.types.Ref.of(su.onno.crm.domain.ChatStatus.class,su.onno.repository.EnumerationPersistence.resolveId(ConversationStatus.class,status));
    }
}
