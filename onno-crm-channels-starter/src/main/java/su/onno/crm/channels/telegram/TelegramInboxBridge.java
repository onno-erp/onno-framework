package su.onno.crm.channels.telegram;
import su.onno.crm.service.CrmCustomerBinding;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.CrmMessageTransport;
import su.onno.types.Ref;

/** Host mapping and durable inbox/outbox. Only this app knows the CRM model; the client does not. */
public class TelegramInboxBridge implements CrmMessageTransport {
    private static final Logger log = LoggerFactory.getLogger(TelegramInboxBridge.class);
    private volatile TelegramClient client;
    private final java.util.concurrent.locks.ReentrantReadWriteLock credentialLock = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final su.onno.crm.service.CrmContactService contacts;
    private final su.onno.crm.service.CrmWorkspaceService workspace;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private final InboxRepository inboxes;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(3, r -> {
        Thread thread = new Thread(r, "crm-telegram");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean ready;
    private TelegramClient.Bot bot;
    private volatile long nextPollAt;
    private volatile long nextSendAt;

    private final su.onno.crm.service.CrmConversationStatuses statuses;
    public TelegramInboxBridge(TelegramClient client, JdbcTemplate jdbc, PlatformTransactionManager transactions,
             InboxRepository inboxes, ConversationRepository conversations,
            ConversationMessageRepository messages, su.onno.crm.service.CrmContactService contacts, su.onno.crm.service.CrmWorkspaceService workspace, su.onno.crm.service.CrmConversationStatuses statuses) {
        this.statuses=statuses;
        this.client = client;
        this.contacts = contacts; this.workspace = workspace;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);

        this.inboxes = inboxes;
        this.conversations = conversations;
        this.messages = messages;
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
            log.info("Telegram connected as @{} (private text chats)", bot.username());
        } catch (RuntimeException ex) {
            log.warn("Telegram initialization failed; delivery is disabled ({})", ex.getClass().getSimpleName());
        }
    }

