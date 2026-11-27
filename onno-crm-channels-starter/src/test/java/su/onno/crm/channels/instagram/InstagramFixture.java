package su.onno.crm.channels.instagram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import su.onno.crm.channels.CrmChannelsProperties;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.web.UiMessages;

/**
 * An InstagramBridge over an in-memory database and in-memory conversations, talking to a fake
 * Graph API ({@link FakeGraph}) through a Mockito-stubbed {@link InstagramClient}. No network.
 */
final class InstagramFixture {
    static final String ACCOUNT = "17841400000000001";
    static final String SCOPED = "9000000000000001";
    static final Instant T0 = Instant.parse("2026-11-12T09:00:00Z");

    final ObjectMapper json = new ObjectMapper();
    final DriverManagerDataSource ds;
    final JdbcTemplate jdbc;
    final InstagramClient client = mock(InstagramClient.class);
    final FakeGraph graph = new FakeGraph();
    final MutableClock clock = new MutableClock(T0);
    final Map<UUID, Conversation> conversationStore = new LinkedHashMap<>();
    final List<ConversationMessage> messageStore = new ArrayList<>();
    final ConversationRepository conversations = mock(ConversationRepository.class);
    final ConversationMessageRepository messages = mock(ConversationMessageRepository.class);
    final InboxRepository inboxes = mock(InboxRepository.class);
    final CrmContactService contacts = mock(CrmContactService.class);
    final ContactIdentityRepository identities = mock(ContactIdentityRepository.class);
    final CrmConversationStatuses statuses = mock(CrmConversationStatuses.class);
    final CrmChannelsProperties properties = new CrmChannelsProperties();
    final UUID inbox = UUID.randomUUID();
    /** When it matches a message about to be saved, the save fails once (simulating a failed insert). */
    Predicate<ConversationMessage> failOnce = m -> false;
    InstagramBridge bridge;

    InstagramFixture() { this("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"); }

    InstagramFixture(String url) {
        ds = new DriverManagerDataSource(url, "sa", "");
        jdbc = new JdbcTemplate(ds);
        when(conversations.save(any())).thenAnswer(call -> {
            Conversation c = call.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            conversationStore.put(c.getId(), c);
            return c;
        });
        when(conversations.findActiveById(any())).thenAnswer(call -> Optional.ofNullable(conversationStore.get(call.<UUID>getArgument(0))));
        when(messages.save(any())).thenAnswer(call -> {
            ConversationMessage m = call.getArgument(0);
            if (failOnce.test(m)) { failOnce = x -> false; throw new IllegalStateException("insert failed: " + m.getBody()); }
            if (m.getId() == null) m.setId(UUID.randomUUID());
            if (!messageStore.contains(m)) messageStore.add(m);
            return m;
        });
        when(messages.findActiveById(any())).thenAnswer(call -> messageStore.stream().filter(m -> m.getId().equals(call.getArgument(0))).findFirst());
        when(inboxes.save(any())).thenAnswer(call -> {
            Inbox i = call.getArgument(0);
            if (i.getId() == null) i.setId(inbox);
            return i;
        });
        when(contacts.resolveIncoming(any())).thenAnswer(call -> UUID.nameUUIDFromBytes(call.<CrmCustomerBinding.IncomingContact>getArgument(0).toString().getBytes()));
        when(contacts.canonical(any())).thenAnswer(call -> call.getArgument(0));
        when(statuses.incoming()).thenReturn(UUID.randomUUID());
        when(client.configured()).thenReturn(true);
        when(client.profile()).thenReturn(new InstagramClient.Profile(SCOPED, ACCOUNT, "studio"));
        when(client.conversations(anyString(), any())).thenAnswer(call -> graph.conversations(call.getArgument(1)));
        when(client.messages(anyString())).thenAnswer(call -> graph.messages(call.getArgument(0)));
        properties.getInstagram().setActivationCutoff(T0);
    }

    /** Builds a fresh bridge over the same database, as an application restart would. */
    InstagramBridge restart() {
        bridge = new InstagramBridge(client, jdbc, new DataSourceTransactionManager(ds), inboxes, conversations, messages,
                identities, contacts, mock(CrmWorkspaceService.class), statuses,
                new UiMessages(Map.of()), properties);
        bridge.clock(clock);
        bridge.initialize();
        ReflectionTestUtils.setField(bridge, "ready", true); // no background worker: tests drive tick()
        return bridge;
    }

