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
    private final CrmAttachments attachments = new CrmAttachments(new su.onno.web.media.MediaStorage() {
        public su.onno.web.media.StoredMedia store(java.io.InputStream content, String filename, String contentType, long size) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<su.onno.web.media.LoadedMedia> load(String key) {
            return Optional.of(new su.onno.web.media.LoadedMedia(
                    new org.springframework.core.io.ByteArrayResource("file".getBytes()),
                    key.endsWith(".png") ? "image/png" : "application/pdf", 4,
                    key.substring(key.lastIndexOf('/') + 1)));
        }
    }, new su.onno.web.media.MediaProperties());
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
    void recipientIsCapturedAndRestrictedToContactAddresses() {
        when(transport.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(true,"Email",8000)
            .withRecipients("personal@example.com",java.util.List.of("personal@example.com","work@example.com")));
        var defaultReply=service.addMessage(conversationId,new ConversationService.Reply("Hi","Agent"));
        assertThat(defaultReply.getRecipient()).isEqualTo("personal@example.com");
        var alternate=service.addMessage(conversationId,new ConversationService.Reply("Hi",java.util.List.of(),"Agent",null,null,"work@example.com"));
        assertThat(alternate.getRecipient()).isEqualTo("work@example.com");
        assertThatThrownBy(()->service.addMessage(conversationId,new ConversationService.Reply("Hi",java.util.List.of(),"Agent",null,null,"stranger@example.com")))
            .hasMessage("Choose an email address linked to this contact");
        assertThatThrownBy(()->service.addMessage(conversationId,new ConversationService.Reply("Hi",java.util.List.of(),"Agent",null,null,"work@example.com\r\nBcc: stranger@example.com")))
            .hasMessage("Invalid email recipient");
    }

    @Test
    void ccIsValidatedAndKeptOnTheQueuedReply() {
        when(transport.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(true,"Email",8000)
            .withRecipients("client@example.com",java.util.List.of("client@example.com")));
        var reply=service.addMessage(conversationId,new ConversationService.Reply("Hi",java.util.List.of(),"Agent",null,null,null,null,null,
            java.util.List.of("Marco Rossi <marco@venue.test>"," giulia@venue.test ","GIULIA@venue.test","client@example.com","Smith, Jo <jo@venue.test>")));
        assertThat(reply.getSentTo()).isEqualTo("client@example.com");
        // Duplicates and the recipient leave; a name with a comma is quoted, so the list splits back the same.
        assertThat(reply.getCopyTo()).isEqualTo("Marco Rossi <marco@venue.test>, giulia@venue.test, \"Smith, Jo\" <jo@venue.test>");
        assertThat(EmailAddresses.split(reply.getCopyTo())).hasSize(3);

        var none=service.addMessage(conversationId,new ConversationService.Reply("Hi","Agent"));
        assertThat(none.getCopyTo()).isNull();

        for(String bad:java.util.List.of("marco@venue.test\r\nBcc: spy@example.com","not an address","a@b.test, c@d.test","Marco <marco@venue>"))
            assertThatThrownBy(()->service.addMessage(conversationId,new ConversationService.Reply("Hi",java.util.List.of(),"Agent",null,null,null,null,null,java.util.List.of(bad))))
                .isInstanceOf(IllegalArgumentException.class);
        var many=java.util.stream.IntStream.range(0,21).mapToObj(i->"guest"+i+"@example.com").toList();
        assertThatThrownBy(()->service.addMessage(conversationId,new ConversationService.Reply("Hi",java.util.List.of(),"Agent",null,null,null,null,null,many)))
            .hasMessage("Copy at most 20 people");
    }

    @Test
    void onlyEmailRepliesCarryACc() {
        conversation.setChannel(Channel.TELEGRAM);
        assertThatThrownBy(()->service.addMessage(conversationId,new ConversationService.Reply("Hi",java.util.List.of(),"Agent",null,null,null,null,null,
            java.util.List.of("marco@venue.test")))).hasMessage("Only email replies have a Cc");
    }

    @Test
    void senderIsCapturedAndRestrictedToTheOfferedMailboxes() {
        var offered = new CrmMessageTransport.Connection(true, "Gmail", 8000).withSenders("info@studio.test", java.util.List.of(
                new CrmMessageTransport.Sender("info@studio.test", "Info"), new CrmMessageTransport.Sender("office@studio.test", "Office")));
        when(transport.connection(conversation)).thenReturn(offered);
        var byDefault = service.addMessage(conversationId, new ConversationService.Reply("Hi", "Agent"));
        assertThat(byDefault.getSenderAccount()).isEqualTo("info@studio.test");
        var chosen = service.addMessage(conversationId, new ConversationService.Reply("Hi", java.util.List.of(), "Agent", null, null, null, " OFFICE@studio.test"));
        assertThat(chosen.getSenderAccount()).isEqualTo("office@studio.test");
        assertThatThrownBy(() -> service.addMessage(conversationId, new ConversationService.Reply("Hi", java.util.List.of(), "Agent", null, null, null, "bob@studio.test")))
                .hasMessage("Choose one of the offered sender accounts");
    }

    @Test
    void aSenderOfferedOnlyToThePrincipalIsAcceptedForThem() {
        java.security.Principal alice = () -> "alice";
        when(transport.connection(conversation)).thenReturn(new CrmMessageTransport.Connection(true, "Gmail", 8000)
                .withSenders(null, java.util.List.of(new CrmMessageTransport.Sender("info@studio.test", null))));
        when(transport.connection(conversation, alice)).thenReturn(new CrmMessageTransport.Connection(true, "Gmail", 8000)
                .withSenders(null, java.util.List.of(new CrmMessageTransport.Sender("info@studio.test", null), new CrmMessageTransport.Sender("alice@studio.test", null))));
        var reply = new ConversationService.Reply("Hi", java.util.List.of(), "Alice", null, null, null, "alice@studio.test");
        assertThat(service.addMessage(conversationId, reply, alice).getSenderAccount()).isEqualTo("alice@studio.test");
        assertThatThrownBy(() -> service.addMessage(conversationId, reply)).hasMessage("Choose one of the offered sender accounts");
        assertThat(service.delivery(conversationId, alice).sender()).isEqualTo("info@studio.test");
    }

    @Test
    void aChannelWithoutSendersRejectsAChosenOneAndStoresNone() {
        assertThat(service.addMessage(conversationId, new ConversationService.Reply("Hi", "Agent")).getSenderAccount()).isNull();
        assertThatThrownBy(() -> service.addMessage(conversationId, new ConversationService.Reply("Hi", java.util.List.of(), "Agent", null, null, null, "info@studio.test")))
                .hasMessage("Choose one of the offered sender accounts");
    }

    @Test
    void markingReadTellsTheTransportOnlyWhenSomethingWasUnread() {
        service.markRead(conversationId);
        assertThat(conversation.getUnreadCount()).isZero();
        verify(transport).markedRead(conversation);
        service.markRead(conversationId);
        verify(transport, org.mockito.Mockito.times(1)).markedRead(conversation);
    }

    @Test
    void emailSubjectIsSavedWithQueuedMessage() {
        var saved=service.addMessage(conversationId,new ConversationService.Reply("Hello",java.util.List.of(),"Agent",null,"  Booking proposal  "));
        assertThat(saved.getSubject()).isEqualTo("Booking proposal");
        verify(transport).enqueue(conversation,saved);
    }

    @Test
    void aFormattedEmailReplyKeepsItsSanitizedHtmlBesideTheText() {
        var saved = service.addMessage(conversationId, new ConversationService.Reply("Hello Anna, see you", java.util.List.of(), "Agent", null, null, null, null,
                "<p dir=\"auto\" class=\"x\" style=\"color:red\">Hello <b>Anna</b>,</p><p onclick=\"steal()\">see <a href=\"javascript:alert(1)\">you</a> <a href=\"https://studio.test\">here</a></p><script>alert(1)</script><img src=\"https://t.test/p.gif\">"));
        assertThat(saved.getBody()).isEqualTo("Hello Anna, see you");
        assertThat(saved.getBodyHtml()).isEqualTo(
                "<p dir=\"auto\">Hello <b>Anna</b>,</p><p>see <a rel=\"noopener noreferrer\" target=\"_blank\">you</a> "
                + "<a href=\"https://studio.test\" rel=\"noopener noreferrer\" target=\"_blank\">here</a></p>");
        verify(transport).enqueue(conversation, saved);
    }

    @Test
    void aReplyWithoutFormattingOrWithEmptyFormattingStaysPlain() {
        assertThat(service.addMessage(conversationId, new ConversationService.Reply("Hi", "Agent")).getBodyHtml()).isNull();
        var empty = service.addMessage(conversationId, new ConversationService.Reply("Hi", java.util.List.of(), "Agent", null, null, null, null, "<p><br></p><script>x</script>"));
        assertThat(empty.getBodyHtml()).isNull();
    }

    @Test
    void onlyEmailRepliesCanBeFormatted() {
        conversation.setChannel(Channel.TELEGRAM);
        assertThatThrownBy(() -> service.addMessage(conversationId, new ConversationService.Reply("Hi", java.util.List.of(), "Agent", null, null, null, null, "<b>Hi</b>")))
                .hasMessage("Only email messages can be formatted");
        assertThatThrownBy(() -> service.addMessage(conversationId, new ConversationService.Reply("Hi", java.util.List.of(), "Agent", null, null, null, null, "<b>" + "x".repeat(ReplyHtml.MAX_LENGTH) + "</b>")))
                .hasMessage("Only email messages can be formatted");
        conversation.setChannel(Channel.EMAIL);
        assertThatThrownBy(() -> service.addMessage(conversationId, new ConversationService.Reply("Hi", java.util.List.of(), "Agent", null, null, null, null, "<b>" + "x".repeat(ReplyHtml.MAX_LENGTH) + "</b>")))
                .hasMessage("The formatted message is too long");
        verify(messages, never()).save(any());
    }

    @Test
    void invalidEmailSubjectIsRejectedBeforeWriting() {
        org.mockito.Mockito.lenient().when(statuses.reply()).thenReturn(statusRef(ConversationStatus.WAITING_CUSTOMER));
        for(String subject:java.util.List.of("hello\r\nBcc: someone@example.com","x".repeat(999)))
            assertThatThrownBy(()->service.addMessage(conversationId,new ConversationService.Reply("Hello",java.util.List.of(),"Agent",null,subject))).isInstanceOf(IllegalArgumentException.class);
        verify(messages,never()).save(any());
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
