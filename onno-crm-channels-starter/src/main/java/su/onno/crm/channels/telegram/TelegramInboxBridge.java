package su.onno.crm.channels.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.CrmAttachments;
import su.onno.crm.service.CrmChannelConnection;
import su.onno.crm.service.CrmContactService;
import su.onno.crm.service.CrmConversationStatuses;
import su.onno.crm.service.CrmCustomerBinding;
import su.onno.crm.service.CrmMessageTransport;
import su.onno.crm.service.CrmWorkspaceService;
import su.onno.types.Ref;

/**
 * Polling owner for one Telegram bot: ordinary bot chats go to a shared inbox, business updates
 * are routed to {@link TelegramBusinessAccounts}.
 *
 * <p>Each update is handled on its own: network work (file downloads, business connection lookups)
 * happens before the transaction and workspace lock, and an update that keeps failing is skipped
 * after {@value #MAX_ATTEMPTS} attempts so it can never halt intake for every other chat.
 */
public class TelegramInboxBridge implements CrmMessageTransport {
    private static final Logger log = LoggerFactory.getLogger(TelegramInboxBridge.class);
    /** Attempts before a failing update is skipped; transient database errors get retried first. */
    static final int MAX_ATTEMPTS = 3;

    private volatile TelegramClient client;
    private final ReentrantReadWriteLock credentialLock = new ReentrantReadWriteLock();
    private final CrmContactService contacts;
    private final CrmWorkspaceService workspace;
    private final CrmAttachments attachments;
    private final CrmConversationStatuses statuses;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final InboxRepository inboxes;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final TelegramBusinessAccounts business;
    private final boolean regularChatsEnabled;
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(3, r -> {
        Thread thread = new Thread(r, "crm-telegram");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean ready;
    private TelegramClient.Bot bot;
    private volatile long nextPollAt;
    private volatile long nextSendAt;

    // Touched only by the polling worker.
    private long failingUpdate = -1;
    private int failedAttempts;
    private long preparedUpdate = -1;
    private TelegramPreparedUpdate prepared = TelegramPreparedUpdate.NONE;

    /**
     * @param business            business-connection routing, or null for a bot without it
     * @param regularChatsEnabled whether ordinary (non-business) bot chats are accepted at all
     */
    public TelegramInboxBridge(TelegramClient client, JdbcTemplate jdbc, PlatformTransactionManager transactions,
                               InboxRepository inboxes, ConversationRepository conversations,
                               ConversationMessageRepository messages, CrmContactService contacts,
                               CrmWorkspaceService workspace, CrmConversationStatuses statuses,
                               CrmAttachments attachments, TelegramBusinessAccounts business,
                               boolean regularChatsEnabled) {
        this.client = client;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.inboxes = inboxes;
        this.conversations = conversations;
        this.messages = messages;
        this.contacts = contacts;
        this.workspace = workspace;
        this.statuses = statuses;
        this.attachments = attachments;
        this.business = business;
        this.regularChatsEnabled = regularChatsEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            bot = client.bot();
            if (client.hasWebhook()) {
                log.warn("Telegram polling disabled: this bot already has a webhook");
                return;
            }
            initialize(bot);
            ready = true;
            workers.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
            workers.scheduleWithFixedDelay(this::deliver, 1, 1, TimeUnit.SECONDS);
            workers.scheduleWithFixedDelay(this::refreshAvatars, 2, 60, TimeUnit.SECONDS);
            log.info("Telegram connected as @{} ({})", bot.username(),
                    regularChatsEnabled ? "private bot chats" : "business connections only");
        } catch (RuntimeException ex) {
            log.warn("Telegram initialization failed; delivery is disabled ({})", ex.getClass().getSimpleName());
        }
    }

    void initialize(TelegramClient.Bot identity) {
        bot = identity;
        if (business != null) business.initialize(identity, () -> client);
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_telegram_avatar (identity_id UUID PRIMARY KEY,content TEXT NOT NULL,checked_at TIMESTAMP NOT NULL)");
        // inbox_id stays null while ordinary chats are turned off (business-only bot).
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_telegram_connection (bot_id BIGINT PRIMARY KEY, next_update BIGINT NOT NULL, inbox_id UUID)");
        jdbc.execute("ALTER TABLE onno_crm_telegram_connection ALTER COLUMN inbox_id DROP NOT NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_telegram_chat (bot_id BIGINT NOT NULL, chat_id BIGINT NOT NULL, customer_id UUID NOT NULL, conversation_id UUID NOT NULL UNIQUE, PRIMARY KEY(bot_id, chat_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_telegram_outbox (message_id UUID PRIMARY KEY, bot_id BIGINT NOT NULL, chat_id BIGINT NOT NULL, state VARCHAR(20) NOT NULL, attempts INT NOT NULL, error VARCHAR(500), updated_at TIMESTAMP NOT NULL)");
        tx.executeWithoutResult(status -> {
            boolean known = jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_telegram_connection WHERE bot_id=?",
                    Integer.class, bot.id()) > 0;
            if (!known) {
                jdbc.update("INSERT INTO onno_crm_telegram_connection VALUES (?, 0, NULL)", bot.id());
            }
            if (regularChatsEnabled && inboxId() == null) {
                jdbc.update("UPDATE onno_crm_telegram_connection SET inbox_id=? WHERE bot_id=?", createInbox().getId(), bot.id());
            }
            List<Object[]> chats = jdbc.query("SELECT chat_id,conversation_id FROM onno_crm_telegram_chat WHERE bot_id=?",
                    (rs, n) -> new Object[]{rs.getLong(1), rs.getObject(2, UUID.class)}, bot.id());
            for (Object[] mapping : chats) {
                conversations.findActiveById((UUID) mapping[1]).ifPresent(c -> contacts.link(c.getCustomer(), Channel.TELEGRAM,
                        Long.toString(bot.id()), mapping[0].toString(), "Telegram " + mapping[0], true));
            }
            List<UUID> uncertain = jdbc.query("SELECT message_id FROM onno_crm_telegram_outbox WHERE bot_id=? AND state='SENDING'",
                    (rs, row) -> rs.getObject(1, UUID.class), bot.id());
            for (UUID id : uncertain) {
                finish(id, DeliveryStatus.FAILED, null, "Delivery was interrupted; check Telegram before retrying");
            }
        });
        ready = true;
    }

