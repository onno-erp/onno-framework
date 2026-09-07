package su.onno.crm.channels.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
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

/** Single-account local adapter. Polls provider-visible messages; webhook hosting is separate. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(InstagramBridge.class)
@Component
@ConditionalOnProperty(name="onno.crm.channels.instagram.enabled",havingValue="true")
public class InstagramBridge implements CrmChannelConnection,CrmMessageTransport {
    private final InstagramClient client;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final InboxRepository inboxes;private final CustomerRepository customers;private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;private final ContactIdentityRepository identities;
    private final CrmContactService contacts;private final CrmWorkspaceService workspace;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"crm-instagram");t.setDaemon(true);return t;});
    private volatile boolean ready;private volatile String failure="";private Instant retryAt=Instant.EPOCH;
    private final su.onno.crm.service.CrmConversationStatuses statuses;
    public InstagramBridge(InstagramClient client,JdbcTemplate jdbc,PlatformTransactionManager transactions,InboxRepository inboxes,
        CustomerRepository customers,ConversationRepository conversations,ConversationMessageRepository messages,
        ContactIdentityRepository identities,CrmContactService contacts,CrmWorkspaceService workspace, su.onno.crm.service.CrmConversationStatuses statuses) {
        this.statuses=statuses;
        this.client=client;this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.inboxes=inboxes;this.customers=customers;
        this.conversations=conversations;this.messages=messages;this.identities=identities;this.contacts=contacts;this.workspace=workspace;
    }
    private record Account(String id,String scoped,String username,UUID inbox,boolean active,String cursor){}
    private Account account(){var a=jdbc.query("SELECT account_id,scoped_id,username,inbox_id,active,cursor FROM onno_crm_ig_account",(r,n)->new Account(r.getString(1),r.getString(2),r.getString(3),r.getObject(4,UUID.class),r.getBoolean(5),r.getString(6)));return a.isEmpty()?null:a.getFirst();}
    @EventListener(ApplicationReadyEvent.class) public void start(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_account (account_id VARCHAR(100) PRIMARY KEY,scoped_id VARCHAR(100),username VARCHAR(200),inbox_id UUID NOT NULL,active BOOLEAN NOT NULL,cursor VARCHAR(4000))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_peer (account_id VARCHAR(100),peer_id VARCHAR(100),conversation_id UUID NOT NULL,last_inbound TIMESTAMP,PRIMARY KEY(account_id,peer_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_seen (account_id VARCHAR(100),external_id VARCHAR(2048),PRIMARY KEY(account_id,external_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_ig_outbox (message_id UUID PRIMARY KEY,account_id VARCHAR(100),peer_id VARCHAR(100),state VARCHAR(20))");
        tx.executeWithoutResult(s->{for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_ig_outbox WHERE state='SENDING'",(r,n)->r.getObject(1,UUID.class)))finish(id,false,null);});
        ready=true;worker.scheduleWithFixedDelay(this::tick,2,15,TimeUnit.SECONDS);
    }
    public String key(){return "instagram";}
    public View view(){var a=ready?account():null;return new View(key(),"Instagram","INSTAGRAM",!client.configured()?"UNAVAILABLE":!failure.isBlank()?"ERROR":a==null?"DISCONNECTED":a.active?"CONNECTED":"PAUSED",a==null?"":"@"+a.username,
        "Checks provider-visible conversations every 15 seconds. Meta test-mode restrictions apply; live webhooks are not configured.",a==null?List.of("check"):List.of("check",a.active?"pause":"resume"));}
    private void connect(){var p=client.profile();var old=account();if(old!=null&&!old.id.equals(p.userId()))throw new IllegalArgumentException("Reconnect the original Instagram account");
        tx.executeWithoutResult(s->{workspace.lock();if(old==null){var i=new Inbox();i.setChannel(Channel.INSTAGRAM);i.setDescription("Instagram · @"+p.username());i.setAddress(p.username());inboxes.save(i);
            jdbc.update("INSERT INTO onno_crm_ig_account VALUES (?,?,?,?,TRUE,NULL)",p.userId(),p.id(),p.username(),i.getId());}});
    }
    public synchronized void command(String action,String credential){if(!ready)throw new IllegalArgumentException("Instagram is starting");
        if(action.equals("check")){connect();retryAt=Instant.EPOCH;failure="";return;}
        if(!Set.of("pause","resume").contains(action))throw new IllegalArgumentException("Unsupported Instagram action");
        var a=account();if(a==null)throw new IllegalArgumentException("Connect Instagram first");if(action.equals("resume"))connect();boolean active=action.equals("resume");
        tx.executeWithoutResult(s->{workspace.lock();jdbc.update("UPDATE onno_crm_ig_account SET active=? WHERE account_id=?",active,a.id);inboxes.findActiveById(a.inbox).ifPresent(i->{i.setActive(active);inboxes.save(i);});});failure="";retryAt=Instant.EPOCH;
    }
    public Connection connection(Conversation c){if(!ready||c.getChannel()!=Channel.INSTAGRAM||c.getInbox()==null)return new Connection(false,"Instagram is not connected",1000);var a=account();
        if(a==null||!a.active||!a.inbox.equals(c.getInbox().id())||!client.configured()||!failure.isBlank())return new Connection(false,"Instagram is paused or unavailable",1000);
        var times=jdbc.query("SELECT last_inbound FROM onno_crm_ig_peer WHERE account_id=? AND conversation_id=?",(r,n)->r.getTimestamp(1)==null?null:r.getTimestamp(1).toLocalDateTime(),a.id,c.getId());
        boolean open=times.stream().anyMatch(t->t!=null&&t.atZone(ZoneId.systemDefault()).toInstant().isAfter(Instant.now().minus(Duration.ofHours(24))));
        return new Connection(open,open?"Instagram":"Instagram's 24-hour reply window has expired",1000);
    }
    public void enqueue(Conversation c,ConversationMessage m){if(!connection(c).connected())throw new IllegalArgumentException(connection(c).label());var a=account();
        String peer=jdbc.queryForObject("SELECT peer_id FROM onno_crm_ig_peer WHERE account_id=? AND conversation_id=? ORDER BY last_inbound DESC NULLS LAST FETCH FIRST 1 ROW ONLY",String.class,a.id,c.getId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_ig_outbox WHERE message_id=?",Integer.class,m.getId())==0)jdbc.update("INSERT INTO onno_crm_ig_outbox VALUES (?,?,?,'QUEUED')",m.getId(),a.id,peer);
        else if(jdbc.update("UPDATE onno_crm_ig_outbox SET state='QUEUED' WHERE message_id=? AND state='FAILED'",m.getId())!=1)throw new IllegalArgumentException("Message is already queued or sent");
    }
    synchronized void tick(){if(!ready||!client.configured()||Instant.now().isBefore(retryAt))return;
        try{if(account()==null)connect();var a=account();if(!a.active)return;failure="";deliver(a);
            // Save a continuation only after all messages in this page commit. Empty pages can still have a cursor.
            var page=client.conversations(a.id,a.cursor);for(var thread:page.data()){String after="";Set<String> seen=new HashSet<>();int pages=0;
                do{var mail=client.messages(InstagramClient.required(thread,"id"),after);var ordered=new ArrayList<JsonNode>();mail.data().forEach(ordered::add);Collections.reverse(ordered);for(var m:ordered)ingest(a,m);
                    after=mail.after();if(!after.isBlank()&&!seen.add(after))throw new IllegalStateException("Repeated Instagram cursor");if(++pages>100)throw new IllegalStateException("Instagram conversation exceeds sync limit");
                }while(!after.isBlank());}
            jdbc.update("UPDATE onno_crm_ig_account SET cursor=? WHERE account_id=?",page.after().isBlank()?null:page.after(),a.id);
        }catch(InstagramClient.ApiFailure e){failure=e.status==401||e.status==400?"Instagram access failed; check permissions or generate a new token":"Instagram synchronization deferred";retryAt=Instant.now().plusSeconds(Math.max(60,e.retrySeconds));}
        catch(Exception e){failure="Instagram synchronization failed; check connection";retryAt=Instant.now().plusSeconds(60);}
    }
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
                Customer customer=identity==null?null:customers.findActiveById(contacts.canonical(identity.getCustomer().id())).orElse(null);
                if(customer==null){customer=new Customer();customer.setDescription(cut(name,200));customer.setSource("Instagram @"+a.username);customers.save(customer);}
                contacts.link(customer.getId(),Channel.INSTAGRAM,a.inbox.toString(),peerId,name,true);
                c=new Conversation();c.setCustomer(Ref.of(Customer.class,customer.getId()));c.setInbox(Ref.of(Inbox.class,a.inbox));c.setChannel(Channel.INSTAGRAM);c.setSubject("Instagram · "+cut(name,200));c.setDescription(c.getSubject());c.setLastMessageAt(sent);conversations.save(c);
                jdbc.update("INSERT INTO onno_crm_ig_peer VALUES (?,?,?,NULL)",a.id,peerId,c.getId());
            }else c=conversations.findActiveById(ids.getFirst()).orElse(null);
            if(c!=null){c.setCustomer(Ref.of(Customer.class,contacts.canonical(c.getCustomer().id())));var m=new ConversationMessage();m.setConversation(Ref.of(Conversation.class,c.getId()));m.setChannel(Channel.INSTAGRAM);
                m.setKind(outbound?MessageKind.AGENT_REPLY:MessageKind.CUSTOMER_MESSAGE);m.setDirection(outbound?MessageDirection.OUTBOUND:MessageDirection.INBOUND);m.setAuthorName(cut(outbound?a.username:name,200));m.setBody(text);m.setDescription(cut(text,100));m.setSentAt(sent);m.setDeliveryStatus(outbound?DeliveryStatus.SENT:DeliveryStatus.RECEIVED);m.setExternalMessageId(externalKey(external));messages.save(m);
                if(!outbound){jdbc.update("UPDATE onno_crm_ig_peer SET last_inbound=? WHERE account_id=? AND peer_id=? AND (last_inbound IS NULL OR last_inbound<?)",sent,a.id,peerId,sent);c.setUnreadCount(c.getUnreadCount()+1);c.setStatus(statuses.incoming());}
                if(c.getLastMessageAt()==null||!sent.isBefore(c.getLastMessageAt())){c.setLastMessageAt(sent);c.setLastMessagePreview(cut(text,180));}conversations.save(c);}
            jdbc.update("INSERT INTO onno_crm_ig_seen VALUES (?,?)",a.id,external);
        });
    }
    private void deliver(Account a){for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_ig_outbox WHERE account_id=? AND state='QUEUED'",(r,n)->r.getObject(1,UUID.class),a.id)){
        if(jdbc.update("UPDATE onno_crm_ig_outbox SET state='SENDING' WHERE message_id=? AND state='QUEUED'",id)!=1)continue;
        try{var m=messages.findActiveById(id).orElseThrow();var c=conversations.findActiveById(m.getConversation().id()).orElseThrow();if(!connection(c).connected())throw new IllegalStateException();
            String peer=jdbc.queryForObject("SELECT peer_id FROM onno_crm_ig_outbox WHERE message_id=?",String.class,id);
            var time=jdbc.queryForObject("SELECT last_inbound FROM onno_crm_ig_peer WHERE account_id=? AND peer_id=?",java.sql.Timestamp.class,a.id,peer);
            if(time==null||time.toInstant().isBefore(Instant.now().minus(Duration.ofHours(24))))throw new IllegalStateException();
            String external=client.send(a.id,peer,m.getBody());tx.executeWithoutResult(s->{finish(id,true,external);jdbc.update("INSERT INTO onno_crm_ig_seen VALUES (?,?)",a.id,external);});
        }catch(Exception e){tx.executeWithoutResult(s->finish(id,false,null));}
    }}
    private void finish(UUID id,boolean sent,String external){messages.findActiveById(id).ifPresent(m->{m.setDeliveryStatus(sent?DeliveryStatus.SENT:DeliveryStatus.FAILED);if(external!=null)m.setExternalMessageId(externalKey(external));messages.save(m);});jdbc.update("UPDATE onno_crm_ig_outbox SET state=? WHERE message_id=?",sent?"SENT":"FAILED",id);}
    static LocalDateTime parseTime(String value){
        OffsetDateTime time;
        try{time=OffsetDateTime.parse(value);}catch(java.time.format.DateTimeParseException e){time=OffsetDateTime.parse(value,DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"));}
        return time.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
    private static String externalKey(String id){return "instagram:"+UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));}
    private static String cut(String text,int max){return text.substring(0,Math.min(text.length(),max));}
    @PreDestroy public void stop(){ready=false;worker.shutdownNow();}
}