    void initialize(TelegramClient.Bot identity) {
        bot = identity;
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_telegram_avatar (identity_id UUID PRIMARY KEY,content TEXT NOT NULL,checked_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_telegram_connection (bot_id BIGINT PRIMARY KEY, next_update BIGINT NOT NULL, inbox_id UUID NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_telegram_chat (bot_id BIGINT NOT NULL, chat_id BIGINT NOT NULL, customer_id UUID NOT NULL, conversation_id UUID NOT NULL UNIQUE, PRIMARY KEY(bot_id, chat_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_telegram_outbox (message_id UUID PRIMARY KEY, bot_id BIGINT NOT NULL, chat_id BIGINT NOT NULL, state VARCHAR(20) NOT NULL, attempts INT NOT NULL, error VARCHAR(500), updated_at TIMESTAMP NOT NULL)");
        tx.executeWithoutResult(status -> {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_telegram_connection WHERE bot_id=?", Integer.class, bot.id()) == 0) {
                Inbox inbox = new Inbox();
                inbox.setDescription("Telegram @" + bot.username());
                inbox.setAddress("@" + bot.username());
                inbox.setChannel(Channel.TELEGRAM);
                inbox.setAccentColor("#229ED9");
                inboxes.save(inbox);
                jdbc.update("INSERT INTO onno_crm_telegram_connection VALUES (?, 0, ?)", bot.id(), inbox.getId());
            }
            jdbc.query("SELECT chat_id,conversation_id FROM onno_crm_telegram_chat WHERE bot_id=?", (rs,n) -> new Object[]{rs.getLong(1),rs.getObject(2,UUID.class)},bot.id()).forEach(mapping -> {
                conversations.findActiveById((UUID)mapping[1]).ifPresent(c -> contacts.link(c.getCustomer(),Channel.TELEGRAM,Long.toString(bot.id()),mapping[0].toString(),"Telegram " + mapping[0],true));
            });
            List<UUID> uncertain = jdbc.query("SELECT message_id FROM onno_crm_telegram_outbox WHERE bot_id=? AND state='SENDING'",
                    (rs, row) -> rs.getObject(1, UUID.class), bot.id());
            for (UUID id : uncertain) finish(id, DeliveryStatus.FAILED, null,
                    "Delivery was interrupted; check Telegram before retrying");
        });
        ready = true;
    }

    void refreshAvatars() {
        credentialLock.readLock().lock();
        try { refreshAvatarsCurrent(); } finally { credentialLock.readLock().unlock(); }
    }

    private void refreshAvatarsCurrent() {
        if(!ready)return;
        try {
            var mapped=jdbc.query("SELECT chat_id,conversation_id FROM onno_crm_telegram_chat WHERE bot_id=?",(rs,n)->new Object[]{rs.getLong(1),rs.getObject(2,UUID.class)},bot.id());
            for(var mapping:mapped) {
                var conversation=conversations.findActiveById((UUID)mapping[1]).orElse(null);if(conversation==null)continue;
                var identity=contacts.link(conversation.getCustomer(),Channel.TELEGRAM,Long.toString(bot.id()),mapping[0].toString(),"Telegram "+mapping[0],true);
                var recent=jdbc.query("SELECT checked_at FROM onno_crm_telegram_avatar WHERE identity_id=?",(rs,n)->rs.getTimestamp(1).toInstant(),identity.getId());
                if(!recent.isEmpty()&&recent.getFirst().isAfter(Instant.now().minusSeconds(3600)))continue;
                byte[] image=client.profilePhoto((Long)mapping[0]);
                String encoded=image==null?"":java.util.Base64.getEncoder().encodeToString(image);
                tx.executeWithoutResult(status->{
                    workspace.lock();
                    if(jdbc.update("UPDATE onno_crm_telegram_avatar SET content=?,checked_at=CURRENT_TIMESTAMP WHERE identity_id=?",encoded,identity.getId())==0)
                        jdbc.update("INSERT INTO onno_crm_telegram_avatar VALUES (?,?,CURRENT_TIMESTAMP)",identity.getId(),encoded);
                    contacts.setIdentityAvatar(identity.getId(), image==null?null:"/api/crm/telegram/avatars/"+identity.getId());
                });
            }
        } catch(RuntimeException e) {log.debug("Telegram profile photo refresh deferred ({})",e.getClass().getSimpleName());}
    }

    public su.onno.crm.service.CrmChannelConnection.View channelView(boolean reconnectAvailable) {
        boolean active = ready && inboxes.findActiveById(inboxId()).map(Inbox::isActive).orElse(false);
        var actions = new java.util.ArrayList<String>();
        actions.add("check");
        if (ready) actions.add(active ? "pause" : "resume");
        if (reconnectAvailable) actions.add("reconnect");
        return new su.onno.crm.service.CrmChannelConnection.View("telegram", "Telegram", "TELEGRAM",
                !ready ? "ERROR" : active ? "CONNECTED" : "PAUSED", bot == null ? "" : "@" + bot.username(),
                "Private conversations with your Telegram bot. Pausing keeps chat history and queued replies.", actions);
    }

    public synchronized void checkConnection() {
        var verified = client.bot();
        if (bot != null && verified.id() != bot.id()) throw new IllegalArgumentException("The configured bot changed; reconnect the original bot");
        if (client.hasWebhook()) throw new IllegalArgumentException("This bot has a webhook. Remove it in your Telegram integration before using polling.");
        if (!ready) throw new IllegalArgumentException("Restart the CRM to initialize this connection");
    }

    public synchronized void setChannelActive(boolean active) {
        if (!ready) throw new IllegalArgumentException("Telegram is not initialized");
        tx.executeWithoutResult(status -> {
            Inbox inbox = inboxes.findActiveById(inboxId()).orElseThrow();
            inbox.setActive(active); inboxes.save(inbox);
        });
    }

    public void reconnect(TelegramClient replacement, Runnable persistCredential) {
        credentialLock.writeLock().lock();
        try { reconnectCurrent(replacement,persistCredential); } finally { credentialLock.writeLock().unlock(); }
    }

    private void reconnectCurrent(TelegramClient replacement, Runnable persistCredential) {
        var verified = replacement.bot();
        if (bot == null || verified.id() != bot.id()) throw new IllegalArgumentException("Use a token for the currently connected bot");
        if (replacement.hasWebhook()) throw new IllegalArgumentException("This bot has a webhook. Remove it in your Telegram integration before using polling.");
        persistCredential.run();
        client = replacement;
        bot = verified;
    }

    private UUID inboxId() {
        return jdbc.queryForObject("SELECT inbox_id FROM onno_crm_telegram_connection WHERE bot_id=?", UUID.class, bot.id());
    }

    @Override
    public boolean supports(Conversation conversation) { return Channel.TELEGRAM.equals(conversation.getChannel()); }

    public Connection connection(Conversation conversation) {
        if (!ready || !Channel.TELEGRAM.equals(conversation.getChannel()) || conversation.getInbox() == null) {
            return new Connection(false, "Messaging channel is not connected", 4096);
        }
        boolean mapped = jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_telegram_chat WHERE bot_id=? AND conversation_id=?",
                Integer.class, bot.id(), conversation.getId()) == 1;
        boolean active = conversation.getInbox().id().equals(inboxId())
                && inboxes.findActiveById(conversation.getInbox().id()).map(Inbox::isActive).orElse(false);
        return new Connection(mapped && active, mapped && active ? "Telegram @" + bot.username()
                : "No Telegram chat linked — message @" + bot.username() + " to start", 4096);
    }

    @Override
    public void enqueue(Conversation conversation, ConversationMessage message) {
        if (!connection(conversation).connected()) throw new IllegalArgumentException("Telegram chat is not connected");
        if (message.getBody().length() > 4096) throw new IllegalArgumentException("Telegram allows up to 4096 characters");
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
        try { pollCurrent(); } finally { credentialLock.readLock().unlock(); }
    }

    private void pollCurrent() {
        if (!ready || System.currentTimeMillis() < nextPollAt) return;
        try {
            if (!inboxes.findActiveById(inboxId()).map(Inbox::isActive).orElse(false)) return;
            long offset = jdbc.queryForObject("SELECT next_update FROM onno_crm_telegram_connection WHERE bot_id=?", Long.class, bot.id());
            for (TelegramClient.Update update : client.updates(offset)) {
                tx.executeWithoutResult(status -> receive(update));
            }
        } catch (RuntimeException ex) {
            int seconds = ex instanceof TelegramClient.ApiFailure failure ? Math.max(5, failure.retryAfter()) : 5;
            nextPollAt = System.currentTimeMillis() + seconds * 1000L + (long) (Math.random() * 1000);
            log.warn("Telegram receive paused; checkpoint retained ({})", ex.getClass().getSimpleName());
        }
    }

    void receive(TelegramClient.Update update) {
        workspace.lock();
        long offset = jdbc.queryForObject("SELECT next_update FROM onno_crm_telegram_connection WHERE bot_id=? FOR UPDATE", Long.class, bot.id());
        if (update.id() < offset) return;
        if (update.privateChat() && update.text() != null) {
            List<UUID> ids = jdbc.query("SELECT conversation_id FROM onno_crm_telegram_chat WHERE bot_id=? AND chat_id=?",
                    (rs, row) -> rs.getObject(1, UUID.class), bot.id(), update.chatId());
            Conversation conversation;
            if (ids.isEmpty()) {
                String name=cut(update.name().isBlank() ? "Telegram " + update.senderId() : update.name(),200);
                UUID customer=contacts.resolveIncoming(new CrmCustomerBinding.IncomingContact(Channel.TELEGRAM,
                        Long.toString(bot.id()),Long.toString(update.chatId()),name,null,null,null,"Telegram @"+bot.username()));
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
            if (conversation != null) {
                UUID canonical = contacts.canonical(conversation.getCustomer());
                conversation.setCustomer(canonical);
                contacts.link(canonical,Channel.TELEGRAM,Long.toString(bot.id()),Long.toString(update.chatId()),update.username().isBlank()?update.name():"@"+update.username(),true);
                ConversationMessage message = new ConversationMessage();
                message.setConversation(Ref.of(Conversation.class, conversation.getId()));
                message.setChannel(Channel.TELEGRAM);
                message.setKind(MessageKind.CUSTOMER_MESSAGE);
                message.setDirection(MessageDirection.INBOUND);
                message.setAuthorName(cut(update.name().isBlank() ? "Telegram user" : update.name(), 200));
                message.setBody(cut(update.text(), 8000));
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
        }
        jdbc.update("UPDATE onno_crm_telegram_connection SET next_update=? WHERE bot_id=?", update.id() + 1, bot.id());
    }

    void deliver() {
        credentialLock.readLock().lock();
        try { deliverCurrent(); } finally { credentialLock.readLock().unlock(); }
    }

    private void deliverCurrent() {
        if (!ready || System.currentTimeMillis() < nextSendAt || !inboxes.findActiveById(inboxId()).map(Inbox::isActive).orElse(false)) return;
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
                    if (currentChat != chatId || message.getDeliveryStatus() != DeliveryStatus.QUEUED
                            || message.getBody() == null || message.getBody().isBlank() || message.getBody().length() > 4096) {
                        throw new IllegalStateException("Queued message no longer matches its destination or content limits");
                    }
                    long externalId = client.send(chatId, message.getBody());
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
        try { workers.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