    private Inbox createInbox() {
        Inbox inbox = new Inbox();
        inbox.setDescription("Telegram @" + bot.username());
        inbox.setAddress("@" + bot.username());
        inbox.setChannel(Channel.TELEGRAM);
        inbox.setAccentColor("#229ED9");
        inbox.setActive(true);
        return inboxes.save(inbox);
    }

    void refreshAvatars() {
        credentialLock.readLock().lock();
        try {
            refreshAvatarsCurrent();
        } finally {
            credentialLock.readLock().unlock();
        }
    }

    private void refreshAvatarsCurrent() {
        if (!ready || !regularChatsEnabled) return;
        try {
            var mapped = jdbc.query("SELECT chat_id,conversation_id FROM onno_crm_telegram_chat WHERE bot_id=?",
                    (rs, n) -> new Object[]{rs.getLong(1), rs.getObject(2, UUID.class)}, bot.id());
            for (var mapping : mapped) {
                var conversation = conversations.findActiveById((UUID) mapping[1]).orElse(null);
                if (conversation == null) continue;
                var identity = contacts.link(conversation.getCustomer(), Channel.TELEGRAM, Long.toString(bot.id()),
                        mapping[0].toString(), "Telegram " + mapping[0], true);
                var recent = jdbc.query("SELECT checked_at FROM onno_crm_telegram_avatar WHERE identity_id=?",
                        (rs, n) -> rs.getTimestamp(1).toInstant(), identity.getId());
                if (!recent.isEmpty() && recent.getFirst().isAfter(Instant.now().minusSeconds(3600))) continue;
                byte[] image = client.profilePhoto((Long) mapping[0]);
                String encoded = image == null ? "" : Base64.getEncoder().encodeToString(image);
                tx.executeWithoutResult(status -> {
                    workspace.lock();
                    if (jdbc.update("UPDATE onno_crm_telegram_avatar SET content=?,checked_at=CURRENT_TIMESTAMP WHERE identity_id=?",
                            encoded, identity.getId()) == 0) {
                        jdbc.update("INSERT INTO onno_crm_telegram_avatar VALUES (?,?,CURRENT_TIMESTAMP)", identity.getId(), encoded);
                    }
                    contacts.setIdentityAvatar(identity.getId(), image == null ? null : "/api/crm/telegram/avatars/" + identity.getId());
                });
            }
        } catch (RuntimeException e) {
            log.debug("Telegram profile photo refresh deferred ({})", e.getClass().getSimpleName());
        }
    }

