package su.onno.crm.channels.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.domain.Inbox;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.repository.InboxRepository;
import su.onno.crm.service.CrmAttachments;
import su.onno.crm.service.CrmContactService;
import su.onno.crm.service.CrmConversationStatuses;
import su.onno.crm.service.CrmCustomerBinding;
import su.onno.crm.service.CrmWorkspaceService;

class TelegramInboxBridgeTest {
    private static final long POISON_CHAT = 666;

    private final ObjectMapper json = new ObjectMapper();
    private final TelegramClient client = mock(TelegramClient.class);
    private final InboxRepository inboxes = mock(InboxRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ConversationMessageRepository messages = mock(ConversationMessageRepository.class);
    private final CrmContactService contacts = mock(CrmContactService.class);
    private final CrmAttachments attachments = mock(CrmAttachments.class);
    private final Map<UUID, Inbox> storedInboxes = new HashMap<>();
    private final Map<UUID, Conversation> storedChats = new HashMap<>();
    private final Map<UUID, ConversationMessage> storedMessages = new HashMap<>();
    private JdbcTemplate jdbc;

    private TelegramInboxBridge bridge(boolean regularChatsEnabled) {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        when(inboxes.save(any())).thenAnswer(a -> {
            Inbox inbox = a.getArgument(0);
            if (inbox.getId() == null) inbox.setId(UUID.randomUUID());
            storedInboxes.put(inbox.getId(), inbox);
            return inbox;
        });
        when(inboxes.findActiveById(any())).thenAnswer(a -> Optional.ofNullable(storedInboxes.get(a.getArgument(0))));
        when(conversations.save(any())).thenAnswer(a -> {
            Conversation conversation = a.getArgument(0);
            if (conversation.getId() == null) conversation.setId(UUID.randomUUID());
            storedChats.put(conversation.getId(), conversation);
            return conversation;
        });
        when(conversations.findActiveById(any())).thenAnswer(a -> Optional.ofNullable(storedChats.get(a.getArgument(0))));
        when(messages.save(any())).thenAnswer(a -> {
            ConversationMessage message = a.getArgument(0);
            if (message.getId() == null) message.setId(UUID.randomUUID());
            storedMessages.put(message.getId(), message);
            return message;
        });
        when(contacts.resolveIncoming(any())).thenAnswer(a -> {
            CrmCustomerBinding.IncomingContact contact = a.getArgument(0);
            if (contact.externalId().equals(Long.toString(POISON_CHAT))) throw new IllegalStateException("broken contact");
            return UUID.randomUUID();
        });
        when(contacts.canonical(any())).thenAnswer(a -> a.getArgument(0));
        when(attachments.limit()).thenReturn(10);
        var bridge = new TelegramInboxBridge(client, jdbc, new DataSourceTransactionManager(ds), inboxes, conversations,
                messages, contacts, mock(CrmWorkspaceService.class), mock(CrmConversationStatuses.class), attachments,
                null, regularChatsEnabled);
        bridge.initialize(new TelegramClient.Bot(7, "crm_bot"));
        return bridge;
    }

    private TelegramClient.Update text(long id, long chat, String text) {
        var payload = json.createObjectNode();
        payload.putObject("message").put("text", text);
        return new TelegramClient.Update(id, chat, chat, id, 1_700_000_000L, "Customer " + chat, "", text, true, payload);
    }

    private TelegramClient.Update document(long id, long chat) throws Exception {
        JsonNode payload = json.readTree("{\"message\":{\"caption\":\"Contract\",\"document\":{\"file_id\":\"doc-" + id + "\",\"file_name\":\"contract.pdf\"}}}");
        return new TelegramClient.Update(id, chat, chat, id, 1_700_000_000L, "Customer " + chat, "", "[Attachment] Contract", true, payload);
    }

    private long offset() {
        return jdbc.queryForObject("SELECT next_update FROM onno_crm_telegram_connection WHERE bot_id=7", Long.class);
    }

    private List<String> bodies() {
        return storedMessages.values().stream().map(ConversationMessage::getBody).toList();
    }

    @Test
    void failingFileImportStillRecordsTheMessageAndAdvances() throws Exception {
        var bridge = bridge(true);
        when(client.download("doc-1")).thenThrow(new IllegalArgumentException("Telegram file exceeds 20 MB"));
        when(client.download("doc-2")).thenReturn(new byte[]{1});
        when(attachments.ingest(anyString(), anyString(), any())).thenThrow(new IllegalStateException("storage offline"));

        assertThat(bridge.process(List.of(document(1, 100), document(2, 101), text(3, 102, "Hello")))).isTrue();

        assertThat(offset()).isEqualTo(4);
        assertThat(bodies()).containsExactlyInAnyOrder(
                "[Attachment] Contract\n" + TelegramAttachments.TOO_LARGE,
                "[Attachment] Contract\n" + TelegramAttachments.NOT_IMPORTED,
                "Hello");
    }

    @Test
    void poisonUpdateIsSkippedAfterRetriesAndDoesNotBlockTheNextOne() {
        var bridge = bridge(true);
        var batch = List.of(text(1, POISON_CHAT, "Breaks"), text(2, 200, "Ordinary message"));

        for (int attempt = 1; attempt < TelegramInboxBridge.MAX_ATTEMPTS; attempt++) {
            assertThat(bridge.process(batch)).isFalse();
            assertThat(offset()).isZero(); // checkpoint retained for a possibly transient failure
        }
        assertThat(bridge.process(batch)).isTrue();

        assertThat(offset()).isEqualTo(3);
        assertThat(bodies()).containsExactly("Ordinary message");
    }

    @Test
    void businessOnlyBotIgnoresOrdinaryChatsAndCreatesNoInbox() {
        var bridge = bridge(false);

        assertThat(bridge.process(List.of(text(1, 300, "Hi bot")))).isTrue();

        assertThat(offset()).isEqualTo(2);
        assertThat(storedInboxes).isEmpty();
        assertThat(storedMessages).isEmpty();
        verify(contacts, never()).resolveIncoming(any());
        assertThat(bridge.channelView(false).state()).isEqualTo("UNAVAILABLE");
    }
}
