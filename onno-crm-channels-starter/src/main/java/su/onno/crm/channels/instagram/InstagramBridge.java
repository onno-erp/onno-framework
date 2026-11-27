package su.onno.crm.channels.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import su.onno.crm.channels.CrmChannelsProperties;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;

/**
 * Single-account local adapter. Polls provider-visible messages; webhook hosting is separate.
 *
 * <p>Only messages sent at or after the account's persisted activation cutoff
 * ({@code onno_crm_ig_sync.activation_cutoff}, an ISO-8601 UTC instant) are imported; the cutoff is
 * written once and never reset. Each poll reads the conversation list head-first (most recently
 * updated first) and stops at the account's checkpoint minus an overlap window, downloads the recent
 * messages only of conversations that changed since their last download (per-thread watermark in
 * {@code onno_crm_ig_thread}), and bounds both pages and downloads per poll. A longer backlog is
 * resumed from a saved cursor on later polls, after the newest conversations are rechecked first.
 * Watermarks, cursors and the checkpoint advance only after the messages they cover have committed;
 * provider message ids deduplicate every overlap.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(InstagramBridge.class)
@Component
@ConditionalOnProperty(name="onno.crm.channels.instagram.enabled",havingValue="true")
public class InstagramBridge implements CrmChannelConnection,CrmMessageTransport {
    private static final Logger log=LoggerFactory.getLogger(InstagramBridge.class);
    /** How far behind the checkpoint a poll still looks, absorbing late or out-of-order provider timestamps. */
    static final Duration OVERLAP=Duration.ofMinutes(2);
    /**
     * A conversation is downloaded again until one download happened at least this long after its
     * latest {@code updated_time}: Instagram timestamps have one-second precision, so a later message
     * in the same second leaves {@code updated_time} unchanged.
     */
    static final Duration SETTLE=Duration.ofMinutes(1);
    /** How long a downloaded profile picture (or Instagram's "none") stands before it is checked again. */
    static final Duration AVATAR_REFRESH=Duration.ofDays(7);
    /** Profile pictures one poll may look up: two Graph calls each, so a first pass over many people is spread out. */
    static final int AVATARS_PER_POLL=3;
    /** Where {@link InstagramAvatars} serves a cached picture; the provider's own URL never leaves the server. */
    static final String AVATAR_PATH="/api/crm/instagram/avatars/";
    private final InstagramClient client;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final InboxRepository inboxes;private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;private final ContactIdentityRepository identities;
    private final CrmContactService contacts;private final CrmWorkspaceService workspace;
    private final CrmChannelsProperties.Instagram settings;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"crm-instagram");t.setDaemon(true);return t;});
    private volatile boolean ready;private volatile String failure="";private volatile Instant retryAt=Instant.EPOCH;private int failures;
    private Clock clock=Clock.systemUTC();
    private final su.onno.crm.service.CrmConversationStatuses statuses;
    private final su.onno.web.UiMessages uiMessages;
    public su.onno.crm.service.CrmChannelDefinition definition() { return new su.onno.crm.service.CrmChannelDefinition("INSTAGRAM","Instagram","/crm/channels/instagram.png"); }
    public InstagramBridge(InstagramClient client,JdbcTemplate jdbc,PlatformTransactionManager transactions,InboxRepository inboxes,
        ConversationRepository conversations,ConversationMessageRepository messages,
        ContactIdentityRepository identities,CrmContactService contacts,CrmWorkspaceService workspace, su.onno.crm.service.CrmConversationStatuses statuses,
        su.onno.web.UiMessages uiMessages,CrmChannelsProperties properties) {
        this.statuses=statuses;this.uiMessages=uiMessages;this.settings=properties.getInstagram();
        this.client=client;this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.inboxes=inboxes;
        this.conversations=conversations;this.messages=messages;this.identities=identities;this.contacts=contacts;this.workspace=workspace;
    }
    /** Test hook: the clock behind cutoffs, watermarks and backoff. */
    void clock(Clock value){this.clock=value;}
    private record Account(String id,String scoped,String username,UUID inbox,boolean active,String cursor){}
    /** Persisted synchronization state; a non-null {@code passHead} means a poll pass is still in progress at {@code passCursor} (null = list head). */
    record SyncState(Instant cutoff,Instant checkpoint,Instant passHead,String passCursor){}
    private record ThreadMark(Instant updated,Instant fetchedAt){}
    private Account account(){var a=jdbc.query("SELECT account_id,scoped_id,username,inbox_id,active,cursor FROM onno_crm_ig_account",(r,n)->new Account(r.getString(1),r.getString(2),r.getString(3),r.getObject(4,UUID.class),r.getBoolean(5),r.getString(6)));return a.isEmpty()?null:a.getFirst();}
    @EventListener(ApplicationReadyEvent.class) public void start(){
        initialize();
        ready=true;worker.scheduleWithFixedDelay(this::tick,2,15,TimeUnit.SECONDS);
    }
    /** Creates the additive adapter tables, recovers uncertain sends and seeds a configured cutoff. */
    void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_account (account_id VARCHAR(100) PRIMARY KEY,scoped_id VARCHAR(100),username VARCHAR(200),inbox_id UUID NOT NULL,active BOOLEAN NOT NULL,cursor VARCHAR(4000))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_peer (account_id VARCHAR(100),peer_id VARCHAR(100),conversation_id UUID NOT NULL,last_inbound TIMESTAMP,PRIMARY KEY(account_id,peer_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_seen (account_id VARCHAR(100),external_id VARCHAR(2048),PRIMARY KEY(account_id,external_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_outbox (message_id UUID PRIMARY KEY,account_id VARCHAR(100),peer_id VARCHAR(100),state VARCHAR(20))");
        // Times are ISO-8601 UTC text (Instant#toString), so neither the JVM nor the database time zone can shift them.
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_sync (account_id VARCHAR(100) PRIMARY KEY,activation_cutoff VARCHAR(40) NOT NULL,checkpoint VARCHAR(40),pass_head VARCHAR(40),pass_cursor VARCHAR(4000))");
        // One row per person the account talks to: their cached profile picture (base64, empty when Instagram gave
        // none) and when it was last checked (ISO-8601 UTC, whole seconds, so the text compares in time order).
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_avatar (account_id VARCHAR(100),peer_id VARCHAR(100),identity_id UUID NOT NULL,content_type VARCHAR(100),content TEXT,checked_at VARCHAR(40) NOT NULL,PRIMARY KEY(account_id,peer_id))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS onno_crm_ig_avatar_identity ON onno_crm_ig_avatar (identity_id)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_thread (account_id VARCHAR(100),thread_id VARCHAR(512),updated_time VARCHAR(40) NOT NULL,fetched_at VARCHAR(40) NOT NULL,PRIMARY KEY(account_id,thread_id))");
        tx.executeWithoutResult(s->{for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_ig_outbox WHERE state='SENDING'",(r,n)->r.getObject(1,UUID.class)))finish(id,false,null);});
        var a=account();
        if(a!=null){
            var state=syncOrNull(a.id);
            if(state==null&&settings.getActivationCutoff()!=null)ensureCutoff(a.id,"configured");
            else if(state!=null&&settings.getActivationCutoff()!=null&&!state.cutoff().equals(settings.getActivationCutoff()))
                log.warn("Instagram account {} keeps its persisted activation cutoff {}; onno.crm.channels.instagram.activation-cutoff={} only seeds an account without one",a.id,state.cutoff(),settings.getActivationCutoff());
        }
    }
    public String key(){return "instagram";}
    public View view(){var a=ready?account():null;Instant cutoff=null;try{var s=a==null?null:syncOrNull(a.id);cutoff=s==null?null:s.cutoff();}catch(RuntimeException ignored){}
        return new View(key(),"Instagram","INSTAGRAM",!client.configured()?"UNAVAILABLE":!failure.isBlank()?"ERROR":a==null?"DISCONNECTED":a.active?"CONNECTED":"PAUSED",a==null?"":"@"+a.username,
        "Checks provider-visible conversations every 15 seconds and imports only messages sent "+(cutoff==null?"after synchronization is enabled":"from "+cutoff)
            +". Meta test-mode restrictions apply; live webhooks are not configured.",a==null?List.of("check"):List.of("check",a.active?"pause":"resume"));}
    private void connect(){var p=client.profile();var old=account();if(old!=null&&!old.id.equals(p.userId()))throw new IllegalArgumentException("Reconnect the original Instagram account");
        tx.executeWithoutResult(s->{workspace.lock();if(old==null){var i=new Inbox();i.setChannel(Channel.INSTAGRAM);i.setDescription("Instagram · @"+p.username());i.setAddress(p.username());inboxes.save(i);
            jdbc.update("INSERT INTO onno_crm_ig_account VALUES (?,?,?,?,TRUE,NULL)",p.userId(),p.id(),p.username(),i.getId());
            ensureCutoff(p.userId(),"first connection");}});
    }
    public synchronized void command(String action,String credential){if(!ready)throw new IllegalArgumentException("Instagram is starting");
        if(action.equals("check")){connect();retryAt=Instant.EPOCH;failures=0;failure="";return;}
        if(!Set.of("pause","resume").contains(action))throw new IllegalArgumentException("Unsupported Instagram action");
        var a=account();if(a==null)throw new IllegalArgumentException("Connect Instagram first");if(action.equals("resume"))connect();boolean active=action.equals("resume");
        tx.executeWithoutResult(s->{workspace.lock();if(active)ensureCutoff(a.id,"resumed");jdbc.update("UPDATE onno_crm_ig_account SET active=? WHERE account_id=?",active,a.id);inboxes.findActiveById(a.inbox).ifPresent(i->{i.setActive(active);inboxes.save(i);});});failure="";failures=0;retryAt=Instant.EPOCH;
    }
    public boolean supports(Conversation conversation) { return Channel.INSTAGRAM.equals(conversation.getChannel()); }

    public Connection connection(Conversation c){if(!ready||!Channel.INSTAGRAM.equals(c.getChannel())||c.getInbox()==null)return new Connection(false,"Instagram is not connected",1000);var a=account();
        if(a==null||!a.active||!a.inbox.equals(c.getInbox().id())||!client.configured()||!failure.isBlank())return new Connection(false,"Instagram is paused or unavailable",1000);
        var times=jdbc.query("SELECT last_inbound FROM onno_crm_ig_peer WHERE account_id=? AND conversation_id=?",(r,n)->r.getTimestamp(1)==null?null:r.getTimestamp(1).toLocalDateTime(),a.id,c.getId());
        boolean open=times.stream().anyMatch(t->t!=null&&t.atZone(ZoneId.systemDefault()).toInstant().isAfter(Instant.now().minus(Duration.ofHours(24))));
        // Instagram takes an attachment only as a URL it can fetch itself, and this application's
        // media sits behind sign-in. Rather than publish it, the composer says so and offers no clip.
        return new Connection(true,"Instagram",1000,open?ReplyCapability.AVAILABLE:ReplyCapability.WINDOW_CLOSED,open?"":"Instagram's 24-hour reply window has expired")
            .withAttachments(0,uiMessages.get("crm.channel.instagram.noFiles"));
    }
    public void enqueue(Conversation c,ConversationMessage m){if(!connection(c).canSend())throw new IllegalArgumentException(connection(c).unavailableReason());var a=account();
        String peer=jdbc.queryForObject("SELECT peer_id FROM onno_crm_ig_peer WHERE account_id=? AND conversation_id=? ORDER BY last_inbound DESC NULLS LAST FETCH FIRST 1 ROW ONLY",String.class,a.id,c.getId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_ig_outbox WHERE message_id=?",Integer.class,m.getId())==0)jdbc.update("INSERT INTO onno_crm_ig_outbox VALUES (?,?,?,'QUEUED')",m.getId(),a.id,peer);
        else if(jdbc.update("UPDATE onno_crm_ig_outbox SET state='QUEUED' WHERE message_id=? AND state='FAILED'",m.getId())!=1)throw new IllegalArgumentException("Message is already queued or sent");
    }

    // ---- synchronization state ----

    private static final class InvalidCutoff extends RuntimeException{InvalidCutoff(){super("invalid Instagram activation cutoff");}}
    SyncState syncOrNull(String account){
        var rows=jdbc.query("SELECT activation_cutoff,checkpoint,pass_head,pass_cursor FROM onno_crm_ig_sync WHERE account_id=?",
                (r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)},account);
        if(rows.isEmpty())return null;var row=rows.getFirst();
        try{return new SyncState(Instant.parse(row[0].trim()),row[1]==null?null:Instant.parse(row[1]),row[2]==null?null:Instant.parse(row[2]),row[3]);}
        catch(DateTimeParseException|NullPointerException e){throw new InvalidCutoff();}
    }
    /**
     * Persists the account's activation cutoff unless one already exists; an existing cutoff is never
     * changed. The configured {@code activation-cutoff} wins over "now" for the first value.
     */
    Instant ensureCutoff(String account,String reason){
        var existing=syncOrNull(account);if(existing!=null)return existing.cutoff();
        Instant configured=settings.getActivationCutoff();Instant cutoff=configured!=null?configured:clock.instant();
        jdbc.update("INSERT INTO onno_crm_ig_sync (account_id,activation_cutoff) VALUES (?,?)",account,cutoff.toString());
        log.info("Instagram account {} activation cutoff persisted as {} ({}); earlier messages are never imported",account,cutoff,configured!=null?"configured":reason);
        return cutoff;
    }
    private ThreadMark mark(String account,String thread){
        var rows=jdbc.query("SELECT updated_time,fetched_at FROM onno_crm_ig_thread WHERE account_id=? AND thread_id=?",(r,n)->new ThreadMark(Instant.parse(r.getString(1)),Instant.parse(r.getString(2))),account,thread);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void saveMark(String account,String thread,Instant updated,Instant fetchedAt){
        if(jdbc.update("UPDATE onno_crm_ig_thread SET updated_time=?,fetched_at=? WHERE account_id=? AND thread_id=?",updated.toString(),fetchedAt.toString(),account,thread)==0)
            jdbc.update("INSERT INTO onno_crm_ig_thread (account_id,thread_id,updated_time,fetched_at) VALUES (?,?,?,?)",account,thread,updated.toString(),fetchedAt.toString());
    }
    private void savePass(String account,Instant head,String cursor){
        jdbc.update("UPDATE onno_crm_ig_sync SET pass_head=?,pass_cursor=? WHERE account_id=?",head.toString(),cursor,account);
    }
    private void finishPass(String account,SyncState state,Instant head){
        Instant checkpoint=state.checkpoint()==null||head.isAfter(state.checkpoint())?head:state.checkpoint();
        jdbc.update("UPDATE onno_crm_ig_sync SET checkpoint=?,pass_head=NULL,pass_cursor=NULL WHERE account_id=?",checkpoint.toString(),account);
    }

    // ---- polling ----

    synchronized void tick(){if(!ready||!client.configured()||clock.instant().isBefore(retryAt))return;
        try{if(account()==null)connect();var a=account();if(!a.active)return;failure="";
            ensureCutoff(a.id,"first poll");deliver(a);poll(a);avatars(a);failures=0;
        }catch(InstagramClient.ApiFailure e){
            failures++;boolean limited=e.rateLimited();
            long delay=Math.max(e.retrySeconds,backoff(limited?300:60,limited?3600:900));
            failure=limited?"Instagram rate limit reached; synchronization resumes automatically"
                    :e.accessDenied()?"Instagram access failed; check permissions or generate a new token":"Instagram synchronization deferred";
            retryAt=clock.instant().plusSeconds(delay);
            log.warn("Instagram synchronization deferred {}s: HTTP {}, Graph error code {} subcode {}{}",delay,e.status,e.code,e.subcode,limited?" (rate limited)":"");
        }catch(InvalidCutoff e){
            failures++;failure="Instagram activation cutoff is invalid; store an ISO-8601 UTC instant in onno_crm_ig_sync";retryAt=clock.instant().plusSeconds(backoff(60,900));
            log.warn("Instagram synchronization stopped: the persisted activation cutoff is not an ISO-8601 instant");
        }catch(Exception e){
            failures++;long delay=backoff(60,900);failure="Instagram synchronization failed; check connection";retryAt=clock.instant().plusSeconds(delay);
            // Only the exception type: messages can carry SQL parameters or provider text.
            log.warn("Instagram synchronization failed ({}); retrying in {}s",e.getClass().getSimpleName(),delay);
        }
    }
    private long backoff(long base,long cap){return Math.min(cap,base<<Math.min(Math.max(failures-1,0),6));}
    private static final class Budget{int pages;int threads;Budget(int pages,int threads){this.pages=pages;this.threads=threads;}}
    /** Outcome of one conversation page: every eligible thread done, a thread older than the floor seen, newest updated_time. */
    private record Scan(boolean complete,boolean reachedFloor,Instant newest){}

    private void poll(Account a){
        var state=syncOrNull(a.id);
        Instant floor=state.checkpoint()==null?state.cutoff():max(state.cutoff(),state.checkpoint().minus(OVERLAP));
        var budget=new Budget(settings.getConversationPagesPerPoll(),settings.getThreadsPerPoll());
        // The newest conversations come first on every poll, so a backlog can never delay a fresh DM.
        var head=client.conversations(a.id,null);budget.pages--;
        var scan=scan(a,state.cutoff(),floor,head,budget);
        Instant passHead=state.passHead()!=null?state.passHead():scan.newest()!=null?scan.newest():state.checkpoint()!=null?state.checkpoint():state.cutoff();
        String cursor;
        if(state.passHead()==null){
            if(!scan.complete()){savePass(a.id,passHead,null);return;}
            if(scan.reachedFloor()||head.after().isBlank()){finishPass(a.id,state,passHead);return;}
            cursor=head.after();
        }else{
            // Newest-first: when the head alone reaches the floor, nothing at or above it is left beyond the head.
            if(scan.complete()&&(scan.reachedFloor()||head.after().isBlank())){finishPass(a.id,state,max(passHead,scan.newest()));return;}
            // A pass still at the head (passCursor null) moves past it only once every head thread is done.
            if(state.passCursor()==null&&!scan.complete()){savePass(a.id,passHead,null);return;}
            cursor=state.passCursor()==null?head.after():state.passCursor();
        }
        var cursors=new HashSet<String>();
        while(budget.pages>0&&budget.threads>0){
            if(!cursors.add(cursor))throw new IllegalStateException("Repeated Instagram cursor");
            var page=client.conversations(a.id,cursor);budget.pages--;
            var part=scan(a,state.cutoff(),floor,page,budget);
            if(!part.complete()){savePass(a.id,passHead,cursor);return;}
            if(part.reachedFloor()||page.after().isBlank()){finishPass(a.id,state,passHead);return;}
            cursor=page.after();
        }
        savePass(a.id,passHead,cursor);
    }
    private Scan scan(Account a,Instant cutoff,Instant floor,InstagramClient.Page page,Budget budget){
        boolean complete=true,reached=false;Instant newest=null;
        for(var thread:page.data()){
            String id=InstagramClient.required(thread,"id");Instant updated=parseInstant(InstagramClient.required(thread,"updated_time"));
            newest=max(newest,updated);
            if(updated.isBefore(floor)){reached=true;continue;}
            var mark=mark(a.id,id);
            if(mark!=null&&!mark.updated().isBefore(updated)&&!mark.fetchedAt().isBefore(updated.plus(SETTLE)))continue;
            if(budget.threads<=0){complete=false;continue;}
            budget.threads--;Instant fetchedAt=clock.instant();
            importRecent(a,cutoff,id,mark,client.messages(id));
            // Each message above committed in its own transaction; only now may the thread's watermark move.
            saveMark(a.id,id,mark==null?updated:max(mark.updated(),updated),fetchedAt);
        }
        return new Scan(complete,reached,newest);
    }
    private void importRecent(Account a,Instant cutoff,String thread,ThreadMark mark,InstagramClient.Page mail){
        record Item(Instant sent,int order,JsonNode raw){}
        var items=new ArrayList<Item>();int order=0;Instant oldest=null;
        for(var m:mail.data()){Instant sent=parseInstant(InstagramClient.required(m,"created_time"));oldest=oldest==null||sent.isBefore(oldest)?sent:oldest;
            // Message-level cutoff: a new DM in an old conversation never brings that conversation's history with it.
            if(sent.isBefore(cutoff))continue;
            String external=InstagramClient.required(m,"id");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_ig_seen WHERE account_id=? AND external_id=?",Integer.class,a.id,external)>0)continue;
            items.add(new Item(sent,order++,m));}
        if(!mail.after().isBlank()&&oldest!=null&&!oldest.isBefore(cutoff)&&(mark==null||oldest.isAfter(mark.updated())))
            log.warn("Instagram conversation had more than {} new messages since the last poll; Instagram details only the most recent {}",InstagramClient.MESSAGE_PAGE_SIZE,InstagramClient.MESSAGE_PAGE_SIZE);
        // The provider lists newest first; import oldest first so conversation previews end on the latest message.
        items.sort(Comparator.comparing(Item::sent).thenComparing(Item::order,Comparator.reverseOrder()));
        for(var item:items)ingest(a,item.raw());
    }
    private static Instant max(Instant a,Instant b){return a==null?b:b==null?a:a.isAfter(b)?a:b;}

    private boolean own(Account a,String id){return a.id.equals(id)||a.scoped.equals(id);}
    private void ingest(Account a,JsonNode raw){
        String external=InstagramClient.required(raw,"id"),sender=InstagramClient.required(raw.path("from"),"id");boolean outbound=own(a,sender);
        JsonNode peer=raw.path("from");if(outbound){peer=null;for(var to:raw.path("to").path("data"))if(!own(a,to.path("id").asText())){if(peer!=null)return;peer=to;}if(peer==null)return;}
        String peerId=InstagramClient.required(peer,"id"),name=peer.path("username").asText(peerId);String body=raw.path("message").asText();if(body.isBlank())body="[Instagram attachment or unavailable message]";
        String text=cut(body,8000);LocalDateTime sent=parseTime(InstagramClient.required(raw,"created_time"));
        tx.executeWithoutResult(s->{workspace.lock();if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_ig_seen WHERE account_id=? AND external_id=?",Integer.class,a.id,external)>0)return;
            var ids=jdbc.query("SELECT conversation_id FROM onno_crm_ig_peer WHERE account_id=? AND peer_id=?",(r,n)->r.getObject(1,UUID.class),a.id,peerId);Conversation c;
            if(ids.isEmpty()){
                UUID identityId=UUID.nameUUIDFromBytes(("INSTAGRAM\n"+a.inbox+"\n"+peerId).getBytes(StandardCharsets.UTF_8));var identity=identities.findActiveById(identityId).orElse(null);
                UUID customer=contacts.resolveIncoming(new CrmCustomerBinding.IncomingContact(Channel.INSTAGRAM,
                        a.inbox.toString(),peerId,cut(name,200),null,null,null,"INSTAGRAM"));
                contacts.link(customer,Channel.INSTAGRAM,a.inbox.toString(),peerId,name,true);
                c=new Conversation();c.setCustomer(customer);c.setInbox(Ref.of(Inbox.class,a.inbox));c.setChannel(Channel.INSTAGRAM);c.setSubject("Instagram · "+cut(name,200));c.setDescription(c.getSubject());c.setLastMessageAt(sent);conversations.save(c);
                jdbc.update("INSERT INTO onno_crm_ig_peer VALUES (?,?,?,NULL)",a.id,peerId,c.getId());
            }else c=conversations.findActiveById(ids.getFirst()).orElse(null);
            if(c!=null){c.setCustomer(contacts.canonical(c.getCustomer()));var m=new ConversationMessage();m.setConversation(Ref.of(Conversation.class,c.getId()));m.setChannel(Channel.INSTAGRAM);
                m.setKind(outbound?MessageKind.AGENT_REPLY:MessageKind.CUSTOMER_MESSAGE);m.setDirection(outbound?MessageDirection.OUTBOUND:MessageDirection.INBOUND);m.setAuthorName(cut(outbound?a.username:name,200));m.setBody(text);m.setDescription(cut(text,100));m.setSentAt(sent);m.setDeliveryStatus(outbound?DeliveryStatus.SENT:DeliveryStatus.RECEIVED);m.setExternalMessageId(externalKey(external));messages.save(m);
                if(!outbound){jdbc.update("UPDATE onno_crm_ig_peer SET last_inbound=? WHERE account_id=? AND peer_id=? AND (last_inbound IS NULL OR last_inbound<?)",sent,a.id,peerId,sent);c.setUnreadCount(c.getUnreadCount()+1);c.setStatus(statuses.incoming());}
                if(c.getLastMessageAt()==null||!sent.isBefore(c.getLastMessageAt())){c.setLastMessageAt(sent);c.setLastMessagePreview(cut(text,180));}conversations.save(c);}
            jdbc.update("INSERT INTO onno_crm_ig_seen VALUES (?,?)",a.id,external);
        });
    }
    // ---- profile pictures ----

    /**
     * Downloads the profile pictures of up to {@link #AVATARS_PER_POLL} people: first those never
     * checked, then the longest unchecked, the most recent writers first, each at most once per
     * {@link #AVATAR_REFRESH}. A picture is kept until Instagram answers again; only a clear answer
     * (a new picture, or none) replaces it. Rate limits and a rejected token stop the poll as usual.
     */
    void avatars(Account a){
        String due=seconds(clock.instant().minus(AVATAR_REFRESH));
        var peers=jdbc.query("SELECT p.peer_id FROM onno_crm_ig_peer p LEFT JOIN onno_crm_ig_avatar v ON v.account_id=p.account_id AND v.peer_id=p.peer_id"
                +" WHERE p.account_id=? AND (v.checked_at IS NULL OR v.checked_at<?) ORDER BY v.checked_at NULLS FIRST,p.last_inbound DESC NULLS LAST,p.peer_id"
                +" FETCH FIRST "+AVATARS_PER_POLL+" ROWS ONLY",(r,n)->r.getString(1),a.id,due);
        for(String peer:peers){
            UUID identity=identityId(a,peer);boolean known=identities.findActiveById(identity).isPresent();
            boolean answered=false;InstagramClient.Image image=null;
            if(known)try{String url=client.profilePicture(peer);image=url==null?null:client.picture(url);answered=url==null||image!=null;}
            catch(InstagramClient.ApiFailure e){if(e.rateLimited()||e.code==190)throw e;}
            catch(RuntimeException e){log.debug("Instagram profile picture deferred ({})",e.getClass().getSimpleName());}
            var found=image;boolean replace=answered;String checked=seconds(clock.instant());
            tx.executeWithoutResult(s->{workspace.lock();
                String content=found==null?"":Base64.getEncoder().encodeToString(found.content()),type=found==null?null:found.contentType();
                boolean exists=jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_ig_avatar WHERE account_id=? AND peer_id=?",Integer.class,a.id,peer)>0;
                if(!exists)jdbc.update("INSERT INTO onno_crm_ig_avatar (account_id,peer_id,identity_id,content_type,content,checked_at) VALUES (?,?,?,?,?,?)",a.id,peer,identity,type,content,checked);
                else if(replace)jdbc.update("UPDATE onno_crm_ig_avatar SET identity_id=?,content_type=?,content=?,checked_at=? WHERE account_id=? AND peer_id=?",identity,type,content,checked,a.id,peer);
                else jdbc.update("UPDATE onno_crm_ig_avatar SET checked_at=? WHERE account_id=? AND peer_id=?",checked,a.id,peer);
                if(known&&replace)contacts.setIdentityAvatar(identity,found==null?null:AVATAR_PATH+identity);
            });
        }
    }
    /** The contact identity {@link CrmContactService#link} gives this person in the account's inbox. */
    private static UUID identityId(Account a,String peer){return UUID.nameUUIDFromBytes(("INSTAGRAM\n"+a.inbox+"\n"+peer.trim()).getBytes(StandardCharsets.UTF_8));}
    private static String seconds(Instant at){return at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();}

    private void deliver(Account a){for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_ig_outbox WHERE account_id=? AND state='QUEUED'",(r,n)->r.getObject(1,UUID.class),a.id)){
        if(jdbc.update("UPDATE onno_crm_ig_outbox SET state='SENDING' WHERE message_id=? AND state='QUEUED'",id)!=1)continue;
        try{var m=messages.findActiveById(id).orElseThrow();var c=conversations.findActiveById(m.getConversation().id()).orElseThrow();if(!connection(c).canSend())throw new IllegalStateException();
            String peer=jdbc.queryForObject("SELECT peer_id FROM onno_crm_ig_outbox WHERE message_id=?",String.class,id);
            var time=jdbc.queryForObject("SELECT last_inbound FROM onno_crm_ig_peer WHERE account_id=? AND peer_id=?",java.sql.Timestamp.class,a.id,peer);
            if(time==null||time.toInstant().isBefore(Instant.now().minus(Duration.ofHours(24))))throw new IllegalStateException();
            String external=client.send(a.id,peer,m.getBody());tx.executeWithoutResult(s->{finish(id,true,external);jdbc.update("INSERT INTO onno_crm_ig_seen VALUES (?,?)",a.id,external);});
        }catch(Exception e){tx.executeWithoutResult(s->finish(id,false,null));}
    }}
    private void finish(UUID id,boolean sent,String external){messages.findActiveById(id).ifPresent(m->{m.setDeliveryStatus(sent?DeliveryStatus.SENT:DeliveryStatus.FAILED);if(external!=null)m.setExternalMessageId(externalKey(external));messages.save(m);});jdbc.update("UPDATE onno_crm_ig_outbox SET state=? WHERE message_id=?",sent?"SENT":"FAILED",id);}
    static Instant parseInstant(String value){
        try{return OffsetDateTime.parse(value).toInstant();}catch(DateTimeParseException e){return OffsetDateTime.parse(value,DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant();}
    }
    static LocalDateTime parseTime(String value){return LocalDateTime.ofInstant(parseInstant(value),ZoneId.systemDefault());}
    private static String externalKey(String id){return "instagram:"+UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));}
    private static String cut(String text,int max){return text.substring(0,Math.min(text.length(),max));}
    @PreDestroy public void stop(){ready=false;worker.shutdownNow();}
}