    public CrmChannelConnection.View channelView(boolean reconnectAvailable) {
        var actions = new ArrayList<String>();
        actions.add("check");
        String account = bot == null ? "" : "@" + bot.username();
        if (!regularChatsEnabled) {
            if (reconnectAvailable) actions.add("reconnect");
            return new CrmChannelConnection.View("telegram", "Telegram", "TELEGRAM", ready ? "UNAVAILABLE" : "ERROR", account,
                    "Ordinary bot chats are turned off on the server; this bot serves Telegram business connections only.",
                    actions);
        }
        boolean active = ready && regularActive();
        if (ready) actions.add(active ? "pause" : "resume");
        if (reconnectAvailable) actions.add("reconnect");
        return new CrmChannelConnection.View("telegram", "Telegram", "TELEGRAM",
                !ready ? "ERROR" : active ? "CONNECTED" : "PAUSED", account,
                "Private conversations with your Telegram bot. Pausing keeps chat history and queued replies; "
                        + "messages sent to the bot while paused are not imported.", actions);
    }

    public synchronized void checkConnection() {
        var verified = client.bot();
        if (bot != null && verified.id() != bot.id()) throw new IllegalArgumentException("The configured bot changed; reconnect the original bot");
        if (client.hasWebhook()) throw new IllegalArgumentException("This bot has a webhook. Remove it in your Telegram integration before using polling.");
        if (!ready) throw new IllegalArgumentException("Restart the CRM to initialize this connection");
    }

    public synchronized void setChannelActive(boolean active) {
        if (!ready) throw new IllegalArgumentException("Telegram is not initialized");
        if (!regularChatsEnabled) throw new IllegalArgumentException("Ordinary Telegram bot chats are turned off on the server");
        tx.executeWithoutResult(status -> {
            Inbox inbox = inboxes.findActiveById(inboxId()).orElseThrow();
            inbox.setActive(active);
            inboxes.save(inbox);
        });
    }

    public void reconnect(TelegramClient replacement, Runnable persistCredential) {
        credentialLock.writeLock().lock();
        try {
            reconnectCurrent(replacement, persistCredential);
        } finally {
            credentialLock.writeLock().unlock();
        }
    }

    private void reconnectCurrent(TelegramClient replacement, Runnable persistCredential) {
        var verified = replacement.bot();
        if (bot == null || verified.id() != bot.id()) throw new IllegalArgumentException("Use a token for the currently connected bot");
        if (replacement.hasWebhook()) throw new IllegalArgumentException("This bot has a webhook. Remove it in your Telegram integration before using polling.");
        persistCredential.run();
        client = replacement;
        bot = verified;
    }

    /** The ordinary-chat inbox, or null while ordinary chats have never been enabled for this bot. */
    private UUID inboxId() {
        return jdbc.queryForObject("SELECT inbox_id FROM onno_crm_telegram_connection WHERE bot_id=?", UUID.class, bot.id());
    }

    private Optional<Inbox> regularInbox() {
        if (!regularChatsEnabled) return Optional.empty();
        UUID id = inboxId();
        return id == null ? Optional.empty() : inboxes.findActiveById(id);
    }

    private boolean regularActive() {
        return regularInbox().map(Inbox::isActive).orElse(false);
    }

    @Override
    public boolean supports(Conversation conversation) { return Channel.TELEGRAM.equals(conversation.getChannel()); }

