package su.onno.crm.channels.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.channels.ExternalReplyReadState;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;

/**
 * Business-account routing shares the bot's polling owner, but never its ordinary-chat inbox.
 * A one-use /start challenge proves the Telegram user before their business connection is bound.
 *
 * <p>Contacts created from business chats use the account's inbox id as their connection key, so
 * a personal account's correspondents stay hidden from other users with the inbox.
 */
public class TelegramBusinessAccounts {
    public static final String PROVIDER = "telegram-business";
    private static final Logger log = LoggerFactory.getLogger(TelegramBusinessAccounts.class);
    private static final String START = "/start crm_";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CrmConnectionAccounts accounts;
    private final CrmWorkspaceService workspace;
    private final CrmContactService contacts;
    private final CrmConversationStatuses statuses;
    private final InboxRepository inboxes;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final CrmAttachments attachments;
    private Supplier<TelegramClient> client;
    private TelegramClient.Bot bot;
    private volatile long nextSendAt;

    public TelegramBusinessAccounts(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                    CrmConnectionAccounts accounts, CrmWorkspaceService workspace, CrmContactService contacts,
                                    CrmConversationStatuses statuses, InboxRepository inboxes, ConversationRepository conversations,
                                    ConversationMessageRepository messages, CrmAttachments attachments) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.accounts = accounts;
        this.workspace = workspace;
        this.contacts = contacts;
        this.statuses = statuses;
        this.inboxes = inboxes;
        this.conversations = conversations;
        this.messages = messages;
        this.attachments = attachments;
    }

    void initialize(TelegramClient.Bot bot, Supplier<TelegramClient> client) {
        this.bot = bot;
        this.client = client;
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_tg_business_links (account_id UUID PRIMARY KEY,bot_id BIGINT NOT NULL,user_id BIGINT,connection_id VARCHAR(200),authorized BOOLEAN NOT NULL,UNIQUE(bot_id,user_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_tg_business_auth (account_id UUID PRIMARY KEY,token_hash VARCHAR(64) NOT NULL UNIQUE,principal VARCHAR(320) NOT NULL,expires_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_tg_business_connections (bot_id BIGINT NOT NULL,id VARCHAR(200) NOT NULL,user_id BIGINT NOT NULL,user_name VARCHAR(200) NOT NULL,enabled BOOLEAN NOT NULL,can_reply BOOLEAN NOT NULL,updated_at TIMESTAMP NOT NULL,PRIMARY KEY(bot_id,id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_tg_business_chats (account_id UUID NOT NULL,chat_id BIGINT NOT NULL,conversation_id UUID NOT NULL UNIQUE,last_inbound TIMESTAMP NOT NULL,PRIMARY KEY(account_id,chat_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_tg_business_messages (bot_id BIGINT NOT NULL,connection_id VARCHAR(200) NOT NULL,chat_id BIGINT NOT NULL,external_id BIGINT NOT NULL,message_id UUID NOT NULL,PRIMARY KEY(bot_id,connection_id,chat_id,external_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_tg_business_outbox (message_id UUID PRIMARY KEY,account_id UUID NOT NULL,connection_id VARCHAR(200) NOT NULL,chat_id BIGINT NOT NULL,state VARCHAR(20) NOT NULL,error VARCHAR(500),updated_at TIMESTAMP NOT NULL)");
        tx.executeWithoutResult(s -> jdbc.query("SELECT o.message_id FROM onno_crm_tg_business_outbox o JOIN onno_crm_tg_business_links l ON l.account_id=o.account_id WHERE l.bot_id=? AND o.state='SENDING'",
                        (rs, n) -> rs.getObject(1, UUID.class), bot.id())
                .forEach(id -> finish(id, DeliveryStatus.FAILED, null, "Delivery interrupted; check Telegram before retrying")));
    }

    public boolean available() { return bot != null && bot.business(); }

    public String authorize(UUID id, Principal principal) {
        var account = accounts.require(id, principal);
        if (!PROVIDER.equals(account.provider()) || !available()) throw new IllegalArgumentException("Business bot is unavailable");
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tx.executeWithoutResult(s -> {
            workspace.lock();
            jdbc.update("DELETE FROM onno_crm_tg_business_auth WHERE account_id=? OR expires_at<CURRENT_TIMESTAMP", id);
            jdbc.update("INSERT INTO onno_crm_tg_business_auth VALUES (?,?,?,?)", id, hash(token), principal.getName(),
                    Timestamp.from(Instant.now().plusSeconds(600)));
        });
        return "https://t.me/" + bot.username() + "?start=crm_" + token;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether this update is a setup command or a business event, which {@link #receive} consumes. */
    boolean consumes(TelegramClient.Update update) {
        if (isSetupCommand(update)) return true;
        JsonNode event = update.payload();
        return event != null && (event.has("business_connection") || event.has("business_message")
                || event.has("edited_business_message") || event.has("deleted_business_messages"));
    }

    private static boolean isSetupCommand(TelegramClient.Update update) {
        return update.privateChat() && update.text() != null && update.text().startsWith(START);
    }

    /**
     * Network work for a business message, done before the polling transaction and workspace lock:
     * connection metadata for a connection not yet known, and the message's file. Never throws.
     */
    TelegramPreparedUpdate prepare(TelegramClient.Update update) {
        JsonNode event = update.payload();
        if (event == null || isSetupCommand(update)) return TelegramPreparedUpdate.NONE;
        boolean edited = !event.has("business_message");
        JsonNode data = edited ? event.path("edited_business_message") : event.path("business_message");
        if (data.isMissingNode()) return TelegramPreparedUpdate.NONE;
        String connection = data.path("business_connection_id").asText();
        Optional<Link> link = linked(connection);
        JsonNode metadata = null;
        if (link.isEmpty() && !connection.isBlank()) {
            try {
                metadata = client.get().businessConnection(connection);
                link = linkFrom(metadata);
            } catch (RuntimeException error) {
                log.warn("Telegram business connection lookup failed ({})", error.getClass().getSimpleName());
            }
        }
        TelegramAttachments.Imported files = null;
        if (!edited && link.isPresent() && importable(data, link.get()) && TelegramAttachments.hasFile(data)) {
            files = TelegramAttachments.read(data, client.get(), attachments);
        }
        return new TelegramPreparedUpdate(metadata, files);
    }

    /** Called inside the polling transaction and workspace lock; true consumes a setup/business update. */
    boolean receive(TelegramClient.Update update, TelegramPreparedUpdate work) {
        if (isSetupCommand(update)) {
            verifyUser(update);
            return true;
        }
        JsonNode event = update.payload();
        if (event == null) return false;
        if (event.has("business_connection")) {
            connectionEvent(event.path("business_connection"));
            return true;
        }
        if (event.has("business_message")) {
            receiveMessage(event.path("business_message"), false, work);
            return true;
        }
        if (event.has("edited_business_message")) {
            receiveMessage(event.path("edited_business_message"), true, work);
            return true;
        }
        if (event.has("deleted_business_messages")) {
            receiveDeletion(event.path("deleted_business_messages"));
            return true;
        }
        return false;
    }

    /** Receive without prepared network work (tests and callers that already fetched nothing). */
    boolean receive(TelegramClient.Update update) {
        return receive(update, TelegramPreparedUpdate.NONE);
    }

    private void receiveDeletion(JsonNode deleted) {
        String connection = deleted.path("business_connection_id").asText();
        long chat = deleted.path("chat").path("id").asLong();
        if (linked(connection).isEmpty()) return;
        for (var id : deleted.path("message_ids")) {
            externalMessage(connection, chat, id.asLong()).flatMap(messages::findActiveById).ifPresent(m -> {
                m.setBody("[Message deleted in Telegram]");
                m.setDescription("Message deleted in Telegram");
                messages.save(m);
                conversations.findActiveById(m.getConversation().id()).ifPresent(c -> {
                    if (Objects.equals(c.getLastMessageAt(), m.getSentAt())) {
                        c.setLastMessagePreview(m.getBody());
                        conversations.save(c);
                    }
                });
            });
        }
    }

    private void verifyUser(TelegramClient.Update update) {
        if (update.chatId() != update.senderId()) return;
        String token = update.text().substring(START.length());
        var candidates = jdbc.query("SELECT account_id,principal FROM onno_crm_tg_business_auth WHERE token_hash=? AND expires_at>CURRENT_TIMESTAMP",
                (rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)), hash(token));
        if (candidates.isEmpty()) return;
        var attempt = candidates.getFirst();
        jdbc.update("DELETE FROM onno_crm_tg_business_auth WHERE account_id=?", attempt.getKey());
        CrmConnectionAccounts.Account account;
        try {
            account = accounts.find(attempt.getKey());
        } catch (ResponseStatusException removed) {
            return;
        }
        if (!PROVIDER.equals(account.provider()) || (account.personal() && !account.owner().equals(attempt.getValue()))) return;
        var existing = jdbc.query("SELECT account_id FROM onno_crm_tg_business_links WHERE bot_id=? AND user_id=?",
                (rs, n) -> rs.getObject(1, UUID.class), bot.id(), update.senderId());
        if (!existing.isEmpty() && !existing.getFirst().equals(account.id())) return;
        var owner = jdbc.query("SELECT user_id FROM onno_crm_tg_business_links WHERE account_id=?", (rs, n) -> rs.getLong(1), account.id());
        // Reconnecting must prove the original Telegram user; never rebind history to another user.
        if (!owner.isEmpty() && owner.getFirst() != update.senderId()) return;
        if (owner.isEmpty()) {
            jdbc.update("INSERT INTO onno_crm_tg_business_links VALUES (?,?,?,NULL,true)", account.id(), bot.id(), update.senderId());
        }
        jdbc.update("UPDATE onno_crm_tg_business_links SET authorized=true WHERE account_id=?", account.id());
        // Bind an inbox immediately so draft removal cannot orphan a pending verified ownership link.
        ensureInbox(account, update.name());
        var inbox = inboxes.findActiveById(accounts.find(account.id()).inboxId()).orElseThrow();
        inbox.setActive(true);
        inboxes.save(inbox);
        var connected = jdbc.query("SELECT id FROM onno_crm_tg_business_connections WHERE bot_id=? AND user_id=? AND enabled=true ORDER BY updated_at DESC",
                (rs, n) -> rs.getString(1), bot.id(), update.senderId());
        if (!connected.isEmpty()) {
            jdbc.update("UPDATE onno_crm_tg_business_links SET connection_id=? WHERE account_id=?", connected.getFirst(), account.id());
        }
        accounts.changed();
    }

    private void ensureInbox(CrmConnectionAccounts.Account account, String name) {
        if (account.inboxId() != null) return;
        Inbox inbox = new Inbox();
        inbox.setChannel(Channel.TELEGRAM);
        inbox.setDescription(account.label());
        inbox.setAddress(cut(name.isBlank() ? "Telegram business" : name, 200));
        inbox.setAccentColor("#229ED9");
        inboxes.save(inbox);
        accounts.bindInbox(account.id(), inbox.getId());
    }

    private void connectionEvent(JsonNode value) {
        String id = value.path("id").asText();
        long user = value.path("user").path("id").asLong();
        if (id.isBlank() || id.length() > 200 || user <= 0) return;
        String name = (value.path("user").path("first_name").asText() + " " + value.path("user").path("last_name").asText()).trim();
        boolean enabled = value.path("is_enabled").asBoolean();
        boolean canReply = canReply(value);
        if (jdbc.update("UPDATE onno_crm_tg_business_connections SET user_id=?,user_name=?,enabled=?,can_reply=?,updated_at=CURRENT_TIMESTAMP WHERE bot_id=? AND id=?",
                user, cut(name, 200), enabled, canReply, bot.id(), id) == 0) {
            jdbc.update("INSERT INTO onno_crm_tg_business_connections VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    bot.id(), id, user, cut(name, 200), enabled, canReply);
        }
        if (enabled) {
            jdbc.update("UPDATE onno_crm_tg_business_links SET connection_id=? WHERE bot_id=? AND user_id=? AND authorized=true",
                    id, bot.id(), user);
        }
        accounts.changed();
    }

    private static boolean canReply(JsonNode connection) {
        return connection.path("rights").path("can_reply").asBoolean(connection.path("can_reply").asBoolean());
    }

    private record Link(UUID accountId, long user, String connection, boolean enabled, boolean canReply) {}

    private Optional<Link> linked(String connection) {
        return jdbc.query("SELECT l.account_id,l.user_id,l.connection_id,c.enabled,c.can_reply FROM onno_crm_tg_business_links l JOIN onno_crm_tg_business_connections c ON c.bot_id=l.bot_id AND c.id=l.connection_id AND c.user_id=l.user_id WHERE l.bot_id=? AND l.connection_id=? AND l.authorized=true",
                (rs, n) -> new Link(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5)),
                bot.id(), connection).stream().findFirst();
    }

    /** The link that freshly fetched connection metadata would produce, without writing it yet. */
    private Optional<Link> linkFrom(JsonNode metadata) {
        String connection = metadata.path("id").asText();
        long user = metadata.path("user").path("id").asLong();
        if (connection.isBlank() || user <= 0 || !metadata.path("is_enabled").asBoolean()) return Optional.empty();
        return jdbc.query("SELECT account_id FROM onno_crm_tg_business_links WHERE bot_id=? AND user_id=? AND authorized=true",
                        (rs, n) -> rs.getObject(1, UUID.class), bot.id(), user).stream().findFirst()
                .map(account -> new Link(account, user, connection, true, canReply(metadata)));
    }

    private Optional<Link> accountLink(UUID id) {
        var ids = jdbc.query("SELECT connection_id FROM onno_crm_tg_business_links WHERE bot_id=? AND account_id=?",
                (rs, n) -> rs.getString(1), bot.id(), id);
        return ids.isEmpty() || ids.getFirst() == null ? Optional.empty() : linked(ids.getFirst());
    }

    private Optional<UUID> externalMessage(String connection, long chat, long external) {
        return jdbc.query("SELECT message_id FROM onno_crm_tg_business_messages WHERE bot_id=? AND connection_id=? AND chat_id=? AND external_id=?",
                (rs, n) -> rs.getObject(1, UUID.class), bot.id(), connection, chat, external).stream().findFirst();
    }

    /** Whether a business message on this link belongs in its account's inbox. */
    private boolean importable(JsonNode data, Link link) {
        var account = accounts.find(link.accountId());
        if (!link.enabled() || account.inboxId() == null
                || !inboxes.findActiveById(account.inboxId()).map(Inbox::isActive).orElse(false)) return false;
        long chat = data.path("chat").path("id").asLong();
        long sender = data.path("from").path("id").asLong();
        boolean outbound = sender == link.user();
        // Connected-bot echoes are already represented by the durable CRM outbox.
        return "private".equals(data.path("chat").path("type").asText()) && (outbound || sender == chat)
                && !data.path("from").path("is_bot").asBoolean() && !data.hasNonNull("sender_business_bot");
    }

    private void receiveMessage(JsonNode data, boolean edited, TelegramPreparedUpdate work) {
        String connection = data.path("business_connection_id").asText();
        var found = linked(connection);
        // A business connection may predate the worker; its metadata was fetched before the lock.
        if (found.isEmpty() && work.businessConnection() != null) {
            connectionEvent(work.businessConnection());
            found = linked(connection);
        }
        if (found.isEmpty()) return;
        Link link = found.get();
        if (!importable(data, link)) return;
        var account = accounts.find(link.accountId());
        UUID inboxId = Objects.requireNonNull(account.inboxId(), "Telegram business account has no inbox");
        long chat = data.path("chat").path("id").asLong();
        boolean outbound = data.path("from").path("id").asLong() == link.user();
        long external = data.path("message_id").asLong();
        String body = data.hasNonNull("text") ? data.path("text").asText() : "[Attachment] " + data.path("caption").asText();
        var existing = externalMessage(connection, chat, external);
        if (existing.isPresent()) {
            if (edited) applyEdit(existing.get(), body);
            return;
        }
        if (edited) return; // An edit of unimported history is not a new incoming enquiry.
        var chatIds = jdbc.query("SELECT conversation_id FROM onno_crm_tg_business_chats WHERE account_id=? AND chat_id=?",
                (rs, n) -> rs.getObject(1, UUID.class), account.id(), chat);
        if (outbound && chatIds.isEmpty()) return;
        String name = (data.path("from").path("first_name").asText() + " " + data.path("from").path("last_name").asText()).trim();
        if (name.isBlank()) name = "Telegram " + chat;
        var time = LocalDateTime.ofInstant(Instant.ofEpochSecond(data.path("date").asLong()), ZoneId.systemDefault());
        Conversation conversation;
        if (chatIds.isEmpty()) {
            // The inbox id is the connection key: personal-inbox privacy filters identities by it.
            UUID customer = contacts.resolveIncoming(new CrmCustomerBinding.IncomingContact(Channel.TELEGRAM,
                    inboxId.toString(), Long.toString(chat), cut(name, 200), null, null, null, "Telegram business"));
            conversation = new Conversation();
            conversation.setCustomer(customer);
            conversation.setInbox(Ref.of(Inbox.class, inboxId));
            conversation.setChannel(Channel.TELEGRAM);
            conversation.setSubject(cut("Telegram · " + name, 240));
            conversation.setDescription(conversation.getSubject());
            conversations.save(conversation);
            jdbc.update("INSERT INTO onno_crm_tg_business_chats VALUES (?,?,?,?)", account.id(), chat, conversation.getId(), Timestamp.valueOf(time));
        } else {
            conversation = conversations.findActiveById(chatIds.getFirst()).orElse(null);
        }
        if (conversation == null) return;
        var imported = work.filesFor(data);
        ConversationMessage message = new ConversationMessage();
        message.setConversation(Ref.of(Conversation.class, conversation.getId()));
        message.setChannel(Channel.TELEGRAM);
        message.setKind(outbound ? MessageKind.AGENT_REPLY : MessageKind.CUSTOMER_MESSAGE);
        message.setDirection(outbound ? MessageDirection.OUTBOUND : MessageDirection.INBOUND);
        message.setAuthorName(cut(name, 200));
        message.setBody(cut(imported.failure().isBlank() ? body : body + "\n" + imported.failure(), 8000));
        message.setDescription(cut(body, 100));
        message.setSentAt(time);
        message.setAttachments(imported.urls());
        message.setDeliveryStatus(outbound ? DeliveryStatus.SENT : DeliveryStatus.RECEIVED);
        message.setExternalMessageId(connection + ":" + chat + ":" + external);
        messages.save(message);
        jdbc.update("INSERT INTO onno_crm_tg_business_messages VALUES (?,?,?,?,?)", bot.id(), connection, chat, external, message.getId());
        if (!outbound) {
            jdbc.update("UPDATE onno_crm_tg_business_chats SET last_inbound=CASE WHEN last_inbound<? THEN ? ELSE last_inbound END WHERE account_id=? AND chat_id=?",
                    Timestamp.valueOf(time), Timestamp.valueOf(time), account.id(), chat);
        }
        if (conversation.getLastMessageAt() == null || !time.isBefore(conversation.getLastMessageAt())) {
            conversation.setLastMessageAt(time);
            conversation.setLastMessagePreview(cut(body, 180));
        }
        var history = messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class, conversation.getId()));
        if (outbound) {
            ExternalReplyReadState.answered(conversation, message, history, ExternalReplyReadState.CHAT_MESSAGE_ORDER);
        } else if (ExternalReplyReadState.unread(message, history, ExternalReplyReadState.CHAT_MESSAGE_ORDER)) {
            conversation.setUnreadCount(conversation.getUnreadCount() + 1);
            conversation.setStatus(statuses.incoming());
        }
        conversations.save(conversation);
    }

    private void applyEdit(UUID messageId, String body) {
        messages.findActiveById(messageId).ifPresent(m -> {
            m.setBody(cut(body, 8000));
            m.setDescription(cut(body, 100));
            messages.save(m);
            conversations.findActiveById(m.getConversation().id()).ifPresent(c -> {
                if (Objects.equals(c.getLastMessageAt(), m.getSentAt())) {
                    c.setLastMessagePreview(cut(body, 180));
                    conversations.save(c);
                }
            });
        });
    }

    private record Route(UUID account, long chat, LocalDateTime lastInbound) {}

    private Optional<Route> route(Conversation c) {
        if (bot == null) return Optional.empty();
        return jdbc.query("SELECT c.account_id,c.chat_id,c.last_inbound FROM onno_crm_tg_business_chats c JOIN onno_crm_tg_business_links l ON l.account_id=c.account_id WHERE c.conversation_id=? AND l.bot_id=?",
                (rs, n) -> new Route(rs.getObject(1, UUID.class), rs.getLong(2), rs.getTimestamp(3).toLocalDateTime()),
                c.getId(), bot.id()).stream().findFirst();
    }

    boolean supports(Conversation c) { return route(c).isPresent(); }

    CrmMessageTransport.Connection connection(Conversation c) {
        var mapped = route(c);
        boolean connected = false;
        String reason = "Telegram business connection is unavailable";
        if (mapped.isPresent()) {
            var r = mapped.get();
            var a = accounts.find(r.account());
            var l = accountLink(a.id());
            boolean recent = r.lastInbound().isAfter(LocalDateTime.now().minusHours(24));
            connected = l.filter(v -> v.enabled() && v.canReply()).isPresent() && c.getInbox() != null
                    && c.getInbox().id().equals(a.inboxId())
                    && inboxes.findActiveById(a.inboxId()).map(Inbox::isActive).orElse(false)
                    && recent;
            if (connected) reason = "Reply as your connected Telegram account";
            else if (!recent) reason = "Telegram business replies require a customer message within the last 24 hours";
        }
        return new CrmMessageTransport.Connection(connected, reason, 4096)
                .withAttachments(attachments.limit(), attachments.unavailableReason());
    }

    void enqueue(Conversation c, ConversationMessage m) {
        if (!connection(c).connected()) throw new IllegalArgumentException("Telegram business chat is not connected");
        if (m.getBody() != null && m.getBody().length() > 4096) throw new IllegalArgumentException("Telegram allows up to 4096 characters");
        if (attachments.of(m).size() > CrmAttachments.MAX_PER_MESSAGE) throw new IllegalArgumentException("Too many attachments");
        var r = route(c).orElseThrow();
        var link = accountLink(r.account()).orElseThrow();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_tg_business_outbox WHERE message_id=?", Integer.class, m.getId()) == 0) {
            jdbc.update("INSERT INTO onno_crm_tg_business_outbox VALUES (?,?,?,?,'QUEUED',NULL,CURRENT_TIMESTAMP)",
                    m.getId(), r.account(), link.connection(), r.chat());
        } else if (jdbc.update("UPDATE onno_crm_tg_business_outbox SET state='QUEUED',connection_id=?,error=NULL,updated_at=CURRENT_TIMESTAMP WHERE message_id=? AND state='FAILED'",
                link.connection(), m.getId()) != 1) {
            throw new IllegalArgumentException("Message is already queued or sent");
        }
    }

    void deliver() {
        if (bot == null || System.currentTimeMillis() < nextSendAt) return;
        var queued = jdbc.query("SELECT o.message_id FROM onno_crm_tg_business_outbox o JOIN onno_crm_tg_business_links l ON l.account_id=o.account_id WHERE l.bot_id=? AND o.state='QUEUED' ORDER BY o.updated_at LIMIT 10",
                (rs, n) -> rs.getObject(1, UUID.class), bot.id());
        for (UUID id : queued) {
            var message = messages.findActiveById(id).orElse(null);
            var conversation = message == null ? null : conversations.findActiveById(message.getConversation().id()).orElse(null);
            if (conversation != null && conversation.getInbox() != null
                    && !inboxes.findActiveById(conversation.getInbox().id()).map(Inbox::isActive).orElse(false)) continue;
            if (!Boolean.TRUE.equals(tx.execute(s -> jdbc.update("UPDATE onno_crm_tg_business_outbox SET state='SENDING',updated_at=CURRENT_TIMESTAMP WHERE message_id=? AND state='QUEUED'", id) == 1))) continue;
            if (!deliver(id, message, conversation)) break;
        }
    }

    /** Deliver one claimed message; false stops the batch after a retryable failure. */
    private boolean deliver(UUID id, ConversationMessage message, Conversation conversation) {
        Route r;
        Link link;
        try {
            if (conversation == null || message.getKind() != MessageKind.AGENT_REPLY || message.getDirection() != MessageDirection.OUTBOUND
                    || message.getDeliveryStatus() != DeliveryStatus.QUEUED || !connection(conversation).connected()) {
                throw new IllegalStateException("Conversation disconnected");
            }
            r = route(conversation).orElseThrow();
            link = accountLink(r.account()).orElseThrow();
            var destination = jdbc.queryForMap("SELECT account_id,connection_id,chat_id FROM onno_crm_tg_business_outbox WHERE message_id=?", id);
            if (!r.account().equals(destination.get("account_id")) || !link.connection().equals(destination.get("connection_id"))
                    || r.chat() != ((Number) destination.get("chat_id")).longValue()) {
                throw new IllegalStateException("Destination changed; retry explicitly");
            }
        } catch (RuntimeException error) {
            tx.executeWithoutResult(s -> finish(id, DeliveryStatus.FAILED, null, "Delivery not confirmed; check Telegram before retrying"));
            return true;
        }
        JsonNode verified;
        try {
            verified = client.get().businessConnection(link.connection());
        } catch (RuntimeException error) {
            // Nothing was sent yet: leave the reply queued and back off, honouring Telegram's retry_after.
            int seconds = error instanceof TelegramClient.ApiFailure failure ? failure.retryAfter() : 0;
            nextSendAt = System.currentTimeMillis() + Math.max(5, seconds) * 1000L;
            tx.executeWithoutResult(s -> jdbc.update("UPDATE onno_crm_tg_business_outbox SET state='QUEUED',updated_at=CURRENT_TIMESTAMP WHERE message_id=? AND state='SENDING'", id));
            log.warn("Telegram business delivery deferred before sending ({})", error.getClass().getSimpleName());
            return false;
        }
        try {
            if (!verified.path("is_enabled").asBoolean() || verified.path("user").path("id").asLong() != link.user()
                    || !canReply(verified)) throw new IllegalStateException("Telegram permission revoked");
            String text = Objects.toString(message.getBody(), "");
            var files = attachments.of(message);
            if (text.length() > 4096 || (text.isBlank() && files.isEmpty()) || files.size() > CrmAttachments.MAX_PER_MESSAGE) {
                throw new IllegalStateException("Invalid reply content");
            }
            long sent = send(r.chat(), text, files, link.connection());
            String externalId = link.connection() + ":" + r.chat() + ":" + sent;
            tx.executeWithoutResult(s -> finish(id, DeliveryStatus.SENT, externalId, null));
            return true;
        } catch (RuntimeException error) {
            tx.executeWithoutResult(s -> finish(id, DeliveryStatus.FAILED, null, "Delivery not confirmed; check Telegram before retrying"));
            if (error instanceof TelegramClient.ApiFailure failure && failure.retryAfter() > 0) {
                nextSendAt = System.currentTimeMillis() + failure.retryAfter() * 1000L;
                return false;
            }
            return true;
        }
    }

    private long send(long chat, String text, List<CrmAttachments.Attachment> files, String connection) {
        TelegramClient telegram = client.get();
        if (files.isEmpty()) return telegram.send(chat, text, connection);
        boolean caption = !text.isBlank() && text.length() <= TelegramClient.CAPTION_LIMIT;
        long sent = !text.isBlank() && !caption ? telegram.send(chat, text, connection) : 0;
        for (int i = 0; i < files.size(); i++) {
            var file = files.get(i);
            long external = telegram.sendFile(chat, file.filename(), file.contentType(), attachments.bytes(file),
                    caption && i == 0 ? text : null, connection);
            if (sent == 0) sent = external;
        }
        return sent;
    }

    private void finish(UUID id, DeliveryStatus status, String external, String error) {
        jdbc.update("UPDATE onno_crm_tg_business_outbox SET state=?,error=?,updated_at=CURRENT_TIMESTAMP WHERE message_id=?", status.name(), error, id);
        messages.findActiveById(id).ifPresent(m -> {
            m.setDeliveryStatus(status);
            if (external != null) m.setExternalMessageId(external);
            messages.save(m);
        });
    }

    public CrmChannelConnection.View view(CrmConnectionAccounts.Account a) {
        var link = bot == null ? Optional.<Link>empty() : accountLink(a.id());
        boolean active = a.inboxId() != null && inboxes.findActiveById(a.inboxId()).map(Inbox::isActive).orElse(false);
        boolean connected = link.filter(l -> l.enabled() && l.canReply()).isPresent();
        String state = connected ? (active ? "CONNECTED" : "PAUSED") : "DISCONNECTED";
        String detail = connected ? "Replies are sent on behalf of your connected Telegram user."
                : "Open the connection link and press Start, then select this bot in Telegram Settings → Chat Automation (or Telegram Business → Chatbots) and allow replies.";
        List<String> actions = available()
                ? (connected ? List.of("check", active ? "pause" : "resume", "disconnect") : List.of("connect"))
                : List.of();
        return new CrmChannelConnection.View(PROVIDER, "Telegram business", "TELEGRAM", state,
                bot == null ? "" : "@" + bot.username(), detail, actions);
    }

    public void command(CrmConnectionAccounts.Account a, String action) {
        if ("check".equals(action)) {
            // Fetch outside the workspace lock; only the write below needs it.
            var link = accountLink(a.id()).orElseThrow();
            JsonNode metadata = client.get().businessConnection(link.connection());
            tx.executeWithoutResult(s -> {
                workspace.lock();
                connectionEvent(metadata);
            });
            return;
        }
        if (!"pause".equals(action) && !"resume".equals(action) && !"disconnect".equals(action)) {
            throw new IllegalArgumentException("Unsupported account action");
        }
        tx.executeWithoutResult(s -> {
            workspace.lock();
            if (a.inboxId() == null) throw new IllegalArgumentException("Account is not connected");
            var inbox = inboxes.findActiveById(a.inboxId()).orElseThrow();
            inbox.setActive("resume".equals(action));
            inboxes.save(inbox);
            if ("disconnect".equals(action)) {
                jdbc.update("UPDATE onno_crm_tg_business_links SET connection_id=NULL,authorized=false WHERE account_id=?", a.id());
                jdbc.update("DELETE FROM onno_crm_tg_business_auth WHERE account_id=?", a.id());
                jdbc.query("SELECT message_id FROM onno_crm_tg_business_outbox WHERE account_id=? AND state='QUEUED'",
                                (rs, n) -> rs.getObject(1, UUID.class), a.id())
                        .forEach(id -> finish(id, DeliveryStatus.FAILED, null, "Account disconnected"));
            }
        });
        accounts.changed();
    }

    private static String cut(String value, int max) { return value.length() > max ? value.substring(0, max) : value; }
}
