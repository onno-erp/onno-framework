package su.onno.crm.channels.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;

/**
 * Gmail adapter for any number of mailboxes: the legacy server-level mailbox plus one per shared or
 * personal connection account. Gmail account/thread IDs, not email display names, own routes.
 * Imports inbox mail with its files, and sent replies on threads the CRM already knows.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(GmailBridge.class)
@Component
@ConditionalOnProperty(name="onno.crm.channels.gmail.enabled",havingValue="true")
public class GmailBridge implements CrmMessageTransport,CrmChannelConnection {
    private final CrmConnectionAccounts connectionAccounts;
    private final java.util.concurrent.ConcurrentMap<UUID,String> accountFailures=new java.util.concurrent.ConcurrentHashMap<>();
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GmailBridge.class);
    private final GmailClient client;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final InboxRepository inboxes;
    private final ConversationRepository conversations;private final ConversationMessageRepository messages;
    private final ContactIdentityRepository identities;private final CrmContactService contacts;private final CrmWorkspaceService workspace;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"crm-gmail");t.setDaemon(true);return t;});
    private volatile boolean ready;private volatile String failure="";
    /** Mailboxes Gmail is rate-limiting, with the time it allows the next request. */
    private final ConcurrentMap<String,Instant> limitedUntil=new ConcurrentHashMap<>();
    /** Failed imports per mailbox and Gmail message id, to stop one bad email from holding up its mailbox. */
    private final ConcurrentMap<String,Integer> failedImports=new ConcurrentHashMap<>();
    /** Sync rounds an email may fail for its own reasons before it is skipped. */
    static final int IMPORT_ATTEMPTS=3;
    private final su.onno.crm.service.CrmConversationStatuses statuses;
    private final su.onno.crm.service.CrmAttachments attachments;
    public su.onno.crm.service.CrmChannelDefinition definition() { return new su.onno.crm.service.CrmChannelDefinition("EMAIL","Email","/crm/channels/gmail.svg"); }
    public GmailBridge(GmailClient client,JdbcTemplate jdbc,PlatformTransactionManager transactions,InboxRepository inboxes,
        ConversationRepository conversations,ConversationMessageRepository messages,CrmContactService contacts,CrmWorkspaceService workspace,ContactIdentityRepository identities, su.onno.crm.service.CrmConversationStatuses statuses,
        su.onno.crm.service.CrmAttachments attachments,CrmConnectionAccounts connectionAccounts) {
        this.statuses=statuses;this.attachments=attachments;
        this.connectionAccounts=connectionAccounts;
        this.client=client;this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.inboxes=inboxes;
        this.conversations=conversations;this.messages=messages;this.contacts=contacts;this.workspace=workspace;this.identities=identities;
    }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_gmail_account (account VARCHAR(320) PRIMARY KEY,inbox_id UUID NOT NULL,history_id VARCHAR(40),active BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_gmail_thread (account VARCHAR(320),thread_id VARCHAR(100),conversation_id UUID NOT NULL,reply_to VARCHAR(320),subject VARCHAR(998),reference_id VARCHAR(998),PRIMARY KEY(account,thread_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_gmail_seen (account VARCHAR(320),external_id VARCHAR(100),PRIMARY KEY(account,external_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_gmail_outbox (message_id UUID PRIMARY KEY,account VARCHAR(320),thread_id VARCHAR(100),state VARCHAR(20))");
        tx.executeWithoutResult(s->{for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_gmail_outbox WHERE state='SENDING'",(rs,n)->rs.getObject(1,UUID.class)))finish(id,false,null);});
        jdbc.execute("ALTER TABLE onno_crm_gmail_thread ADD COLUMN IF NOT EXISTS last_received TIMESTAMP");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_gmail_grouping (source_id UUID PRIMARY KEY,target_id UUID NOT NULL)");
        jdbc.execute("ALTER TABLE onno_crm_gmail_account ADD COLUMN IF NOT EXISTS connection_id UUID UNIQUE");
        createBackfillMarkers();
        groupExistingConversations();
        ready=true;worker.scheduleWithFixedDelay(this::tick,2,15,TimeUnit.SECONDS);
    }
    /**
     * Mailboxes listed here no longer need the one-time file backfill. Mailboxes connected before
     * file import shipped have no row and are backfilled once; new mailboxes get a row on connect,
     * because their mail is imported with files from the start.
     */
    private void createBackfillMarkers() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_gmail_files_backfill_v2 (account VARCHAR(320) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_gmail_html_backfill (account VARCHAR(320) PRIMARY KEY)");
    }
    private record Account(String email,UUID inbox,String history,boolean active,UUID connectionId) {}
    private List<Account> accounts() { return jdbc.query("SELECT account,inbox_id,history_id,active,connection_id FROM onno_crm_gmail_account",(rs,n)->new Account(rs.getString(1),rs.getObject(2,UUID.class),rs.getString(3),rs.getBoolean(4),rs.getObject(5,UUID.class))); }
    private Account account() { return accounts().stream().filter(a->a.connectionId==null).findFirst().orElse(null); }
    private Account account(UUID id) { return accounts().stream().filter(a->id.equals(a.connectionId)).findFirst().orElse(null); }
    private Account account(Conversation c) { return accounts().stream().filter(a->c.getInbox()!=null && a.inbox.equals(c.getInbox().id())).findFirst().orElse(null); }
    private GmailClient client(Account a) { return a.connectionId==null?client:client.forAccount(a.connectionId); }
    public synchronized void connect(JsonNode token) { connect(token,null); }
    public synchronized void connect(JsonNode token,UUID connectionId) {
        String email=client.profile(token).path("emailAddress").asText();if(email.isBlank())throw new IllegalArgumentException("Google did not identify a mailbox");
        var old=connectionId==null?account():account(connectionId);
        if(accounts().stream().anyMatch(a->a.email.equalsIgnoreCase(email) && !Objects.equals(a.connectionId,connectionId)))throw new IllegalArgumentException("This mailbox is already connected to another account");
        if(old!=null&&!old.email.equalsIgnoreCase(email))throw new IllegalArgumentException("Reconnect the original Gmail account; switching mailboxes requires a separate inbox");
        // Persist credentials before activating the account. A failed DB commit is retryable by reauthorizing.
        (connectionId==null?client:client.forAccount(connectionId)).saveTokens(token);
        createBackfillMarkers();
        tx.executeWithoutResult(s->{workspace.lock();if(old==null){var inbox=new Inbox();inbox.setDescription("Gmail · "+email);inbox.setAddress(email);inbox.setChannel(Channel.EMAIL);inboxes.save(inbox);jdbc.update("INSERT INTO onno_crm_gmail_account (account,inbox_id,history_id,active,connection_id) VALUES (?,?,NULL,TRUE,?)",email,inbox.getId(),connectionId);
                // A new mailbox imports files from its first sync; it must never be backfilled.
                if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_files_backfill_v2 WHERE account=?",Integer.class,email)==0)
                    jdbc.update("INSERT INTO onno_crm_gmail_files_backfill_v2 VALUES (?)",email);
                if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_html_backfill WHERE account=?",Integer.class,email)==0)
                    jdbc.update("INSERT INTO onno_crm_gmail_html_backfill VALUES (?)",email);
                if(connectionId!=null)connectionAccounts.bindInbox(connectionId,inbox.getId());}
            else{jdbc.update("UPDATE onno_crm_gmail_account SET active=TRUE WHERE account=?",email);inboxes.findActiveById(old.inbox).ifPresent(i->{i.setActive(true);inboxes.save(i);});}});if(connectionId==null)failure="";else accountFailures.remove(connectionId);
    }
    public String key(){return "gmail";}
    public View view(){var a=ready?account():null;String state=!client.configured()?"UNAVAILABLE":a==null?"DISCONNECTED":!failure.isBlank()?"ERROR":a.active?"CONNECTED":"PAUSED";
        return new View("gmail","Gmail","EMAIL",state,a==null?"":a.email,"Sync recent inbox mail and reply from this CRM. Files are imported with previews and downloads.",!client.configured()?List.of():a==null?List.of("connect"):List.of("connect","check",a.active?"pause":"resume"));}
    public synchronized void command(String action,String credential){var a=account();if(a==null)throw new IllegalArgumentException("Connect a Google account first");
        if(action.equals("check")){client.get("profile");failure="";return;}
        if(!action.equals("pause")&&!action.equals("resume"))throw new IllegalArgumentException("Unsupported Gmail action");
        boolean active=action.equals("resume");if(active)client.get("profile");
        tx.executeWithoutResult(s->{workspace.lock();jdbc.update("UPDATE onno_crm_gmail_account SET active=? WHERE account=?",active,a.email);inboxes.findActiveById(a.inbox).ifPresent(i->{i.setActive(active);inboxes.save(i);});});failure="";
    }
    public View view(CrmConnectionAccounts.Account record) {
        var a=ready?account(record.id()):null;var scopedClient=client.forAccount(record.id());
        String state=!client.configured()?"UNAVAILABLE":a==null || !scopedClient.authorized()?"DISCONNECTED":accountFailures.containsKey(record.id())?"ERROR":a.active?"CONNECTED":"PAUSED";
        var actions=!client.configured()?List.<String>of():state.equals("DISCONNECTED")?List.of("connect"):List.of("connect","check",a.active?"pause":"resume","disconnect");
        return new View(record.id().toString(),"Gmail","EMAIL",state,a==null?"":a.email,"Recent inbox mail with files, and replies sent from Gmail on known threads.",actions);
    }
    public synchronized void command(CrmConnectionAccounts.Account record,String action) {
        var a=account(record.id());if(a==null)throw new IllegalArgumentException("Connect this account first");
        if(action.equals("check")){client(a).get("profile");accountFailures.remove(record.id());return;}
        if(!Set.of("pause","resume","disconnect").contains(action))throw new IllegalArgumentException("Unsupported account action");
        boolean active=action.equals("resume");if(active)client(a).get("profile");
        tx.executeWithoutResult(s->{workspace.lock();jdbc.update("UPDATE onno_crm_gmail_account SET active=? WHERE connection_id=?",active,record.id());inboxes.findActiveById(a.inbox).ifPresent(i->{i.setActive(active);inboxes.save(i);});});
        if(action.equals("disconnect")) {
            client(a).disconnect();
            tx.executeWithoutResult(status-> { for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_gmail_outbox WHERE account=? AND state='QUEUED'",(rs,n)->rs.getObject(1,UUID.class),a.email))finish(id,false,null); });
        }
        accountFailures.remove(record.id());
    }
    public boolean supports(Conversation conversation) { return Channel.EMAIL.equals(conversation.getChannel()); }

    public Connection connection(Conversation c){if(!ready||!Channel.EMAIL.equals(c.getChannel())||c.getInbox()==null)return new Connection(false,"Gmail is not connected",8000);var a=account(c);
        boolean mapped=a!=null&&a.inbox.equals(c.getInbox().id())&&a.active&&client(a).authorized()&&jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_thread WHERE account=? AND conversation_id=?",Integer.class,a.email,c.getId())>0;
        var connection=new Connection(mapped,mapped?"Gmail":"Gmail is not connected or is paused",8000)
            .withAttachments(attachments.limit(),attachments.unavailableReason());
        if(!mapped)return connection;
        String replyTo=jdbc.queryForObject("SELECT reply_to FROM onno_crm_gmail_thread WHERE account=? AND conversation_id=? ORDER BY last_received DESC NULLS LAST,thread_id DESC FETCH FIRST 1 ROW ONLY",String.class,a.email,c.getId());
        String recipient=emailAddress(replyTo);
        var addresses=new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if(recipient!=null)addresses.add(recipient);
        if(c.getCustomer()!=null)for(var identity:identities.findByCustomerAndDeletionMarkFalse(contacts.canonical(c.getCustomer()))) {
            if(!Channel.EMAIL.equals(identity.getChannel()))continue;
            String address=emailAddress(identity.getAddress());if(address!=null)addresses.add(address);
        }
        return connection.withRecipients(recipient,List.copyOf(addresses));}
    private static String emailAddress(String value) {
        if(value==null || value.contains("\r") || value.contains("\n"))return null;
        try {var parsed=jakarta.mail.internet.InternetAddress.parse(value,true);
            if(parsed.length!=1 || parsed[0].isGroup())return null;
            parsed[0].validate();return parsed[0].getAddress();
        }catch(jakarta.mail.internet.AddressException ignored){return null;}
    }
    public void enqueue(Conversation c,ConversationMessage message){if(!connection(c).connected())throw new IllegalArgumentException("Gmail is not connected");var a=account(c);
        String thread=jdbc.queryForObject("SELECT thread_id FROM onno_crm_gmail_thread WHERE account=? AND conversation_id=? ORDER BY last_received DESC NULLS LAST,thread_id DESC FETCH FIRST 1 ROW ONLY",String.class,a.email,c.getId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_outbox WHERE message_id=?",Integer.class,message.getId())==0)jdbc.update("INSERT INTO onno_crm_gmail_outbox VALUES (?,?,?,'QUEUED')",message.getId(),a.email,thread);
        else if(jdbc.update("UPDATE onno_crm_gmail_outbox SET state='QUEUED' WHERE message_id=? AND state='FAILED'",message.getId())!=1)throw new IllegalArgumentException("Reply is already queued or sent");}
    synchronized void tick(){if(!ready)return;for(var a:accounts()) {
        if(!a.active || !client(a).authorized())continue;
        var limited=limitedUntil.get(a.email);if(limited!=null && Instant.now().isBefore(limited))continue; // asking early only extends Gmail's limit
        try {deliver(a);sync(a);backfillFiles(a);backfillHtml(a);limitedUntil.remove(a.email);if(a.connectionId==null)failure="";else accountFailures.remove(a.connectionId);}
        catch(Exception e){String message="Gmail synchronization failed; check connection or reconnect";if(a.connectionId==null)failure=message;else accountFailures.put(a.connectionId,message);
            if(e instanceof GmailClient.ApiFailure f && f.retryAt!=null)limitedUntil.put(a.email,f.retryAt);
            log.warn("Gmail synchronization deferred for {} ({})",a.email,describe(e));}
    }}
    private void sync(Account a) throws Exception {
        if(a.history==null){String checkpoint=client(a).get("profile").path("historyId").asText();var page=client(a).get("messages?labelIds=INBOX&maxResults=50");
            for(var item:page.path("messages"))importMessage(a,item.path("id").asText());jdbc.update("UPDATE onno_crm_gmail_account SET history_id=? WHERE account=?",checkpoint,a.email);return;}
        String next="",checkpoint=a.history;
        do{JsonNode page;try{page=client(a).get("history?historyTypes=messageAdded&startHistoryId="+GmailClient.encode(a.history)+"&maxResults=100"+(next.isBlank()?"":"&pageToken="+GmailClient.encode(next)));}
            catch(GmailClient.ApiFailure e){if(e.status==404){jdbc.update("UPDATE onno_crm_gmail_account SET history_id=NULL WHERE account=?",a.email);return;}throw e;}
            for(var history:page.path("history"))for(var added:history.path("messagesAdded")){var m=added.path("message");boolean inbox=false;for(var label:m.path("labelIds"))if(label.asText().equals("INBOX") || label.asText().equals("SENT"))inbox=true;if(inbox)importMessage(a,m.path("id").asText());}
            checkpoint=page.path("historyId").asText(checkpoint);next=page.path("nextPageToken").asText();
        }while(!next.isBlank());jdbc.update("UPDATE onno_crm_gmail_account SET history_id=? WHERE account=?",checkpoint,a.email);
    }
    /**
     * Imports one email. An email that fails for its own reasons (Gmail's answer cannot be read, the MIME
     * cannot be parsed) would otherwise fail every round and hold up the rest of its mailbox, so after
     * {@link #IMPORT_ATTEMPTS} rounds it is logged and skipped. Mailbox-wide failures (network, access,
     * rate limits) are never counted against an email.
     */
    private void importMessage(Account a,String id) throws Exception {
        String key=a.email+"\n"+id;
        try {ingest(a,id);failedImports.remove(key);}
        catch(Exception e){
            if(e instanceof GmailClient.ApiFailure f && !f.unreadable && (f.status==0 || f.status==401 || f.status==403 || f.status==429 || f.status>=500))throw e;
            int attempts=failedImports.merge(key,1,Integer::sum);if(attempts<IMPORT_ATTEMPTS)throw e;
            failedImports.remove(key);
            jdbc.update("INSERT INTO onno_crm_gmail_seen (account,external_id) SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM onno_crm_gmail_seen WHERE account=? AND external_id=?)",a.email,id,a.email,id);
            log.warn("Gmail message {} in {} skipped after {} failed imports ({})",id,a.email,attempts,describe(e));
        }
    }
    private static String describe(Exception e) { return e instanceof GmailClient.ApiFailure?e.getMessage():e.getClass().getSimpleName(); }
    private void ingest(Account a,String id) throws Exception {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_seen WHERE account=? AND external_id=?",Integer.class,a.email,id)>0)return;
        JsonNode raw;try{raw=client(a).get("messages/"+GmailClient.encode(id)+"?format=raw");}catch(GmailClient.ApiFailure e){if(e.status==404)return;throw e;}
        var mail=GmailMail.read(raw.path("raw").asText());String thread=raw.path("threadId").asText();
        var receivedAt=LocalDateTime.ofInstant(raw.hasNonNull("internalDate")?Instant.ofEpochMilli(raw.path("internalDate").asLong()):mail.sentAt(),ZoneId.systemDefault());
        tx.executeWithoutResult(s->{workspace.lock();if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_seen WHERE account=? AND external_id=?",Integer.class,a.email,id)>0)return;
            boolean outbound=false;
            for(var label:raw.path("labelIds"))if(label.asText().equals("SENT"))outbound=true;
            var ids=jdbc.query("SELECT conversation_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=?",(rs,n)->rs.getObject(1,UUID.class),a.email,thread);Conversation c;
            if(outbound && ids.isEmpty())return; // Only import replies to known CRM threads.
            if(ids.isEmpty()){
                UUID customer=contacts.resolveIncoming(new CrmCustomerBinding.IncomingContact(Channel.EMAIL,
                        a.inbox.toString(),mail.sender(),cut(mail.name(),200),mail.sender(),null,null,"Gmail "+a.email));
                c=conversationForCustomer(a,customer);
                if(c==null){c=new Conversation();c.setLastMessageAt(receivedAt);c.setCustomer(customer);c.setInbox(Ref.of(Inbox.class,a.inbox));c.setChannel(Channel.EMAIL);c.setSubject(cut(mail.subject(),240));c.setDescription(c.getSubject());conversations.save(c);}
                jdbc.update("INSERT INTO onno_crm_gmail_thread (account,thread_id,conversation_id,reply_to,subject,reference_id,last_received) VALUES (?,?,?,?,?,?,?)",a.email,thread,c.getId(),cut(mail.replyTo(),320),cut(mail.subject(),998),cut(mail.messageId(),998),receivedAt);
            }else c=conversations.findActiveById(ids.getFirst()).orElse(null);
            if(c!=null && outbound) {
                var history=messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,c.getId()));
                if(history.stream().noneMatch(m->Objects.equals(m.getExternalMessageId(),"gmail:"+id))) {
                    var reply=new ConversationMessage();reply.setConversation(Ref.of(Conversation.class,c.getId()));reply.setChannel(Channel.EMAIL);
                    reply.setKind(MessageKind.AGENT_REPLY);reply.setDirection(MessageDirection.OUTBOUND);reply.setDeliveryStatus(DeliveryStatus.SENT);
                    var files=importFiles(mail);
                    reply.setAuthorName(cut(mail.name(),200));reply.setBody(cut(files.withNotes(mail.text()),8000));reply.setBodyHtml(files.html(mail));reply.setDescription(cut(mail.text(),100));
                    reply.setSentAt(receivedAt);reply.setExternalMessageId("gmail:"+id);reply.setAttachments(files.urls());messages.save(reply);
                    su.onno.crm.channels.ExternalReplyReadState.answered(c,reply,history,su.onno.crm.channels.ExternalReplyReadState.NO_TIE_BREAK);
                    if(c.getLastMessageAt()==null || !reply.getSentAt().isBefore(c.getLastMessageAt())){c.setLastMessageAt(reply.getSentAt());c.setLastMessagePreview(cut(mail.text(),180));}
                    conversations.save(c);
                }
            } else if(c!=null){UUID canonical=contacts.canonical(c.getCustomer());c.setCustomer(canonical);UUID senderIdentity=UUID.nameUUIDFromBytes(("EMAIL\n"+a.inbox+"\n"+mail.sender().toLowerCase(Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var linked=identities.findActiveById(senderIdentity).orElse(null);
                if(linked==null||contacts.canonical(linked.getCustomer()).equals(canonical))contacts.link(canonical,Channel.EMAIL,a.inbox.toString(),mail.sender().toLowerCase(Locale.ROOT),mail.sender(),false);
                var message=new ConversationMessage();message.setConversation(Ref.of(Conversation.class,c.getId()));message.setChannel(Channel.EMAIL);message.setKind(MessageKind.CUSTOMER_MESSAGE);message.setDirection(MessageDirection.INBOUND);message.setAuthorName(cut(mail.name(),200));
                var files=importFiles(mail);
                message.setBody(cut(files.withNotes(mail.text()),8000));message.setBodyHtml(files.html(mail));message.setDescription(cut(mail.text(),100));message.setSentAt(receivedAt);message.setDeliveryStatus(DeliveryStatus.RECEIVED);message.setExternalMessageId(cut("gmail:"+a.email+":"+id,240));message.setAttachments(files.urls());messages.save(message);
                if(c.getLastMessageAt()==null||!message.getSentAt().isBefore(c.getLastMessageAt())){c.setLastMessageAt(message.getSentAt());c.setLastMessagePreview(cut(mail.text(),180));}
                jdbc.update("UPDATE onno_crm_gmail_thread SET reply_to=?,subject=?,reference_id=?,last_received=? WHERE account=? AND thread_id=? AND (last_received IS NULL OR last_received<=?)",cut(mail.replyTo(),320),cut(mail.subject(),998),cut(mail.messageId(),998),message.getSentAt(),a.email,thread,message.getSentAt());
                if(su.onno.crm.channels.ExternalReplyReadState.unread(message,messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,c.getId())),su.onno.crm.channels.ExternalReplyReadState.NO_TIE_BREAK)){c.setUnreadCount(c.getUnreadCount()+1);c.setStatus(statuses.incoming());}conversations.save(c);}
            jdbc.update("INSERT INTO onno_crm_gmail_seen VALUES (?,?)",a.email,id);
        });
    }
    private void backfillFiles(Account a) throws Exception {
        if(attachments.limit()==0 || jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_files_backfill_v2 WHERE account=?",Integer.class,a.email)>0)return;
        var page=client(a).get("messages?labelIds=INBOX&maxResults=50");
        for(var item:page.path("messages")) {
            String id=item.path("id").asText();
            JsonNode raw;try{raw=client(a).get("messages/"+GmailClient.encode(id)+"?format=raw");}catch(GmailClient.ApiFailure e){if(e.status==404 || e.unreadable)continue;throw e;}
            var ids=jdbc.query("SELECT conversation_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=?",(rs,n)->rs.getObject(1,UUID.class),a.email,raw.path("threadId").asText());
            if(ids.isEmpty())continue;
            var existing=messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,ids.getFirst())).stream()
                    .filter(m->Objects.equals(m.getExternalMessageId(),"gmail:"+a.email+":"+id)).findFirst();
            if(existing.isEmpty())continue;
            var mail=GmailMail.read(raw.path("raw").asText());
            if(mail.files().isEmpty())continue;
            tx.executeWithoutResult(status->{workspace.lock();var message=messages.findActiveById(existing.get().getId()).orElse(null);
                if(message!=null){
                    var files=importFiles(mail);
                    message.setAttachments(files.urls());message.setBody(cut(files.withNotes(mail.text()),8000));messages.save(message);
                }
            });
        }
        jdbc.update("INSERT INTO onno_crm_gmail_files_backfill_v2 VALUES (?)",a.email);
    }
    /**
     * Once per mailbox, give recent emails imported before HTML bodies were kept their sanitized
     * HTML. Inline {@code cid:} images are matched to the message's already-stored files by name,
     * so nothing is downloaded or stored twice.
     */
    private void backfillHtml(Account a) throws Exception {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_html_backfill WHERE account=?",Integer.class,a.email)>0)return;
        var page=client(a).get("messages?labelIds=INBOX&maxResults=50");
        for(var item:page.path("messages")) {
            String id=item.path("id").asText();
            JsonNode raw;try{raw=client(a).get("messages/"+GmailClient.encode(id)+"?format=raw");}catch(GmailClient.ApiFailure e){if(e.status==404 || e.unreadable)continue;throw e;}
            var ids=jdbc.query("SELECT conversation_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=?",(rs,n)->rs.getObject(1,UUID.class),a.email,raw.path("threadId").asText());
            if(ids.isEmpty())continue;
            var existing=messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,ids.getFirst())).stream()
                    .filter(m->Objects.equals(m.getExternalMessageId(),"gmail:"+a.email+":"+id)&&m.getBodyHtml()==null).findFirst();
            if(existing.isEmpty())continue;
            var mail=GmailMail.read(raw.path("raw").asText());
            if(mail.html()==null)continue;
            tx.executeWithoutResult(status->{workspace.lock();var message=messages.findActiveById(existing.get().getId()).orElse(null);
                if(message!=null){
                    var byName=new HashMap<String,String>();
                    for(var file:attachments.of(message))byName.putIfAbsent(file.filename(),file.url());
                    var byContentId=new HashMap<String,String>();
                    for(var file:mail.files())if(file.contentId()!=null&&byName.containsKey(file.filename()))byContentId.put(file.contentId(),byName.get(file.filename()));
                    message.setBodyHtml(EmailHtml.resolveContentIds(mail.html(),byContentId));messages.save(message);
                }
            });
        }
        jdbc.update("INSERT INTO onno_crm_gmail_html_backfill VALUES (?)",a.email);
    }
    /** Stored attachment value (or null) plus placeholder lines for files the media policy refused. */
    record ImportedFiles(String urls, List<String> notes, Map<String, String> urlsByContentId) {
        ImportedFiles(String urls, List<String> notes) { this(urls, notes, Map.of()); }
        String withNotes(String text) {
            return notes.isEmpty() ? text : text + "\n" + String.join("\n", notes);
        }
        /** The mail's sanitized HTML with inline {@code cid:} images pointed at the stored files. */
        String html(GmailMail.Incoming mail) {
            return EmailHtml.resolveContentIds(mail.html(), urlsByContentId);
        }
    }

    /**
     * Store a mail's files. A file the media policy refuses (empty, too large, type not allowed) is
     * skipped with a placeholder note, so one refused file never rolls back the whole mail — which
     * would leave it unseen and retried forever.
     */
    ImportedFiles importFiles(GmailMail.Incoming mail) {
        if (attachments.limit() == 0 || mail.files().isEmpty()) return new ImportedFiles(null, List.of());
        var urls = new ArrayList<String>();
        var notes = new ArrayList<String>();
        var byContentId = new HashMap<String, String>();
        for (var file : mail.files()) {
            try {
                String url = attachments.ingest(file.filename(), file.contentType(), file.content());
                urls.add(url);
                if (file.contentId() != null) byContentId.put(file.contentId(), url);
            } catch (CrmAttachments.RejectedAttachment rejected) {
                notes.add(switch (rejected.reason()) {
                    case EMPTY -> "[Empty file skipped]";
                    case TOO_LARGE -> "[File skipped: exceeds the media size limit]";
                    case TYPE_NOT_ALLOWED -> "[File skipped: file type is not allowed]";
                });
            }
        }
        return new ImportedFiles(urls.isEmpty() ? null : attachments.store(urls), List.copyOf(notes), Map.copyOf(byContentId));
    }
    private void deliver(Account a){for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_gmail_outbox WHERE account=? AND state='QUEUED'",(rs,n)->rs.getObject(1,UUID.class),a.email)){
        if(jdbc.update("UPDATE onno_crm_gmail_outbox SET state='SENDING' WHERE message_id=? AND state='QUEUED'",id)!=1)continue;
        try{var message=messages.findActiveById(id).orElseThrow();var c=conversations.findActiveById(message.getConversation().id()).orElseThrow();if(!connection(c).connected())throw new IllegalStateException();
            var row=jdbc.queryForMap("SELECT thread_id,reply_to,subject,reference_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=(SELECT thread_id FROM onno_crm_gmail_outbox WHERE message_id=?)",a.email,id);
            var files=attachments.of(message).stream().map(file->new GmailMail.Outgoing(
                file.filename(),file.contentType(),attachments.bytes(file))).toList();
            // Base64 inflates by a third and Gmail refuses a message over 35 MB, so an oversized
            // batch fails here with a reason rather than at the API with an opaque rejection.
            long total=files.stream().mapToLong(f->f.content().length).sum();
            if(total>25L*1024*1024)throw new IllegalStateException("Attachments exceed what Gmail accepts on one message");
            String originalSubject=row.get("subject").toString();
            String subject=message.getSubject();
            String recipient=message.getRecipient()==null?row.get("reply_to").toString():message.getRecipient();
            boolean newThread=(subject!=null&&!subject.equals(originalSubject)) || !Objects.equals(emailAddress(recipient),emailAddress(row.get("reply_to").toString()));
            String outgoingSubject=subject==null?originalSubject:subject;
            String body=message.getBody()==null?"":message.getBody();
            String raw=newThread
                ? GmailMail.compose(a.email,recipient,outgoingSubject,"",body,files,id)
                : GmailMail.reply(a.email,recipient,originalSubject,row.get("reference_id").toString(),body,files,id);
            var sent=client(a).send(raw,newThread?null:row.get("thread_id").toString());
            tx.executeWithoutResult(s->{
                if(newThread&&!sent.path("threadId").asText().isBlank()) {
                    String thread=sent.path("threadId").asText();
                    String reference="<onno-"+id+"@"+a.email.substring(a.email.indexOf('@')+1)+">";
                    if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_thread WHERE account=? AND thread_id=?",Integer.class,a.email,thread)==0)
                        jdbc.update("INSERT INTO onno_crm_gmail_thread (account,thread_id,conversation_id,reply_to,subject,reference_id,last_received) VALUES (?,?,?,?,?,?,?)",a.email,thread,c.getId(),recipient,outgoingSubject,reference,message.getSentAt());
                    c.setSubject(outgoingSubject.isBlank()?"(No subject)":cut(outgoingSubject,240));conversations.save(c);
                }
                finish(id,true,sent.path("id").asText());
            });
        }catch(Exception e){tx.executeWithoutResult(s->finish(id,false,null));}
    }}
    private Conversation conversationForCustomer(Account a,UUID customer) {
        UUID canonical=contacts.canonical(customer);
        for(UUID id:jdbc.query("SELECT DISTINCT conversation_id FROM onno_crm_gmail_thread WHERE account=?",(rs,n)->rs.getObject(1,UUID.class),a.email)) {
            var c=conversations.findActiveById(id).orElse(null);
            if(c!=null&&contacts.canonical(c.getCustomer()).equals(canonical))return c;
        }
        return null;
    }
    /** Upgrade thread-per-chat imports without losing messages, notes or original thread routes. */
    private void groupExistingConversations() {
        var a=account();if(a==null)return;
        tx.executeWithoutResult(s->{workspace.lock();Map<UUID,Conversation> groups=new LinkedHashMap<>();
            var ids=jdbc.query("SELECT DISTINCT conversation_id FROM onno_crm_gmail_thread WHERE account=? ORDER BY conversation_id",(rs,n)->rs.getObject(1,UUID.class),a.email);
            for(UUID id:ids){var source=conversations.findActiveById(id).orElse(null);if(source==null)continue;
                var history=messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,id));
                LocalDateTime latest=history.isEmpty()?source.getLastMessageAt():history.getLast().getSentAt();
                jdbc.update("UPDATE onno_crm_gmail_thread SET last_received=? WHERE conversation_id=? AND last_received IS NULL",latest,id);
                UUID customer=contacts.canonical(source.getCustomer());var target=groups.get(customer);
                if(target==null){groups.put(customer,source);continue;}
                for(var message:history){message.setConversation(Ref.of(Conversation.class,target.getId()));messages.save(message);}
                if(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)='onno_comments' AND TABLE_SCHEMA=CURRENT_SCHEMA()",Integer.class)>0)
                    jdbc.update("UPDATE onno_comments SET _entity_id=? WHERE _entity_type='catalogs' AND _entity_name='crm_conversations' AND _entity_id=?",target.getId(),id);
                if(target.getAssignee()==null)target.setAssignee(source.getAssignee());
                if(target.getPriority()==null)target.setPriority(source.getPriority());
                target.setUnreadCount(target.getUnreadCount()+source.getUnreadCount());
                if(source.getLastMessageAt().isAfter(target.getLastMessageAt())){target.setLastMessageAt(source.getLastMessageAt());target.setLastMessagePreview(source.getLastMessagePreview());target.setSubject(source.getSubject());target.setStatus(source.getStatus());}
                conversations.save(target);
                jdbc.update("UPDATE onno_crm_gmail_thread SET conversation_id=? WHERE account=? AND conversation_id=?",target.getId(),a.email,id);
                jdbc.update("INSERT INTO onno_crm_gmail_grouping VALUES (?,?)",id,target.getId());
                source.setDeletionMark(true);conversations.save(source);
            }
        });
    }
    private void finish(UUID id,boolean sent,String external){messages.findActiveById(id).ifPresent(m->{m.setDeliveryStatus(sent?DeliveryStatus.SENT:DeliveryStatus.FAILED);if(external!=null)m.setExternalMessageId("gmail:"+external);messages.save(m);});jdbc.update("UPDATE onno_crm_gmail_outbox SET state=? WHERE message_id=?",sent?"SENT":"FAILED",id);}
    private static String cut(String text,int max){return text==null?"":text.substring(0,Math.min(max,text.length()));}
    @PreDestroy public void stop(){ready=false;worker.shutdownNow();}
}