    /** The account row the way an earlier connection left it. */
    void account(boolean active) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_account (account_id VARCHAR(100) PRIMARY KEY,scoped_id VARCHAR(100),username VARCHAR(200),inbox_id UUID NOT NULL,active BOOLEAN NOT NULL,cursor VARCHAR(4000))");
        jdbc.update("INSERT INTO onno_crm_ig_account VALUES (?,?,?,?,?,?)", ACCOUNT, SCOPED, "studio", inbox, active, "legacy-cursor");
    }

    /** Advances the clock one polling interval and polls. */
    void poll() { clock.advance(Duration.ofSeconds(15)); bridge.tick(); }

    String failure() { return (String) ReflectionTestUtils.getField(bridge, "failure"); }

    List<String> bodies() { return messageStore.stream().map(ConversationMessage::getBody).toList(); }

    String cutoff() { return jdbc.queryForObject("SELECT activation_cutoff FROM onno_crm_ig_sync WHERE account_id=?", String.class, ACCOUNT); }

    String checkpoint() { return jdbc.queryForObject("SELECT checkpoint FROM onno_crm_ig_sync WHERE account_id=?", String.class, ACCOUNT); }

    String passHead() { return jdbc.queryForObject("SELECT pass_head FROM onno_crm_ig_sync WHERE account_id=?", String.class, ACCOUNT); }

    /** A minimal in-memory Graph API: conversations newest-updated first with offset cursors, messages newest first. */
    final class FakeGraph {
        record Msg(String id, Instant created, String from, String text) {}
        final class Thread {
            final String id; final String peer; Instant updated; final List<Msg> messages = new ArrayList<>();
            Thread(String id, String peer) { this.id = id; this.peer = peer; }
        }
        final Map<String, Thread> threads = new LinkedHashMap<>();
        int conversationCalls;
        int messageCalls;
        int seq;
        RuntimeException next;

        /** A message from the customer in the thread with {@code peer} (created when absent). */
        String inbound(String peer, Instant at, String text) { return add(peer, at, peer, text); }

        /** A message the studio sent from Instagram itself. */
        String outbound(String peer, Instant at, String text) { return add(peer, at, ACCOUNT, text); }

        private String add(String peer, Instant at, String from, String text) {
            var thread = threads.computeIfAbsent("t_" + peer, id -> new Thread(id, peer));
            String id = "m_" + (++seq);
            thread.messages.add(new Msg(id, at, from, text));
            thread.updated = thread.updated == null || at.isAfter(thread.updated) ? at : thread.updated;
            return id;
        }

        InstagramClient.Page conversations(String after) {
            conversationCalls++;
            if (next != null) { var e = next; next = null; throw e; }
            var sorted = threads.values().stream().sorted(Comparator.comparing((Thread t) -> t.updated).reversed().thenComparing(t -> t.id)).toList();
            int offset = after == null ? 0 : Integer.parseInt(after.substring(1));
            ArrayNode data = json.createArrayNode();
            sorted.stream().skip(offset).limit(InstagramClient.CONVERSATION_PAGE_SIZE)
                    .forEach(t -> data.addObject().put("id", t.id).put("updated_time", graphTime(t.updated)));
            int end = offset + InstagramClient.CONVERSATION_PAGE_SIZE;
            return new InstagramClient.Page(data, end < sorted.size() ? "o" + end : "");
        }

        InstagramClient.Page messages(String conversation) {
            messageCalls++;
            var thread = threads.get(conversation);
            var sorted = thread.messages.stream().sorted(Comparator.comparing(Msg::created).reversed()).toList();
            ArrayNode data = json.createArrayNode();
            sorted.stream().limit(InstagramClient.MESSAGE_PAGE_SIZE).forEach(m -> {
                var node = data.addObject().put("id", m.id).put("created_time", graphTime(m.created)).put("message", m.text);
                node.putObject("from").put("id", m.from).put("username", m.from.equals(ACCOUNT) ? "studio" : "user_" + m.from);
                String to = m.from.equals(ACCOUNT) ? thread.peer : ACCOUNT;
                node.putObject("to").putArray("data").addObject().put("id", to).put("username", "user_" + to);
            });
            return new InstagramClient.Page(data, sorted.size() > InstagramClient.MESSAGE_PAGE_SIZE ? "more" : "");
        }
    }

    /** Graph's own timestamp format, e.g. {@code 2026-11-12T09:00:00+0000}. */
    static String graphTime(Instant at) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneOffset.UTC).format(at);
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        void set(Instant at) { now = at; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
