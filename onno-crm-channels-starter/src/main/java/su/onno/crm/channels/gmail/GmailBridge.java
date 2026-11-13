package su.onno.crm.channels.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.annotation.PreDestroy;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import su.onno.crm.channels.CrmChannelsProperties;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;

/**
 * Gmail adapter for any number of mailboxes: the legacy server-level mailbox plus one per shared or
 * personal connection account. Gmail account/thread IDs, not email display names, own routes.
 * Imports inbox mail with its files, and sent replies on threads the CRM already knows.
 *
 * <p>A reply may be sent from another mailbox than the conversation's own ({@code Connection.senders}):
 * the deployment's shared mailboxes, the conversation's own, and the replying user's own personal
 * mailbox, never someone else's. It starts a thread in that mailbox, recorded for this conversation,
 * so the contact's answer syncs back into it.
 *
 * <p>One message that cannot be fetched or parsed never blocks a mailbox: it is retried
 * {@value #MAX_ATTEMPTS} times, then recorded as seen and skipped. When Gmail asks us to slow down
 * (429, or 403 with a rate reason) only that mailbox pauses, until Gmail's Retry-After or an
 * exponential backoff.
 *
 * <p>With {@code onno.crm.channels.gmail.history-import.enabled} each mailbox then imports its older
 * mail once, a few threads per round, as quiet history ({@link CrmHistoryImport}): text only, its
 * files left in Gmail and fetched when someone opens them ({@link GmailMediaFiles}).
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
    private final su.onno.crm.service.CrmConversationStatuses statuses;
    private final su.onno.crm.service.CrmAttachments attachments;
    private final CrmChannelsProperties.Gmail settings;
    /** Reloads open inboxes once per history batch; absent in tests and headless setups. */
    private volatile su.onno.ui.UiEventPublisher uiEvents;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void setUiEvents(su.onno.ui.UiEventPublisher events) { this.uiEvents=events; }
    /** How a message comes in: as it arrived, from a scan of recent mail, or as old history. */
    enum Mode { LIVE, SCAN, HISTORY }
    /** Media keys of files kept in Gmail: {@code gmail/<id>/<name>}. */
    static final String FILE_PREFIX="gmail/";
    /** Fetch/import attempts at one message before it is recorded as seen and skipped. */
    static final int MAX_ATTEMPTS=3;
    private static final Duration MAX_BACKOFF=Duration.ofHours(1);
    /** Messages one read-sync call clears in Gmail; {@code batchModify} takes at most 1000 ids. */
    private static final int READ_SYNC_LIMIT=1000;
    static final int SEARCH_PAGE=20;
    private record Backoff(Instant until,int strikes) {}
    /** Rate-limit pauses by lower-cased mailbox address. Other mailboxes keep syncing. */
    private final ConcurrentMap<String,Backoff> backoffs=new ConcurrentHashMap<>();
    /** Mirrors CRM read state to Gmail off the request thread, so a slow Gmail never holds a request. */
    private final ExecutorService readSync=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"crm-gmail-read");t.setDaemon(true);return t;});
    public su.onno.crm.service.CrmChannelDefinition definition() { return new su.onno.crm.service.CrmChannelDefinition("EMAIL","Email","/crm/channels/gmail.svg"); }
    public GmailBridge(GmailClient client,JdbcTemplate jdbc,PlatformTransactionManager transactions,InboxRepository inboxes,
        ConversationRepository conversations,ConversationMessageRepository messages,CrmContactService contacts,CrmWorkspaceService workspace,ContactIdentityRepository identities, su.onno.crm.service.CrmConversationStatuses statuses,
        su.onno.crm.service.CrmAttachments attachments,CrmConnectionAccounts connectionAccounts) {
        this(client,jdbc,transactions,inboxes,conversations,messages,contacts,workspace,identities,statuses,attachments,connectionAccounts,new CrmChannelsProperties());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public GmailBridge(GmailClient client,JdbcTemplate jdbc,PlatformTransactionManager transactions,InboxRepository inboxes,
        ConversationRepository conversations,ConversationMessageRepository messages,CrmContactService contacts,CrmWorkspaceService workspace,ContactIdentityRepository identities, su.onno.crm.service.CrmConversationStatuses statuses,
        su.onno.crm.service.CrmAttachments attachments,CrmConnectionAccounts connectionAccounts,CrmChannelsProperties properties) {
        this.settings=properties.getGmail();
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
        // Messages that failed to fetch or import: retried, then recorded as seen and skipped.
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_gmail_failed (account VARCHAR(320),external_id VARCHAR(100),attempts INT NOT NULL,last_failure VARCHAR(200),retry_at TIMESTAMP,PRIMARY KEY(account,external_id))");
        // History import progress per mailbox: the label and Gmail page it continues from.
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_gmail_history_import (account VARCHAR(320) PRIMARY KEY,label VARCHAR(100) NOT NULL,page_token VARCHAR(500),threads INT NOT NULL,messages INT NOT NULL,started_at TIMESTAMP NOT NULL,finished_at TIMESTAMP)");
        // Files of history mail, left in Gmail: the message part each media key fetches when opened.
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_gmail_file (id UUID PRIMARY KEY,account VARCHAR(320) NOT NULL,message_id VARCHAR(100) NOT NULL,part_id VARCHAR(100) NOT NULL,filename VARCHAR(255) NOT NULL,content_type VARCHAR(255) NOT NULL)");
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
    record Account(String email,UUID inbox,String history,boolean active,UUID connectionId) {}
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
        backoffs.remove(email.toLowerCase(Locale.ROOT));
    }
    public String key(){return "gmail";}
    public View view(){var a=ready?account():null;String state=!client.configured()?"UNAVAILABLE":a==null?"DISCONNECTED":!failure.isBlank()?"ERROR":a.active?"CONNECTED":"PAUSED";
        return new View("gmail","Gmail","EMAIL",state,a==null?"":a.email,"Sync inbox mail and reply from this CRM. Files are imported with previews and downloads."+notes(a),!client.configured()?List.of():a==null?List.of("connect"):List.of("connect","check",a.active?"pause":"resume"));}
    /** Sentences for a connection card: a rate-limit pause, and a grant that cannot sync read state. */
    private String notes(Account a) {
        if(a==null || !client(a).authorized())return "";
        var text=new StringBuilder();
        var pause=backoffs.get(key(a));
        if(pause!=null && Instant.now().isBefore(pause.until()))
            text.append(" Gmail asked the CRM to slow down; syncing resumes at ").append(pause.until().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)).append('.');
        if(!client(a).canModify())text.append(" Reconnect to let the CRM mark mail read and move it back to the Inbox in Gmail.");
        if(settings.getHistoryImport().isEnabled())historyProgress(a).ifPresent(progress->text.append(progress.finishedAt()==null
            ? " Importing older mail: "+progress.messages()+" emails so far."
            : " Older mail imported: "+progress.messages()+" emails."));
        return text.toString();
    }
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
        return new View(record.id().toString(),"Gmail","EMAIL",state,a==null?"":a.email,"Inbox mail with files, and replies sent from Gmail on known threads."+notes(a),actions);
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

    public Connection connection(Conversation c){return connection(c,null);}
    /**
     * The conversation's own mailbox answers by default. {@code senders} adds every other active,
     * authorised mailbox the reply may come from: shared ones and, when {@code principal} is known,
     * their own personal mailbox; never another person's personal mailbox.
     */
    @Override public Connection connection(Conversation c,Principal principal){if(!ready||!Channel.EMAIL.equals(c.getChannel())||c.getInbox()==null)return new Connection(false,"Gmail is not connected",8000);var a=account(c);
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
        return connection.withRecipients(recipient,List.copyOf(addresses)).withSenders(a.email,senders(a,principal));}
    /** The conversation's own mailbox first, then the others this reply may come from. */
    private List<Sender> senders(Account home,Principal principal) {
        var senders=new ArrayList<Sender>();senders.add(new Sender(home.email,mailboxLabel(home)));
        for(var other:accounts()) {
            if(other.email.equalsIgnoreCase(home.email) || !other.active || !client(other).authorized() || !usableBy(other,principal))continue;
            senders.add(new Sender(other.email,mailboxLabel(other)));
        }
        return senders;
    }
    /**
     * Whether {@code principal} may use this mailbox outside its own inbox: the deployment's own
     * (legacy) mailbox and shared ones always; a personal one only by its owner, so a null
     * principal never gets one.
     */
    private boolean usableBy(Account mailbox,Principal principal) {
        if(mailbox.connectionId==null)return true;
        var record=connectionAccounts==null?null:connectionAccounts.lookup(mailbox.connectionId).orElse(null);
        if(record==null)return false;
        return record.scope()==CrmConnectionAccounts.Scope.SHARED || record.ownedBy(principal);
    }
    /** What a person reads for a mailbox: its account name and address, or just the address. */
    String mailboxLabel(Account mailbox) {
        var record=mailbox.connectionId==null||connectionAccounts==null?null:connectionAccounts.lookup(mailbox.connectionId).orElse(null);
        return record==null || record.label()==null || record.label().isBlank() || record.label().equalsIgnoreCase(mailbox.email)
            ? mailbox.email : record.label()+" · "+mailbox.email;
    }
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
    synchronized void tick(){if(!ready)return;
        try {
            for(var a:accounts()) {
                if(!a.active || !client(a).authorized() || paused(a))continue;
                try {deliver(a);retryFailed(a);sync(a);backfillFiles(a);backfillHtml(a);importHistory(a);backoffs.remove(key(a));if(a.connectionId==null)failure="";else accountFailures.remove(a.connectionId);}
                catch(Exception e){
                    if(e instanceof GmailClient.ApiFailure limited && limited.rateLimited()){pause(a,limited);continue;}
                    String message="Gmail synchronization failed; check connection or reconnect";if(a.connectionId==null)failure=message;else accountFailures.put(a.connectionId,message);
                    log.warn("Gmail synchronization deferred for {} ({})",a.email,describe(e));
                }
            }
        } catch(RuntimeException|LinkageError e) {
            // The scheduler stops a task that throws; keep the worker alive for the next round.
            log.error("Gmail synchronization round failed ({})",e.getClass().getSimpleName(),e);
        }
    }
    private static String describe(Exception e) { return e instanceof GmailClient.ApiFailure failure ? failure.describe() : e.getClass().getSimpleName(); }
    private static String key(Account a) { return a.email.toLowerCase(Locale.ROOT); }
    /** Whether Gmail asked this mailbox to wait and the wait is not over. */
    boolean paused(Account a) { var pause=backoffs.get(key(a));return pause!=null && Instant.now().isBefore(pause.until()); }
    /** Pause one mailbox: until Gmail's Retry-After when it gave one, else 1, 2, 4… minutes, at most an hour. */
    void pause(Account a,GmailClient.ApiFailure failure) {
        var previous=backoffs.get(key(a));
        int strikes=previous==null?1:previous.strikes()+1;
        Duration wait=failure.retryAfter!=null?failure.retryAfter:backoff(strikes);
        var until=Instant.now().plus(wait);
        backoffs.put(key(a),new Backoff(until,strikes));
        log.warn("Gmail rate limit for {}: synchronization paused until {} ({})",a.email,until,failure.describe());
    }
    static Duration backoff(int strikes) {
        Duration wait=Duration.ofMinutes(1L<<Math.min(Math.max(strikes,1)-1,6));
        return wait.compareTo(MAX_BACKOFF)>0?MAX_BACKOFF:wait;
    }
    private void sync(Account a) throws Exception {
        if(a.history==null){
            String checkpoint=client(a).get("profile").path("historyId").asText();
            int days=settings.getInitialImportDays();
            scan(a,"INBOX",days>0?"newer_than:"+days+"d":"");
            jdbc.update("UPDATE onno_crm_gmail_account SET history_id=? WHERE account=?",checkpoint,a.email);return;}
        String next="",checkpoint=a.history;
        do{JsonNode page;try{page=client(a).get("history?historyTypes=messageAdded&historyTypes=labelAdded&startHistoryId="+GmailClient.encode(a.history)+"&maxResults=100"+(next.isBlank()?"":"&pageToken="+GmailClient.encode(next)));}
            catch(GmailClient.ApiFailure e){if(e.status==404){rescan(a);return;}throw e;}
            for(var history:page.path("history")) {
                for(var added:history.path("messagesAdded")){var m=added.path("message");if(labelled(m,"INBOX")||labelled(m,"SENT"))ingest(a,m.path("id").asText());}
                // Mail moved back to the Inbox in Gmail (unarchived, or out of spam) shows up too.
                for(var labelled:history.path("labelsAdded")){boolean inbox=false;for(var label:labelled.path("labelIds"))if(label.asText().equals("INBOX"))inbox=true;
                    if(inbox)ingest(a,labelled.path("message").path("id").asText());}
            }
            checkpoint=page.path("historyId").asText(checkpoint);next=page.path("nextPageToken").asText();
        }while(!next.isBlank());jdbc.update("UPDATE onno_crm_gmail_account SET history_id=? WHERE account=?",checkpoint,a.email);
    }
    private static boolean labelled(JsonNode message,String label) {
        for(var value:message.path("labelIds"))if(value.asText().equals(label))return true;
        return false;
    }
    /**
     * Gmail's history expired (it keeps about a week): take a new checkpoint, then import Inbox and
     * sent mail from a day before the mailbox's latest imported email, so the gap is filled rather
     * than skipped. Already imported messages are skipped by id. The checkpoint is saved only once
     * the scan finished, so an interrupted scan is simply repeated.
     */
    private void rescan(Account a) throws Exception {
        String checkpoint=client(a).get("profile").path("historyId").asText();
        var latest=jdbc.query("SELECT MAX(last_received) FROM onno_crm_gmail_thread WHERE account=?",(rs,n)->rs.getTimestamp(1),a.email);
        Instant since=latest.isEmpty()||latest.getFirst()==null
            ? settings.getInitialImportDays()>0?Instant.now().minus(Duration.ofDays(settings.getInitialImportDays())):null
            : latest.getFirst().toInstant().minus(Duration.ofDays(1));
        String query=since==null?"":"after:"+since.getEpochSecond();
        log.info("Gmail history expired for {}; re-scanning mail {}",a.email,since==null?"without an age limit":"since "+since);
        scan(a,"INBOX",query);
        scan(a,"SENT",query);
        jdbc.update("UPDATE onno_crm_gmail_account SET history_id=? WHERE account=?",checkpoint,a.email);
    }
    /** Import one label's messages matching {@code query}, newest first, up to the configured limit. */
    private void scan(Account a,String label,String query) throws Exception {
        int limit=settings.getInitialImportLimit(),count=0;String token="";
        do {
            int size=limit<=0?100:Math.min(100,limit-count);
            var page=client(a).get("messages?labelIds="+label+"&maxResults="+size+(query.isBlank()?"":"&q="+GmailClient.encode(query))+(token.isBlank()?"":"&pageToken="+GmailClient.encode(token)));
            for(var item:page.path("messages")){ingest(a,item.path("id").asText(),true);count++;}
            token=page.path("nextPageToken").asText("");
        } while(!token.isBlank() && (limit<=0 || count<limit));
    }
    private boolean seen(Account a,String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_seen WHERE account=? AND external_id=?",Integer.class,a.email,id)>0;
    }
    private void ingest(Account a,String id) throws Exception { ingest(a,id,false); }
    /**
     * Import one message unless it was seen. A failure that belongs to the mailbox (rate limit,
     * revoked access) is thrown; one that belongs to this message (an answer that cannot be read,
     * MIME that cannot be parsed, a rejected import) is counted, retried later and finally skipped.
     *
     * @param backfill imported by a scan rather than as it arrived: unread only if Gmail says so
     */
    private void ingest(Account a,String id,boolean backfill) throws Exception {
        if(seen(a,id))return;
        JsonNode raw;
        try{raw=client(a).get("messages/"+GmailClient.encode(id)+"?format=raw");}
        catch(GmailClient.ApiFailure e){
            if(e.status==404)return;
            if(mailboxFailure(e))throw e;
            failed(a,id,e.describe());return;
        }
        try{save(a,id,raw,GmailMail.read(raw.path("raw").asText()),backfill?Mode.SCAN:Mode.LIVE);}
        catch(Exception e){failed(a,id,e.getClass().getSimpleName());}
    }
    /** Retry messages whose earlier attempts failed, once their wait is over. */
    private void retryFailed(Account a) throws Exception {
        for(String id:jdbc.query("SELECT external_id FROM onno_crm_gmail_failed WHERE account=? AND attempts<? AND (retry_at IS NULL OR retry_at<=?)",
                (rs,n)->rs.getString(1),a.email,MAX_ATTEMPTS,Timestamp.from(Instant.now())))
            ingest(a,id,true);
    }
    /** Count a failed attempt; at {@link #MAX_ATTEMPTS} the message is recorded as seen and skipped. */
    private void failed(Account a,String id,String why) {
        String reason=cut(why,200);
        var known=jdbc.query("SELECT attempts FROM onno_crm_gmail_failed WHERE account=? AND external_id=?",(rs,n)->rs.getInt(1),a.email,id);
        int attempts=(known.isEmpty()?0:known.getFirst())+1;
        boolean skip=attempts>=MAX_ATTEMPTS;
        var retryAt=Timestamp.from(Instant.now().plus(attempts==1?Duration.ofMinutes(1):Duration.ofMinutes(10)));
        tx.executeWithoutResult(s->{
            if(known.isEmpty())jdbc.update("INSERT INTO onno_crm_gmail_failed (account,external_id,attempts,last_failure,retry_at) VALUES (?,?,?,?,?)",a.email,id,attempts,reason,retryAt);
            else jdbc.update("UPDATE onno_crm_gmail_failed SET attempts=?,last_failure=?,retry_at=? WHERE account=? AND external_id=?",attempts,reason,retryAt,a.email,id);
            if(skip && !seen(a,id))jdbc.update("INSERT INTO onno_crm_gmail_seen VALUES (?,?)",a.email,id);
        });
        if(skip)log.warn("Gmail message {} in {} skipped after {} failed attempts ({})",id,a.email,attempts,reason);
        else log.warn("Gmail message {} in {} could not be imported; attempt {} of {} ({})",id,a.email,attempts,MAX_ATTEMPTS,reason);
    }
    /**
     * Store one parsed email. {@code meta} is Gmail's message ({@code threadId}, {@code labelIds},
     * {@code internalDate}), from either format. History mail is never unread, a conversation it
     * starts is closed, and its files stay in Gmail.
     *
     * @return whether the email was stored (a sent email on an unknown thread is not)
     */
    private boolean save(Account a,String id,JsonNode meta,GmailMail.Incoming mail,Mode mode) {
        String thread=meta.path("threadId").asText();
        var receivedAt=LocalDateTime.ofInstant(meta.hasNonNull("internalDate")?Instant.ofEpochMilli(meta.path("internalDate").asLong()):mail.sentAt(),ZoneId.systemDefault());
        boolean unreadInGmail=labelled(meta,"UNREAD");
        return Boolean.TRUE.equals(tx.execute(s->{workspace.lock();if(seen(a,id))return false;
            boolean outbound=labelled(meta,"SENT");
            var ids=jdbc.query("SELECT conversation_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=?",(rs,n)->rs.getObject(1,UUID.class),a.email,thread);Conversation c;
            if(outbound && ids.isEmpty())return false; // Only import replies to known CRM threads.
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
                    var files=mode==Mode.HISTORY?remoteFiles(a,id,mail):importFiles(mail);
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
                var files=mode==Mode.HISTORY?remoteFiles(a,id,mail):importFiles(mail);
                message.setBody(cut(files.withNotes(mail.text()),8000));message.setBodyHtml(files.html(mail));message.setDescription(cut(mail.text(),100));message.setSentAt(receivedAt);message.setDeliveryStatus(DeliveryStatus.RECEIVED);message.setExternalMessageId(cut("gmail:"+a.email+":"+id,240));message.setAttachments(files.urls());messages.save(message);
                if(c.getLastMessageAt()==null||!message.getSentAt().isBefore(c.getLastMessageAt())){c.setLastMessageAt(message.getSentAt());c.setLastMessagePreview(cut(mail.text(),180));}
                jdbc.update("UPDATE onno_crm_gmail_thread SET reply_to=?,subject=?,reference_id=?,last_received=? WHERE account=? AND thread_id=? AND (last_received IS NULL OR last_received<=?)",cut(mail.replyTo(),320),cut(mail.subject(),998),cut(mail.messageId(),998),message.getSentAt(),a.email,thread,message.getSentAt());
                // A scanned (older) email counts as unread only if it is still unread in Gmail; history never does.
                boolean unread=mode!=Mode.HISTORY && su.onno.crm.channels.ExternalReplyReadState.unread(message,messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,c.getId())),su.onno.crm.channels.ExternalReplyReadState.NO_TIE_BREAK)
                    && (mode==Mode.LIVE || unreadInGmail);
                if(unread){c.setUnreadCount(c.getUnreadCount()+1);c.setStatus(statuses.incoming());}
                else if(c.getStatus()==null)c.setStatus(mode==Mode.HISTORY?closedStatus():statuses.incoming());
                conversations.save(c);}
            jdbc.update("INSERT INTO onno_crm_gmail_seen VALUES (?,?)",a.email,id);
            jdbc.update("DELETE FROM onno_crm_gmail_failed WHERE account=? AND external_id=?",a.email,id);
            return true;
        }));
    }
    /** The closed status for a conversation that history starts; the incoming one when none is configured. */
    private UUID closedStatus() {
        try { var closed=statuses.close();return closed!=null?closed:statuses.incoming(); }
        catch(RuntimeException noTransitions) { return statuses.incoming(); }
    }
    /** Whether a mailbox-level failure must stop this round (rate limit, revoked access). */
    /**
     * A failure of the mailbox or the connection rather than of one message: thrown so the round stops,
     * never counted against the message. That includes no answer at all and Gmail's own 5xx, so a
     * network outage cannot use up an email's attempts and skip it.
     */
    private static boolean mailboxFailure(GmailClient.ApiFailure e) { return e.rateLimited() || e.status==401 || e.status==403 || e.status==0 || e.status>=500; }
    private void backfillFiles(Account a) throws Exception {
        if(attachments.limit()==0 || jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_files_backfill_v2 WHERE account=?",Integer.class,a.email)>0)return;
        var page=client(a).get("messages?labelIds=INBOX&maxResults=50");
        for(var item:page.path("messages")) {
            String id=item.path("id").asText();
            try {
                var raw=client(a).get("messages/"+GmailClient.encode(id)+"?format=raw");
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
            } catch(GmailClient.ApiFailure e) {
                if(mailboxFailure(e))throw e;
                log.warn("Gmail file backfill skipped message {} in {} ({})",id,a.email,e.describe());
            } catch(Exception e) {
                log.warn("Gmail file backfill skipped message {} in {} ({})",id,a.email,e.getClass().getSimpleName());
            }
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
            try {
                var raw=client(a).get("messages/"+GmailClient.encode(id)+"?format=raw");
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
            } catch(GmailClient.ApiFailure e) {
                if(mailboxFailure(e))throw e;
                log.warn("Gmail HTML backfill skipped message {} in {} ({})",id,a.email,e.describe());
            } catch(Exception e) {
                log.warn("Gmail HTML backfill skipped message {} in {} ({})",id,a.email,e.getClass().getSimpleName());
            }
        }
        jdbc.update("INSERT INTO onno_crm_gmail_html_backfill VALUES (?)",a.email);
    }
    record HistoryProgress(String label,String pageToken,int threads,int messages,Instant finishedAt) {}
    Optional<HistoryProgress> historyProgress(Account a) {
        return jdbc.query("SELECT label,page_token,threads,messages,finished_at FROM onno_crm_gmail_history_import WHERE account=?",
            (rs,n)->new HistoryProgress(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant()),a.email).stream().findFirst();
    }
    /**
     * One step of a mailbox's history import: the next page of threads under the current label,
     * each thread's emails stored as history. Runs only once the first import set a checkpoint, so
     * new mail is never held up behind old. A mailbox failure (rate limit, revoked access, Gmail
     * unreachable or failing) is thrown like any sync failure and the page is tried again next
     * round; a thread or email Gmail cannot return or that cannot be read is logged and skipped.
     */
    void importHistory(Account a) throws Exception {
        var options=settings.getHistoryImport();
        if(!options.isEnabled() || a.history==null)return;
        var progress=historyProgress(a).orElse(null);
        if(progress==null) {
            jdbc.update("INSERT INTO onno_crm_gmail_history_import (account,label,page_token,threads,messages,started_at) VALUES (?,?,NULL,0,0,?)",
                a.email,options.getLabels().getFirst(),Timestamp.from(Instant.now()));
            log.info("Gmail history import started for {} ({})",a.email,String.join(", ",options.getLabels()));
            progress=historyProgress(a).orElseThrow();
        }
        if(progress.finishedAt()!=null)return;
        int threads=0,stored=0;String next;
        try {
            JsonNode page;
            try { page=client(a).get("threads?labelIds="+GmailClient.encode(progress.label())+"&maxResults="+options.getThreadsPerRound()
                    +(progress.pageToken()==null?"":"&pageToken="+GmailClient.encode(progress.pageToken()))); }
            catch(GmailClient.ApiFailure e) {
                if(e.status!=400 || progress.pageToken()==null)throw e;
                // Gmail no longer knows the page: walk the label again; imported emails are skipped.
                log.warn("Gmail history import for {} restarts {} ({})",a.email,progress.label(),e.describe());
                jdbc.update("UPDATE onno_crm_gmail_history_import SET page_token=NULL WHERE account=?",a.email);
                return;
            }
            for(var thread:page.path("threads")){stored+=importThread(a,thread.path("id").asText(),options);threads++;}
            next=page.path("nextPageToken").asText("");
        } catch(GmailClient.ApiFailure e) {
            if(mailboxFailure(e))throw e;
            log.warn("Gmail history import for {} deferred ({})",a.email,e.describe());return;
        } finally {
            if(stored>0 && uiEvents!=null)uiEvents.publish("UPDATED","page","crm-inbox-workspaces",null);
        }
        String label=progress.label();Timestamp finished=null;
        if(next.isBlank()) {
            int at=options.getLabels().indexOf(label);
            if(at>=0 && at+1<options.getLabels().size())label=options.getLabels().get(at+1);
            else finished=Timestamp.from(Instant.now());
        }
        jdbc.update("UPDATE onno_crm_gmail_history_import SET label=?,page_token=?,threads=threads+?,messages=messages+?,finished_at=? WHERE account=?",
            label,next.isBlank()?null:next,threads,stored,finished,a.email);
        int total=progress.messages()+stored;
        if(finished!=null)log.info("Gmail history import finished for {}: {} emails in {} threads",a.email,total,progress.threads()+threads);
        else if(total/500>progress.messages()/500)log.info("Gmail history import for {}: {} emails so far",a.email,total);
    }
    /**
     * Store a thread's emails as history: received ones first, oldest first, so the thread is known
     * when the studio's own replies (which only join known threads) follow. Drafts, spam, trash and
     * skipped categories are left out; emails already imported are skipped.
     */
    private int importThread(Account a,String threadId,CrmChannelsProperties.HistoryImport options) throws Exception {
        JsonNode thread;
        try { thread=client(a).get("threads/"+GmailClient.encode(threadId)+"?format=full"); }
        catch(GmailClient.ApiFailure e) {
            // A thread Gmail cannot return (deleted since the page was listed) never holds up the rest.
            if(mailboxFailure(e))throw e;
            log.warn("Gmail history import skipped thread {} in {} ({})",threadId,a.email,e.describe());
            return 0;
        }
        var received=new ArrayList<JsonNode>();var sent=new ArrayList<JsonNode>();
        for(var message:thread.path("messages")) {
            if(labelled(message,"DRAFT")||labelled(message,"SPAM")||labelled(message,"TRASH"))continue;
            if(options.getSkipCategories().stream().anyMatch(category->labelled(message,category)))continue;
            if(seen(a,message.path("id").asText()))continue;
            (labelled(message,"SENT")?sent:received).add(message);
        }
        Comparator<JsonNode> oldestFirst=Comparator.comparingLong(m->m.path("internalDate").asLong());
        received.sort(oldestFirst);sent.sort(oldestFirst);
        int stored=0;
        for(var list:List.of(received,sent))for(var message:list) {
            String id=message.path("id").asText();
            try {
                var mail=GmailMail.readFull(message);
                boolean[] done={false};
                CrmHistoryImport.run(()->done[0]=save(a,id,message,mail,Mode.HISTORY));
                if(done[0])stored++;
            } catch(Exception e) {
                log.warn("Gmail history import skipped message {} in {} ({})",id,a.email,e.getClass().getSimpleName());
            }
        }
        return stored;
    }
    /** A history email's files as media references that fetch from Gmail when opened; nothing is downloaded now. */
    private ImportedFiles remoteFiles(Account a,String messageId,GmailMail.Incoming mail) {
        if(mail.remote().isEmpty())return new ImportedFiles(null,List.of());
        var urls=new ArrayList<String>();var notes=new ArrayList<String>();var byContentId=new HashMap<String,String>();
        for(var file:mail.remote()) {
            UUID id=UUID.randomUUID();
            String url=attachments.limit()==0?null:attachments.reference(FILE_PREFIX+id+"/"+mediaName(file.filename()));
            if(url==null){notes.add("[Attachment: "+file.filename()+"]");continue;}
            jdbc.update("INSERT INTO onno_crm_gmail_file (id,account,message_id,part_id,filename,content_type) VALUES (?,?,?,?,?,?)",
                id,a.email,messageId,cut(file.partId(),100),cut(file.filename(),255),cut(file.contentType(),255));
            urls.add(url);
            if(file.contentId()!=null)byContentId.put(file.contentId(),url);
        }
        return new ImportedFiles(urls.isEmpty()?null:attachments.store(urls),List.copyOf(notes),Map.copyOf(byContentId));
    }
    /** A file name reduced to what a media key allows; the real name is served from the table. */
    static String mediaName(String filename) {
        String name=Objects.toString(filename,"").replaceAll("[^A-Za-z0-9_.-]","_").replaceAll("_{2,}","_").replaceAll("\\.{2,}",".").replaceAll("^\\.+","");
        if(name.isBlank())name="file";
        return name.length()<=80?name:name.substring(name.length()-80);
    }
    /** A file kept in Gmail, described from the table; its bytes are fetched only when it is read. */
    Optional<su.onno.ui.media.LoadedMedia> file(String key) {
        if(!key.startsWith(FILE_PREFIX))return Optional.empty();
        String rest=key.substring(FILE_PREFIX.length());int slash=rest.indexOf('/');
        UUID id;try{id=UUID.fromString(slash<0?rest:rest.substring(0,slash));}catch(IllegalArgumentException e){return Optional.empty();}
        return jdbc.query("SELECT account,message_id,part_id,filename,content_type FROM onno_crm_gmail_file WHERE id=?",(rs,n)->{
            String account=rs.getString(1),message=rs.getString(2),part=rs.getString(3),filename=rs.getString(4);
            var resource=new org.springframework.core.io.AbstractResource() {
                private byte[] bytes;
                @Override public String getDescription() { return "Gmail file "+filename; }
                @Override public String getFilename() { return filename; }
                // Spring asks for the length before it writes any header, so a Gmail failure becomes an
                // error response rather than a broken 200; the bytes are fetched once and reused.
                @Override public long contentLength() throws java.io.IOException { return bytes().length; }
                @Override public java.io.InputStream getInputStream() throws java.io.IOException { return new java.io.ByteArrayInputStream(bytes()); }
                private synchronized byte[] bytes() throws java.io.IOException {
                    if(bytes!=null)return bytes;
                    try { return bytes=fileBytes(account,message,part); }
                    catch(java.io.IOException e) { throw e; }
                    catch(Exception e) { throw new java.io.IOException("Gmail file could not be fetched ("+describe(e)+")",e); }
                }
            };
            // The size is unknown until Gmail sends the bytes, so none is announced.
            return new su.onno.ui.media.LoadedMedia(resource,rs.getString(5),-1,filename);
        },id).stream().findFirst();
    }
    /** Fetch one file of a Gmail message by its part: the part's current attachment id, then its bytes. */
    byte[] fileBytes(String account,String messageId,String partId) throws Exception {
        var a=accounts().stream().filter(candidate->candidate.email.equalsIgnoreCase(account)).findFirst()
            .orElseThrow(()->new java.io.IOException("The mailbox is no longer connected"));
        requireNotPaused(a);
        var message=interactive(a,()->client(a).get("messages/"+GmailClient.encode(messageId)+"?format=full"));
        var part=part(message.path("payload"),partId);
        if(part==null)throw new java.io.FileNotFoundException("The file is no longer in Gmail");
        var body=part.path("body");
        String data=body.hasNonNull("attachmentId")
            ? interactive(a,()->client(a).get("messages/"+GmailClient.encode(messageId)+"/attachments/"+GmailClient.encode(body.path("attachmentId").asText()))).path("data").asText("")
            : body.path("data").asText("");
        return Base64.getUrlDecoder().decode(data.replace('+','-').replace('/','_').replace("=",""));
    }
    private static JsonNode part(JsonNode part,String partId) {
        if(part.path("partId").asText("").equals(partId))return part;
        for(var child:part.path("parts")){var found=part(child,partId);if(found!=null)return found;}
        return null;
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
        Account from=a;
        try{var message=messages.findActiveById(id).orElseThrow();var c=conversations.findActiveById(message.getConversation().id()).orElseThrow();if(!connection(c).connected())throw new IllegalStateException();
            from=sender(a,message);
            if(from!=a && paused(from)){requeue(id);continue;}
            send(a,from,id,message,c);
        }catch(GmailClient.ApiFailure e){
            if(!e.rateLimited()){tx.executeWithoutResult(s->finish(id,false,null));continue;}
            // Gmail refused the request, so nothing was sent: the reply waits in the queue instead of failing.
            requeue(id);
            if(from==a)throw e;
            pause(from,e);
        }catch(Exception e){tx.executeWithoutResult(s->finish(id,false,null));}
    }}
    private void requeue(UUID id){jdbc.update("UPDATE onno_crm_gmail_outbox SET state='QUEUED' WHERE message_id=? AND state='SENDING'",id);}
    /**
     * The mailbox a queued reply goes out from: the one chosen when it was queued
     * ({@code ConversationMessage.senderAccount}), else the conversation's own.
     */
    private Account sender(Account home,ConversationMessage message) {
        String chosen=message.getSenderAccount();
        if(chosen==null || chosen.isBlank() || chosen.equalsIgnoreCase(home.email))return home;
        var from=accounts().stream().filter(other->other.email.equalsIgnoreCase(chosen)).findFirst()
            .orElseThrow(()->new IllegalStateException("The sending mailbox is no longer connected"));
        if(!from.active || !client(from).authorized())throw new IllegalStateException("The sending mailbox is paused or disconnected");
        return from;
    }
    private void send(Account home,Account from,UUID id,ConversationMessage message,Conversation c) throws Exception {
        var row=jdbc.queryForMap("SELECT thread_id,reply_to,subject,reference_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=(SELECT thread_id FROM onno_crm_gmail_outbox WHERE message_id=?)",home.email,id);
        var files=attachments.of(message).stream().map(file->new GmailMail.Outgoing(
            file.filename(),file.contentType(),attachments.bytes(file))).toList();
        // Base64 inflates by a third and Gmail refuses a message over 35 MB, so an oversized
        // batch fails here with a reason rather than at the API with an opaque rejection.
        long total=files.stream().mapToLong(f->f.content().length).sum();
        if(total>25L*1024*1024)throw new IllegalStateException("Attachments exceed what Gmail accepts on one message");
        String originalSubject=Objects.toString(row.get("subject"),"");
        String homeReplyTo=Objects.toString(row.get("reply_to"),"");
        String subject=message.getSubject();
        String recipient=message.getRecipient()==null?homeReplyTo:message.getRecipient();
        String outgoingSubject=subject==null?originalSubject:subject;
        String body=message.getBody()==null?"":message.getBody();
        Map<String,Object> route=row;boolean newThread;
        if(from==home) newThread=(subject!=null&&!subject.equals(originalSubject)) || !Objects.equals(emailAddress(recipient),emailAddress(homeReplyTo));
        else {
            // Another mailbox answers in the thread it already has in this conversation while the
            // address and subject still match; otherwise it starts its own thread.
            var own=jdbc.queryForList("SELECT thread_id,reply_to,subject,reference_id FROM onno_crm_gmail_thread WHERE account=? AND conversation_id=? ORDER BY last_received DESC NULLS LAST,thread_id DESC FETCH FIRST 1 ROW ONLY",from.email,c.getId());
            route=own.isEmpty()?null:own.getFirst();
            newThread=route==null || !Objects.equals(emailAddress(recipient),emailAddress(Objects.toString(route.get("reply_to"),"")))
                || subject!=null&&!subject.equals(Objects.toString(route.get("subject"),""));
        }
        String raw=newThread
            ? GmailMail.compose(from.email,recipient,outgoingSubject,"",body,files,id)
            : GmailMail.reply(from.email,recipient,Objects.toString(route.get("subject"),""),Objects.toString(route.get("reference_id"),""),body,files,id);
        var sent=client(from).send(raw,newThread?null:route.get("thread_id").toString());
        tx.executeWithoutResult(s->{
            if(newThread&&!sent.path("threadId").asText().isBlank()) {
                // Recorded under the sending mailbox, so the contact's answer there syncs into this conversation.
                String thread=sent.path("threadId").asText();
                String reference="<onno-"+id+"@"+from.email.substring(from.email.indexOf('@')+1)+">";
                if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_thread WHERE account=? AND thread_id=?",Integer.class,from.email,thread)==0)
                    jdbc.update("INSERT INTO onno_crm_gmail_thread (account,thread_id,conversation_id,reply_to,subject,reference_id,last_received) VALUES (?,?,?,?,?,?,?)",from.email,thread,c.getId(),recipient,outgoingSubject,reference,message.getSentAt());
                c.setSubject(outgoingSubject.isBlank()?"(No subject)":cut(outgoingSubject,240));conversations.save(c);
            }
            finish(id,true,sent.path("id").asText());
        });
    }
    /**
     * The conversation a new thread from this contact joins: the contact's conversation in this
     * mailbox's own inbox. A thread this mailbox started for a conversation elsewhere (a reply sent
     * from it) keeps its own route, but does not pull the contact's other mail into that inbox.
     */
    private Conversation conversationForCustomer(Account a,UUID customer) {
        UUID canonical=contacts.canonical(customer);
        // The contact's own id and every merged id that redirects to it: a mailbox with thousands of
        // conversations is never walked whole for one new thread.
        var redirects=contacts.redirects();
        var ids=new LinkedHashSet<UUID>();ids.add(canonical);
        for(UUID source:redirects.keySet())if(canonical.equals(CrmContactService.canonical(source,redirects)))ids.add(source);
        for(UUID id:ids)for(var c:conversations.findByCustomerAndDeletionMarkFalse(id)) {
            if(ownInbox(a,c) && jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_thread WHERE account=? AND conversation_id=?",Integer.class,a.email,c.getId())>0)return c;
        }
        return null;
    }
    private static boolean ownInbox(Account a,Conversation c) { return c.getInbox()!=null && a.inbox.equals(c.getInbox().id()); }
    /** Upgrade thread-per-chat imports without losing messages, notes or original thread routes. */
    private void groupExistingConversations() {
        var a=account();if(a==null)return;
        tx.executeWithoutResult(s->{workspace.lock();Map<UUID,Conversation> groups=new LinkedHashMap<>();
            var ids=jdbc.query("SELECT DISTINCT conversation_id FROM onno_crm_gmail_thread WHERE account=? ORDER BY conversation_id",(rs,n)->rs.getObject(1,UUID.class),a.email);
            for(UUID id:ids){var source=conversations.findActiveById(id).orElse(null);if(source==null)continue;
                // Threads sent from this mailbox for another inbox's conversation are never merged into it.
                if(!ownInbox(a,source))continue;
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

    /**
     * The CRM marked an email conversation read: after commit, drop Gmail's UNREAD label from its
     * imported emails, in every mailbox whose grant allows it ({@code gmail.modify}). Best-effort
     * and off the request thread; a failure is logged, never shown.
     */
    @Override public void markedRead(Conversation conversation) {
        if(!ready || conversation==null || conversation.getId()==null || !Channel.EMAIL.equals(conversation.getChannel()))return;
        UUID id=conversation.getId();
        Runnable submit=()->{try{readSync.execute(()->markReadInGmail(id));}catch(RejectedExecutionException stopped){/* shutting down */}};
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){submit.run();}});
        else submit.run();
    }
    /** Clear UNREAD in Gmail for the conversation's imported emails, mailbox by mailbox. */
    void markReadInGmail(UUID conversationId) {
        var byMailbox=new LinkedHashMap<String,List<String>>();
        var history=messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,conversationId));
        for(int i=history.size()-1;i>=0;i--) {
            var message=history.get(i);String external=message.getExternalMessageId();
            if(message.getDirection()!=MessageDirection.INBOUND || external==null || !external.startsWith("gmail:"))continue;
            String rest=external.substring("gmail:".length());int split=rest.lastIndexOf(':');
            if(split<=0)continue;
            var ids=byMailbox.computeIfAbsent(rest.substring(0,split).toLowerCase(Locale.ROOT),key->new ArrayList<>());
            if(ids.size()<READ_SYNC_LIMIT)ids.add(rest.substring(split+1));
        }
        for(var entry:byMailbox.entrySet()) {
            var mailbox=accounts().stream().filter(a->key(a).equals(entry.getKey())).findFirst().orElse(null);
            if(mailbox==null || !mailbox.active || paused(mailbox))continue;
            var gmail=client(mailbox);
            if(!gmail.authorized() || !gmail.canModify())continue;
            try {
                var request=JsonNodeFactory.instance.objectNode();
                var ids=request.putArray("ids");entry.getValue().forEach(ids::add);
                request.putArray("removeLabelIds").add("UNREAD");
                gmail.post("messages/batchModify",request);
            } catch(GmailClient.ApiFailure e) {
                if(e.rateLimited())pause(mailbox,e);
                log.warn("Gmail read state not updated for {} ({})",mailbox.email,e.describe());
            } catch(RuntimeException e) {
                log.warn("Gmail read state not updated for {} ({})",mailbox.email,e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Active, authorised mailboxes {@code principal} may search and restore from: the deployment's
     * own and shared mailboxes, and their own personal ones. Inbox read access is checked by the caller.
     */
    List<Account> readableMailboxes(Principal principal) {
        if(!ready)return List.of();
        return accounts().stream().filter(a->a.active && client(a).authorized() && usableBy(a,principal))
            .sorted(Comparator.comparing(Account::email,String.CASE_INSENSITIVE_ORDER)).toList();
    }
    Optional<Account> readableMailbox(String address,Principal principal) {
        if(address==null)return Optional.empty();
        String wanted=address.strip();
        return readableMailboxes(principal).stream().filter(a->a.email.equalsIgnoreCase(wanted)).findFirst();
    }
    boolean canModify(Account a) { return client(a).canModify(); }
    /** The CRM conversation a Gmail thread of this mailbox already belongs to, if any. */
    UUID conversationOf(Account a,String threadId) {
        var ids=jdbc.query("SELECT conversation_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=?",(rs,n)->rs.getObject(1,UUID.class),a.email,threadId);
        return ids.isEmpty()?null:ids.getFirst();
    }
    private void requireNotPaused(Account a) {
        var pause=backoffs.get(key(a));
        if(pause!=null && Instant.now().isBefore(pause.until()))
            throw new GmailClient.ApiFailure(429,"paused",Duration.between(Instant.now(),pause.until()),null);
    }
    /** A rate-limit answer to an interactive request pauses the mailbox's sync too, then is rethrown. */
    private <T> T interactive(Account a,java.util.concurrent.Callable<T> call) throws Exception {
        requireNotPaused(a);
        try { return call.call(); }
        catch(GmailClient.ApiFailure e) { if(e.rateLimited())pause(a,e);throw e; }
    }
    record SearchHit(String id,String threadId,String from,String subject,String snippet,String date,boolean archived,UUID conversationId) {}
    record SearchPage(List<SearchHit> messages,String nextPageToken) {}
    /**
     * Gmail search over all of a mailbox's mail, archived included (spam and trash are not, as in
     * Gmail), {@value #SEARCH_PAGE} per page, each hit with its headers, labels and CRM conversation.
     */
    SearchPage search(Account a,String query,String pageToken) throws Exception {
        return interactive(a,()->{
            var gmail=client(a);
            var page=gmail.get("messages?maxResults="+SEARCH_PAGE+"&q="+GmailClient.encode(query)+(pageToken==null||pageToken.isBlank()?"":"&pageToken="+GmailClient.encode(pageToken)));
            var hits=new ArrayList<SearchHit>();
            for(var item:page.path("messages")) {
                String id=item.path("id").asText();
                JsonNode meta;
                try{meta=gmail.get("messages/"+GmailClient.encode(id)+"?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date");}
                catch(GmailClient.ApiFailure e){if(e.status==404)continue;throw e;}
                String from="",subject="";
                for(var header:meta.path("payload").path("headers")) {
                    String name=header.path("name").asText();
                    if(name.equalsIgnoreCase("From"))from=header.path("value").asText();
                    else if(name.equalsIgnoreCase("Subject"))subject=header.path("value").asText();
                }
                String thread=meta.path("threadId").asText(item.path("threadId").asText());
                String date=meta.hasNonNull("internalDate")?Instant.ofEpochMilli(meta.path("internalDate").asLong()).toString():null;
                hits.add(new SearchHit(id,thread,cut(from,320),cut(subject,998),cut(meta.path("snippet").asText(),500),date,!labelled(meta,"INBOX"),conversationOf(a,thread)));
            }
            String next=page.path("nextPageToken").asText("");
            return new SearchPage(List.copyOf(hits),next.isBlank()?null:next);
        });
    }
    record Restored(UUID conversationId,boolean movedToInbox) {}
    /**
     * Bring a message's whole Gmail thread into the CRM through the same import the sync uses,
     * wherever it sits in Gmail (archived included; spam, trash and drafts are left out). Messages
     * already imported are skipped, so repeating it changes nothing. With {@code toInbox}, and a
     * grant that allows it, the thread is also moved back to the Inbox in Gmail.
     */
    Restored restore(Account a,String messageId,boolean toInbox) throws Exception {
        return interactive(a,()->{
            var gmail=client(a);
            String threadId=gmail.get("messages/"+GmailClient.encode(messageId)+"?format=minimal").path("threadId").asText();
            if(threadId.isBlank())throw new GmailClient.ApiFailure(404);
            var thread=gmail.get("threads/"+GmailClient.encode(threadId)+"?format=minimal");
            var incoming=new ArrayList<String>();var sent=new ArrayList<String>();
            for(var message:thread.path("messages")) {
                if(labelled(message,"SPAM")||labelled(message,"TRASH")||labelled(message,"DRAFT"))continue;
                (labelled(message,"SENT")?sent:incoming).add(message.path("id").asText());
            }
            // Incoming mail first: it makes the thread known, so replies sent from Gmail can join it.
            for(String id:incoming)ingest(a,id,true);
            for(String id:sent)ingest(a,id,true);
            UUID conversation=conversationOf(a,threadId);
            if(conversation==null)throw new IllegalStateException("This thread has no email from a contact to import");
            boolean moved=false;
            if(toInbox && gmail.canModify()) {
                var request=JsonNodeFactory.instance.objectNode();request.putArray("addLabelIds").add("INBOX");
                gmail.post("threads/"+GmailClient.encode(threadId)+"/modify",request);
                moved=true;
            }
            return new Restored(conversation,moved);
        });
    }
    private void finish(UUID id,boolean sent,String external){messages.findActiveById(id).ifPresent(m->{m.setDeliveryStatus(sent?DeliveryStatus.SENT:DeliveryStatus.FAILED);if(external!=null)m.setExternalMessageId("gmail:"+external);messages.save(m);});jdbc.update("UPDATE onno_crm_gmail_outbox SET state=? WHERE message_id=?",sent?"SENT":"FAILED",id);}
    private static String cut(String text,int max){return text==null?"":text.substring(0,Math.min(max,text.length()));}
    @PreDestroy public void stop(){ready=false;worker.shutdownNow();readSync.shutdownNow();}
}