    public Connection connection(Conversation conversation) {
        if (ready && business != null && business.supports(conversation)) return business.connection(conversation);
        if (!ready || !Channel.TELEGRAM.equals(conversation.getChannel()) || conversation.getInbox() == null) {
            return new Connection(false, "Messaging channel is not connected", 4096);
        }
        if (!regularChatsEnabled) {
            return new Connection(false, "Ordinary Telegram bot chats are turned off", 4096);
        }
        boolean mapped = jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_telegram_chat WHERE bot_id=? AND conversation_id=?",
                Integer.class, bot.id(), conversation.getId()) == 1;
        boolean active = Objects.equals(conversation.getInbox().id(), inboxId()) && regularActive();
        return new Connection(mapped && active, mapped && active ? "Telegram @" + bot.username()
                : "No Telegram chat linked — message @" + bot.username() + " to start", 4096)
                .withAttachments(attachments.limit(), attachments.unavailableReason());
    }

    @Override
    public void enqueue(Conversation conversation, ConversationMessage message) {
        if (business != null && business.supports(conversation)) {
            business.enqueue(conversation, message);
            return;
        }
        if (!connection(conversation).connected()) throw new IllegalArgumentException("Telegram chat is not connected");
        if (message.getBody() != null && message.getBody().length() > 4096)
            throw new IllegalArgumentException("Telegram allows up to 4096 characters");
        if (attachments.of(message).size() > CrmAttachments.MAX_PER_MESSAGE)
            throw new IllegalArgumentException("Telegram carries at most " + CrmAttachments.MAX_PER_MESSAGE + " files per message");
        long chatId = jdbc.queryForObject("SELECT chat_id FROM onno_crm_telegram_chat WHERE bot_id=? AND conversation_id=?",
                Long.class, bot.id(), conversation.getId());
        int existing = jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_telegram_outbox WHERE message_id=?", Integer.class, message.getId());
        if (existing == 0) {
            jdbc.update("INSERT INTO onno_crm_telegram_outbox VALUES (?, ?, ?, 'QUEUED', 0, NULL, CURRENT_TIMESTAMP)",
                    message.getId(), bot.id(), chatId);
        } else if (jdbc.update("UPDATE onno_crm_telegram_outbox SET state='QUEUED', error=NULL, updated_at=CURRENT_TIMESTAMP WHERE message_id=? AND bot_id=? AND state='FAILED'",
                message.getId(), bot.id()) != 1) {
            throw new IllegalArgumentException("Message is already queued or sent");
        }
    }

    void poll() {
        credentialLock.readLock().lock();
        try {
            pollCurrent();
        } finally {
            credentialLock.readLock().unlock();
        }
    }

    private void pollCurrent() {
        if (!ready || System.currentTimeMillis() < nextPollAt) return;
        try {
            long offset = jdbc.queryForObject("SELECT next_update FROM onno_crm_telegram_connection WHERE bot_id=?", Long.class, bot.id());
            if (!process(client.updates(offset))) backOff(5);
        } catch (RuntimeException ex) {
            backOff(ex instanceof TelegramClient.ApiFailure failure ? failure.retryAfter() : 0);
            log.warn("Telegram receive paused; checkpoint retained ({})", ex.getClass().getSimpleName());
        }
    }

    private void backOff(int seconds) {
        nextPollAt = System.currentTimeMillis() + Math.max(5, seconds) * 1000L + (long) (Math.random() * 1000);
    }

    /** Handle a batch in order; false when an update must be retried later (the checkpoint stays on it). */
    boolean process(List<TelegramClient.Update> updates) {
        for (TelegramClient.Update update : updates) {
            if (!process(update)) return false;
        }
        return true;
    }

    private boolean process(TelegramClient.Update update) {
        TelegramPreparedUpdate work = prepared(update);
        try {
            tx.executeWithoutResult(status -> receive(update, work));
            forget();
            return true;
        } catch (RuntimeException error) {
            failedAttempts = update.id() == failingUpdate ? failedAttempts + 1 : 1;
            failingUpdate = update.id();
            if (failedAttempts < MAX_ATTEMPTS) {
                log.warn("Telegram update {} deferred, attempt {} of {} ({})",
                        update.id(), failedAttempts, MAX_ATTEMPTS, error.getClass().getSimpleName());
                return false;
            }
            log.error("Telegram update {} skipped after {} failed attempts ({})",
                    update.id(), failedAttempts, error.getClass().getSimpleName());
            tx.executeWithoutResult(status -> advance(update.id()));
            forget();
            return true;
        }
    }

    private void forget() {
        failingUpdate = -1;
        failedAttempts = 0;
        preparedUpdate = -1;
        prepared = TelegramPreparedUpdate.NONE;
    }

    /** Network work for one update, kept across retries so a file is downloaded once. Never throws. */
    private TelegramPreparedUpdate prepared(TelegramClient.Update update) {
        if (preparedUpdate == update.id()) return prepared;
        TelegramPreparedUpdate work;
        try {
            work = prepare(update);
        } catch (RuntimeException error) {
            log.warn("Telegram update {} prepared without files ({})", update.id(), error.getClass().getSimpleName());
            work = TelegramPreparedUpdate.NONE;
        }
        preparedUpdate = update.id();
        prepared = work;
        return work;
    }

    private TelegramPreparedUpdate prepare(TelegramClient.Update update) {
        if (business != null && business.consumes(update)) return business.prepare(update);
        if (!acceptsOrdinary(update)) return TelegramPreparedUpdate.NONE;
        JsonNode message = ordinaryMessage(update);
        if (!TelegramAttachments.hasFile(message)) return TelegramPreparedUpdate.NONE;
        return new TelegramPreparedUpdate(null, TelegramAttachments.read(message, client, attachments));
    }

    private boolean acceptsOrdinary(TelegramClient.Update update) {
        return regularChatsEnabled && update.privateChat() && update.text() != null && regularActive();
    }

    private static JsonNode ordinaryMessage(TelegramClient.Update update) {
        return update.payload() == null ? null : update.payload().path("message");
    }

    /**
     * Apply one update inside the polling transaction. Ordinary messages arriving while the ordinary
     * inbox is paused (or ordinary chats are turned off) are discarded; the offset still advances.
     */
    void receive(TelegramClient.Update update, TelegramPreparedUpdate work) {
        workspace.lock();
        long offset = jdbc.queryForObject("SELECT next_update FROM onno_crm_telegram_connection WHERE bot_id=? FOR UPDATE", Long.class, bot.id());
        if (update.id() < offset) return;
        boolean handled = business != null && business.receive(update, work);
        if (!handled && acceptsOrdinary(update)) receiveOrdinary(update, work);
        advance(update.id());
    }

    private void advance(long updateId) {
        jdbc.update("UPDATE onno_crm_telegram_connection SET next_update=? WHERE bot_id=? AND next_update<=?",
                updateId + 1, bot.id(), updateId);
    }

    private void receiveOrdinary(TelegramClient.Update update, TelegramPreparedUpdate work) {
        List<UUID> ids = jdbc.query("SELECT conversation_id FROM onno_crm_telegram_chat WHERE bot_id=? AND chat_id=?",
                (rs, row) -> rs.getObject(1, UUID.class), bot.id(), update.chatId());
        Conversation conversation;
        if (ids.isEmpty()) {
            String name = cut(update.name().isBlank() ? "Telegram " + update.senderId() : update.name(), 200);
            UUID customer = contacts.resolveIncoming(new CrmCustomerBinding.IncomingContact(Channel.TELEGRAM,
                    Long.toString(bot.id()), Long.toString(update.chatId()), name, null, null, null, "Telegram @" + bot.username()));
            conversation = new Conversation();
            conversation.setCustomer(customer);
            conversation.setInbox(Ref.of(Inbox.class, inboxId()));
            conversation.setChannel(Channel.TELEGRAM);
            conversation.setSubject(cut("Telegram · " + name, 240));
            conversation.setDescription(conversation.getSubject());
            conversations.save(conversation);
            jdbc.update("INSERT INTO onno_crm_telegram_chat VALUES (?, ?, ?, ?)", bot.id(), update.chatId(), customer, conversation.getId());
        } else {
            // Deleted conversations are never silently revived by an external sender.
            conversation = conversations.findActiveById(ids.getFirst()).orElse(null);
        }
        if (conversation == null) return;
        UUID canonical = contacts.canonical(conversation.getCustomer());
        conversation.setCustomer(canonical);
        contacts.link(canonical, Channel.TELEGRAM, Long.toString(bot.id()), Long.toString(update.chatId()),
                update.username().isBlank() ? update.name() : "@" + update.username(), true);
        var imported = work.filesFor(ordinaryMessage(update));
        ConversationMessage message = new ConversationMessage();
        message.setConversation(Ref.of(Conversation.class, conversation.getId()));
        message.setChannel(Channel.TELEGRAM);
        message.setKind(MessageKind.CUSTOMER_MESSAGE);
        message.setDirection(MessageDirection.INBOUND);
        message.setAuthorName(cut(update.name().isBlank() ? "Telegram user" : update.name(), 200));
        message.setAttachments(imported.urls());
        message.setBody(cut(update.text() + (imported.failure().isBlank() ? "" : "\n" + imported.failure()), 8000));
        message.setDescription(cut(update.text(), 100));
        message.setSentAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(update.date()), ZoneId.systemDefault()));
        message.setDeliveryStatus(DeliveryStatus.RECEIVED);
        message.setExternalMessageId(bot.id() + ":" + update.chatId() + ":" + update.messageId());
        messages.save(message);
        conversation.setLastMessageAt(message.getSentAt());
        conversation.setLastMessagePreview(cut(update.text(), 180));
        conversation.setUnreadCount(conversation.getUnreadCount() + 1);
        conversation.setStatus(statuses.incoming());
        conversations.save(conversation);
    }

    void deliver() {
        credentialLock.readLock().lock();
        try {
            deliverCurrent();
        } finally {
            credentialLock.readLock().unlock();
        }
    }

    private void deliverCurrent() {
        if (ready && business != null) {
            try {
                business.deliver();
            } catch (RuntimeException ex) {
                log.warn("Telegram business delivery deferred ({})", ex.getClass().getSimpleName());
            }
        }
        if (!ready || System.currentTimeMillis() < nextSendAt || !regularActive()) return;
        try {
            List<UUID> queued = jdbc.query("SELECT message_id FROM onno_crm_telegram_outbox WHERE bot_id=? AND state='QUEUED' ORDER BY updated_at LIMIT 10",
                    (rs, row) -> rs.getObject(1, UUID.class), bot.id());
            for (UUID id : queued) {
                Boolean claimed = tx.execute(status -> jdbc.update("UPDATE onno_crm_telegram_outbox SET state='SENDING', attempts=attempts+1, updated_at=CURRENT_TIMESTAMP WHERE message_id=? AND state='QUEUED'", id) == 1);
                if (!Boolean.TRUE.equals(claimed)) continue;
                try {
                    ConversationMessage message = messages.findActiveById(id).orElseThrow();
                    Conversation conversation = conversations.findActiveById(message.getConversation().id()).orElseThrow();
                    if (message.getKind() != MessageKind.AGENT_REPLY || message.getDirection() != MessageDirection.OUTBOUND
                            || !connection(conversation).connected()) throw new IllegalStateException("Conversation disconnected");
                    long chatId = jdbc.queryForObject("SELECT chat_id FROM onno_crm_telegram_outbox WHERE message_id=?", Long.class, id);
                    long currentChat = jdbc.queryForObject("SELECT chat_id FROM onno_crm_telegram_chat WHERE bot_id=? AND conversation_id=?",
                            Long.class, bot.id(), conversation.getId());
                    var files = attachments.of(message);
                    String text = message.getBody() == null ? "" : message.getBody();
                    if (currentChat != chatId || message.getDeliveryStatus() != DeliveryStatus.QUEUED
                            || (text.isBlank() && files.isEmpty()) || text.length() > 4096) {
                        throw new IllegalStateException("Queued message no longer matches its destination or content limits");
                    }
                    long externalId = send(chatId, text, files);
                    tx.executeWithoutResult(status -> finish(id, DeliveryStatus.SENT, Long.toString(externalId), null));
                } catch (RuntimeException ex) {
                    String error = ex instanceof TelegramClient.ApiFailure ? ex.getMessage()
                            : "Delivery could not be confirmed; check Telegram before retrying";
                    tx.executeWithoutResult(status -> finish(id, DeliveryStatus.FAILED, null, error));
                    if (ex instanceof TelegramClient.ApiFailure failure && failure.retryAfter() > 0) {
                        nextSendAt = System.currentTimeMillis() + failure.retryAfter() * 1000L;
                        break;
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Telegram delivery worker paused ({})", ex.getClass().getSimpleName());
        }
    }

    /**
     * Send one reply. With no files it is a plain message. With files, a short note rides as the
     * caption of the first file — as it would if a person had sent it — and a note too long for a
     * caption goes first as its own message, because Telegram would otherwise silently truncate it.
     *
     * <p>The id kept is the first message Telegram accepted. A failure partway through leaves the
     * earlier files delivered, so the error says to check the chat rather than implying nothing was
     * sent; the retry path is a person's decision, not an automatic resend.
     */
    private long send(long chatId, String text, List<CrmAttachments.Attachment> files) {
        if (files.isEmpty()) return client.send(chatId, text);
        boolean captioned = !text.isBlank() && text.length() <= TelegramClient.CAPTION_LIMIT;
        long first = 0;
        if (!text.isBlank() && !captioned) first = client.send(chatId, text);
        for (int index = 0; index < files.size(); index++) {
            var file = files.get(index);
            long sent = client.sendFile(chatId, file.filename(), file.contentType(), attachments.bytes(file),
                    captioned && index == 0 ? text : null);
            if (first == 0) first = sent;
        }
        return first;
    }

    private void finish(UUID id, DeliveryStatus result, String externalId, String error) {
        jdbc.update("UPDATE onno_crm_telegram_outbox SET state=?, error=?, updated_at=CURRENT_TIMESTAMP WHERE message_id=?",
                result.name(), error, id);
        messages.findActiveById(id).ifPresent(message -> {
            message.setDeliveryStatus(result);
            if (externalId != null) message.setExternalMessageId(externalId);
            messages.save(message);
        });
    }

    private static String cut(String text, int size) { return text.length() <= size ? text : text.substring(0, size); }

    @PreDestroy
    public void stop() {
        ready = false;
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
