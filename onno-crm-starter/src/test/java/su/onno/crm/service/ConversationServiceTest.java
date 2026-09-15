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
import su.onno.crm.domain.DeliveryStatus;
import su.onno.crm.domain.MessageDirection;
import su.onno.crm.domain.MessageKind;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {
    enum ConversationStatus { OPEN, WAITING_CUSTOMER, CLOSED }

    @Mock CrmMessageTransport transport;
    /** Every key resolves; the point under test is the message, not the storage backend. */
    private final CrmAttachments attachments = new CrmAttachments(new su.onno.ui.media.MediaStorage() {
        public su.onno.ui.media.StoredMedia store(java.io.InputStream content, String filename, String contentType, long size) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<su.onno.ui.media.LoadedMedia> load(String key) {
            return Optional.of(new su.onno.ui.media.LoadedMedia(
                    new org.springframework.core.io.ByteArrayResource("file".getBytes()),
                    key.endsWith(".png") ? "image/png" : "application/pdf", 4,
                    key.substring(key.lastIndexOf('/') + 1)));
        }
    }, new su.onno.ui.media.MediaProperties());
    @Mock ConversationRepository conversations;
    @Mock ConversationMessageRepository messages;

    private ConversationService service;
    private final CrmConversationStatuses statuses = org.mockito.Mockito.mock(CrmConversationStatuses.class);
    private UUID conversationId;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversations, messages, transport, attachments, statuses,su.onno.crm.TestBindings.agents());
        lenient().when(transport.connection(any())).thenReturn(new CrmMessageTransport.Connection(true, "Test", 4096));
        conversationId = UUID.randomUUID();
        conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setChannel(Channel.EMAIL);
        conversation.setStatus(statusRef(ConversationStatus.OPEN));
        when(statuses.reply()).thenReturn(statusRef(ConversationStatus.WAITING_CUSTOMER));
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

    private static java.util.UUID statusRef(ConversationStatus status) {
        return su.onno.repository.EnumerationPersistence.resolveId(ConversationStatus.class,status);
    }

    private static final java.util.List<String> QUOTE = java.util.List.of("/api/media/2026/09/quote.pdf");

    /** An agent's reply; the identity is irrelevant to every attachment rule under test. */
    private static ConversationService.Reply reply(String body, java.util.List<String> files) {
        return new ConversationService.Reply(body, files, "Agent", null);
    }

    /** The default test channel carries no files, as a channel that has not said otherwise should. */
    private void channelCarriesFiles(int limit) {
        when(transport.connection(any())).thenReturn(
                new CrmMessageTransport.Connection(true, "Test", 4096).withAttachments(limit, ""));
    }

    @Test
    void replyCarriesItsFilesAndTheChannelSeesThemQueuedWithIt() {
        channelCarriesFiles(10);
        ConversationMessage saved = service.addMessage(conversationId, reply("The quote, as promised.", QUOTE));

        assertThat(saved.getAttachments()).isEqualTo("/api/media/2026/09/quote.pdf");
        assertThat(saved.getDeliveryStatus()).isEqualTo(DeliveryStatus.QUEUED);
        assertThat(attachments.of(saved)).singleElement().satisfies(file -> {
            assertThat(file.filename()).isEqualTo("quote.pdf");
            assertThat(file.contentType()).isEqualTo("application/pdf");
            assertThat(file.image()).isFalse();
        });
        verify(transport).enqueue(any(), any());
    }

    /** A file sent with no covering note is a message; the conversation list has to say so. */
    @Test
    void aReplyMayBeFilesAlone() {
        channelCarriesFiles(10);
        ConversationMessage saved = service.addMessage(conversationId, reply("  ", QUOTE));

        assertThat(saved.getBody()).isEmpty();
        assertThat(saved.getDescription()).isEqualTo("quote.pdf");
        assertThat(conversation.getLastMessagePreview()).isEqualTo("quote.pdf");
        assertThat(saved.rules().stream().allMatch(rule -> rule.condition().getAsBoolean())).isTrue();
    }

    @Test
    void emptyReplyWithNoFilesIsStillRejected() {
        assertThatThrownBy(() -> service.addMessage(conversationId, reply("   ", java.util.List.of())))
                .hasMessage("Message cannot be empty");
        verify(transport, never()).enqueue(any(), any());
    }

    /**
     * Only references this application issued: a delivery worker must never be pointed elsewhere.
     * This is refused before the channel is even consulted, so no transport can opt out of it.
     */
    @Test
    void aTypedInLinkIsNotAnAttachment() {
        assertThatThrownBy(() -> service.addMessage(conversationId, reply("See this", java.util.List.of("https://example.invalid/invoice.pdf"))))
                .hasMessage("Attach files through the upload button");
        assertThatThrownBy(() -> service.addMessage(conversationId, reply("See this", java.util.List.of("/api/media/../../etc/passwd"))))
                .hasMessage("Attach files through the upload button");
        verify(transport, never()).enqueue(any(), any());
    }

    @Test
    void aChannelThatCarriesNoFilesSaysSoInsteadOfDroppingThem() {
        when(transport.connection(any())).thenReturn(new CrmMessageTransport.Connection(
                true, "Instagram", 1000, CrmMessageTransport.ReplyCapability.AVAILABLE, "")
                .withAttachments(0, "Instagram fetches files from a public URL."));

        assertThatThrownBy(() -> service.addMessage(conversationId, reply("Here it is", QUOTE)))
                .hasMessage("Instagram fetches files from a public URL.");
        verify(transport, never()).enqueue(any(), any());
    }

    @Test
    void aChannelsOwnFileLimitIsEnforcedBeforeAnythingIsQueued() {
        channelCarriesFiles(1);

        assertThatThrownBy(() -> service.addMessage(conversationId, reply("Both files", java.util.List.of("/api/media/a/one.pdf", "/api/media/a/two.png"))))
                .hasMessage("This channel carries at most 1 file(s) per message");
        verify(transport, never()).enqueue(any(), any());
    }

    /** The same file picked twice is one attachment, not two identical sends. */
    @Test
    void duplicateReferencesCollapse() {
        channelCarriesFiles(10);
        ConversationMessage saved = service.addMessage(conversationId, reply("Attached", java.util.List.of("/api/media/a/one.pdf", "/api/media/a/one.pdf")));

        assertThat(attachments.of(saved)).hasSize(1);
    }
}
