package su.onno.crm.channels.gmail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import su.onno.crm.channels.CrmChannelsProperties;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;

/**
 * A GmailBridge over an in-memory database and in-memory conversations, with each mailbox's
 * Gmail client a Mockito stub. Mailboxes are added as rows, the way {@code connect} leaves them.
 */
final class GmailFixture implements AutoCloseable {
    final ObjectMapper json = new ObjectMapper();
    final DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    final JdbcTemplate jdbc = new JdbcTemplate(ds);
    final GmailClient client = mock(GmailClient.class);
    final CrmConnectionAccounts accounts = new CrmConnectionAccounts(jdbc, p -> p.getName().equals("admin"),
            CrmFeatures.defaults().withPersonalAccounts(true));
    final Map<UUID, Conversation> conversationStore = new LinkedHashMap<>();
    final List<ConversationMessage> messageStore = new ArrayList<>();
    final ConversationRepository conversations = mock(ConversationRepository.class);
    final ConversationMessageRepository messages = mock(ConversationMessageRepository.class);
    final CrmContactService contacts = mock(CrmContactService.class);
    final CrmAttachments attachments = mock(CrmAttachments.class);
    final CrmConversationStatuses statuses = mock(CrmConversationStatuses.class);
    final UUID customer = UUID.randomUUID();
    final UUID incomingStatus = UUID.randomUUID();
    final CrmChannelsProperties properties = new CrmChannelsProperties();
    final GmailBridge bridge;

    GmailFixture() {
        accounts.initialize();
        when(conversations.save(any())).thenAnswer(call -> {
            Conversation c = call.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            conversationStore.put(c.getId(), c);
            return c;
        });
        when(conversations.findActiveById(any())).thenAnswer(call -> Optional.ofNullable(conversationStore.get(call.<UUID>getArgument(0))));
        when(messages.save(any())).thenAnswer(call -> {
            ConversationMessage m = call.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            if (!messageStore.contains(m)) messageStore.add(m);
            return m;
        });
        when(messages.findActiveById(any())).thenAnswer(call -> messageStore.stream()
                .filter(m -> m.getId().equals(call.getArgument(0))).findFirst());
        when(messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(any())).thenAnswer(call -> {
            Ref<?> ref = call.getArgument(0);
            return messageStore.stream().filter(m -> m.getConversation() != null && m.getConversation().id().equals(ref.id()))
                    .sorted(Comparator.comparing(ConversationMessage::getSentAt)).toList();
        });
        when(contacts.resolveIncoming(any())).thenReturn(customer);
        when(contacts.canonical(any())).thenAnswer(call -> call.getArgument(0));
        when(statuses.incoming()).thenReturn(incomingStatus);
        when(client.configured()).thenReturn(true);
        when(client.authorized()).thenReturn(true);
        bridge = new GmailBridge(client, jdbc, new DataSourceTransactionManager(ds), mock(InboxRepository.class), conversations, messages,
                contacts, mock(CrmWorkspaceService.class), mock(ContactIdentityRepository.class), statuses, attachments, accounts, properties);
        bridge.start();
        bridge.stop(); // no background worker: tests drive the bridge themselves
        ReflectionTestUtils.setField(bridge, "ready", true);
    }

    /** The deployment's own (legacy) mailbox, which the base client serves. */
    UUID legacyMailbox(String address) {
        UUID inbox = UUID.randomUUID();
        jdbc.update("INSERT INTO onno_crm_gmail_account (account,inbox_id,history_id,active,connection_id) VALUES (?,?,?,TRUE,NULL)", address, inbox, "1");
        return inbox;
    }

    /** A connected account's mailbox; returns its own client stub. */
    GmailClient mailbox(String address, CrmConnectionAccounts.Scope scope, String owner, String label) {
        Principal by = () -> scope == CrmConnectionAccounts.Scope.SHARED ? "admin" : owner;
        var account = accounts.create("gmail", scope, label, by);
        UUID inbox = UUID.randomUUID();
        accounts.bindInbox(account.id(), inbox);
        jdbc.update("INSERT INTO onno_crm_gmail_account (account,inbox_id,history_id,active,connection_id) VALUES (?,?,?,TRUE,?)", address, inbox, "1", account.id());
        var scoped = mock(GmailClient.class);
        when(scoped.authorized()).thenReturn(true);
        when(client.forAccount(account.id())).thenReturn(scoped);
        return scoped;
    }

    UUID inboxOf(String address) {
        return jdbc.queryForObject("SELECT inbox_id FROM onno_crm_gmail_account WHERE account=?", UUID.class, address);
    }

    GmailBridge.Account account(String address) {
        List<?> all = ReflectionTestUtils.invokeMethod(bridge, "accounts");
        return all.stream().map(GmailBridge.Account.class::cast).filter(a -> a.email().equals(address)).findFirst().orElseThrow();
    }

    /** An email conversation in this mailbox's inbox, routed through one Gmail thread. */
    Conversation conversation(String address, String thread, String replyTo) {
        var c = new Conversation();
        c.setId(UUID.randomUUID());
        c.setChannel(Channel.EMAIL);
        c.setCustomer(customer);
        c.setSubject("Booking");
        c.setInbox(Ref.of(Inbox.class, inboxOf(address)));
        conversationStore.put(c.getId(), c);
        jdbc.update("INSERT INTO onno_crm_gmail_thread (account,thread_id,conversation_id,reply_to,subject,reference_id,last_received) VALUES (?,?,?,?,?,?,?)",
                address, thread, c.getId(), replyTo, "Booking", "<original@example.com>", LocalDateTime.now().minusDays(1));
        return c;
    }

    /** Gmail's {@code format=raw} answer for a plain email. */
    ObjectNode raw(String from, String to, String subject, String body, String thread, long internalDate, String... labels) throws Exception {
        var node = json.createObjectNode()
                .put("raw", GmailMail.compose(from, to, subject, "", body, List.of(), UUID.randomUUID()))
                .put("threadId", thread).put("internalDate", String.valueOf(internalDate));
        var ids = node.putArray("labelIds");
        for (String label : labels) ids.add(label);
        return node;
    }

    List<ConversationMessage> messagesOf(UUID conversation) {
        return messageStore.stream().filter(m -> m.getConversation() != null && m.getConversation().id().equals(conversation)).toList();
    }

    boolean seen(String address, String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_seen WHERE account=? AND external_id=?", Integer.class, address, id) > 0;
    }

    @Override public void close() { bridge.stop(); }
}
