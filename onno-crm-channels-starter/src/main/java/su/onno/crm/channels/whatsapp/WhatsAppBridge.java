package su.onno.crm.channels.whatsapp;

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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;

/** Single-number WhatsApp adapter with signed inbound events and transactional queued replies. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(WhatsAppBridge.class)
@Component
@ConditionalOnProperty(name="onno.crm.channels.whatsapp.enabled",havingValue="true")
public class WhatsAppBridge implements CrmChannelConnection,CrmMessageTransport {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(WhatsAppBridge.class);
    private final WhatsAppClient client;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final InboxRepository inboxes;private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;private final ContactIdentityRepository identities;
    private final CrmContactService contacts;private final CrmWorkspaceService workspace;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"crm-whatsapp");t.setDaemon(true);return t;});
    private volatile boolean ready;private volatile String failure="";private Instant retryAt=Instant.EPOCH;
    private final su.onno.crm.service.CrmConversationStatuses statuses;
    public su.onno.crm.service.CrmChannelDefinition definition() { return new su.onno.crm.service.CrmChannelDefinition("WHATSAPP","WhatsApp","/crm/channels/whatsapp.svg"); }
    public WhatsAppBridge(WhatsAppClient client,JdbcTemplate jdbc,PlatformTransactionManager transactions,InboxRepository inboxes,
        ConversationRepository conversations,ConversationMessageRepository messages,
        ContactIdentityRepository identities,CrmContactService contacts,CrmWorkspaceService workspace, su.onno.crm.service.CrmConversationStatuses statuses) {
        this.statuses=statuses;
        this.client=client;this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.inboxes=inboxes;
        this.conversations=conversations;this.messages=messages;this.identities=identities;this.contacts=contacts;this.workspace=workspace;
    }
    private record Account(String id,String scoped,String username,UUID inbox,boolean active,String cursor){}
    private Account account(){var a=jdbc.query("SELECT account_id,scoped_id,username,inbox_id,active,cursor FROM onno_crm_wa_account",(r,n)->new Account(r.getString(1),r.getString(2),r.getString(3),r.getObject(4,UUID.class),r.getBoolean(5),r.getString(6)));return a.isEmpty()?null:a.getFirst();}
    @EventListener(ApplicationReadyEvent.class) public void start(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_wa_account (account_id VARCHAR(100) PRIMARY KEY,scoped_id VARCHAR(100),username VARCHAR(200),inbox_id UUID NOT NULL,active BOOLEAN NOT NULL,cursor VARCHAR(4000))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_wa_peer (account_id VARCHAR(100),peer_id VARCHAR(100),conversation_id UUID NOT NULL,last_inbound TIMESTAMP,PRIMARY KEY(account_id,peer_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_wa_seen (account_id VARCHAR(100),external_id VARCHAR(2048),PRIMARY KEY(account_id,external_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_wa_outbox (message_id UUID PRIMARY KEY,account_id VARCHAR(100),peer_id VARCHAR(100),state VARCHAR(20),external_id VARCHAR(2048))");
        tx.executeWithoutResult(s->{for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_wa_outbox WHERE state='SENDING'",(r,n)->r.getObject(1,UUID.class)))finish(id,false,null);});
        ready=true;worker.scheduleWithFixedDelay(this::tick,2,15,TimeUnit.SECONDS);
    }
    public String key(){return "whatsapp";}
    public View view(){var a=ready?account():null;return new View(key(),"WhatsApp","WHATSAPP",!client.configured()?"UNAVAILABLE":!failure.isBlank()?"ERROR":a==null?"DISCONNECTED":a.active?"CONNECTED":"PAUSED",a==null?"":a.username,
        "WhatsApp Cloud API text replies and signed webhooks. A verified public webhook callback is required for incoming messages.",a==null?List.of("check"):List.of("check",a.active?"pause":"resume"));}
    private void connect(){var p=client.profile();var old=account();if(old!=null&&!old.id.equals(p.userId()))throw new IllegalArgumentException("Reconnect the original WhatsApp account");
        tx.executeWithoutResult(s->{workspace.lock();if(old==null){var i=new Inbox();i.setChannel(Channel.WHATSAPP);i.setDescription("WhatsApp · "+p.username());i.setAddress(p.username());inboxes.save(i);
            jdbc.update("INSERT INTO onno_crm_wa_account VALUES (?,?,?,?,TRUE,NULL)",p.userId(),p.id(),p.username(),i.getId());}});
    }
    public synchronized void command(String action,String credential){if(!ready)throw new IllegalArgumentException("WhatsApp is starting");
        if(action.equals("check")){connect();retryAt=Instant.EPOCH;failure="";return;}
        if(!Set.of("pause","resume").contains(action))throw new IllegalArgumentException("Unsupported WhatsApp action");
        var a=account();if(a==null)throw new IllegalArgumentException("Connect WhatsApp first");if(action.equals("resume"))connect();boolean active=action.equals("resume");
        tx.executeWithoutResult(s->{workspace.lock();jdbc.update("UPDATE onno_crm_wa_account SET active=? WHERE account_id=?",active,a.id);inboxes.findActiveById(a.inbox).ifPresent(i->{i.setActive(active);inboxes.save(i);});});failure="";retryAt=Instant.EPOCH;
    }
    public boolean supports(Conversation conversation) { return Channel.WHATSAPP.equals(conversation.getChannel()); }

    public Connection connection(Conversation c){if(!ready||!Channel.WHATSAPP.equals(c.getChannel())||c.getInbox()==null)return new Connection(false,"WhatsApp is not connected",4096);var a=account();
        if(a==null||!a.active||!a.inbox.equals(c.getInbox().id())||!client.configured()||!failure.isBlank())return new Connection(false,"WhatsApp is paused or unavailable",4096);
        var times=jdbc.query("SELECT last_inbound FROM onno_crm_wa_peer WHERE account_id=? AND conversation_id=?",(r,n)->r.getTimestamp(1)==null?null:r.getTimestamp(1).toLocalDateTime(),a.id,c.getId());
        boolean open=times.stream().anyMatch(t->t!=null&&t.atZone(ZoneId.systemDefault()).toInstant().isAfter(Instant.now().minus(Duration.ofHours(24))));
        return new Connection(true,"WhatsApp",4096,open?ReplyCapability.AVAILABLE:ReplyCapability.WINDOW_CLOSED,open?"":"WhatsApp's 24-hour reply window has expired");
    }
    public void enqueue(Conversation c,ConversationMessage m){if(!connection(c).canSend())throw new IllegalArgumentException(connection(c).unavailableReason());var a=account();
        String peer=jdbc.queryForObject("SELECT peer_id FROM onno_crm_wa_peer WHERE account_id=? AND conversation_id=? ORDER BY last_inbound DESC NULLS LAST FETCH FIRST 1 ROW ONLY",String.class,a.id,c.getId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_wa_outbox WHERE message_id=?",Integer.class,m.getId())==0)jdbc.update("INSERT INTO onno_crm_wa_outbox (message_id,account_id,peer_id,state) VALUES (?,?,?,'QUEUED')",m.getId(),a.id,peer);
        else if(jdbc.update("UPDATE onno_crm_wa_outbox SET state='QUEUED' WHERE message_id=? AND state='FAILED'",m.getId())!=1)throw new IllegalArgumentException("Message is already queued or sent");
        if(TransactionSynchronizationManager.isSynchronizationActive()){
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){wakeSender();}
            });
        }else wakeSender();
    }
    private void wakeSender(){
        try{worker.execute(this::tick);}
        catch(RejectedExecutionException ignored){/* Shutdown: the durable queue is recovered on restart. */}
    }
    synchronized void tick(){if(!ready||!client.configured()||Instant.now().isBefore(retryAt))return;
        try{if(account()==null)connect();var a=account();if(!a.active)return;failure="";deliver(a);
        }catch(WhatsAppClient.ApiFailure e){failure=e.status==401||e.status==400?"WhatsApp access failed; check permissions or generate a new token":"WhatsApp synchronization deferred";retryAt=Instant.now().plusSeconds(Math.max(60,e.retrySeconds));}
        catch(Exception e){failure="WhatsApp synchronization failed; check connection";retryAt=Instant.now().plusSeconds(60);}
    }
    public synchronized void receive(JsonNode payload){
        if(!ready||account()==null)throw new IllegalStateException("WhatsApp is starting");
        var a=account();
        if(!payload.path("object").asText().equals("whatsapp_business_account"))return;
        for(var entry:payload.path("entry")){
            if(!client.businessAccount().equals(entry.path("id").asText()))continue;
            for(var change:entry.path("changes")){
                if(!change.path("field").asText().equals("messages"))continue;
                var value=change.path("value");if(!a.id.equals(value.path("metadata").path("phone_number_id").asText()))continue;
                for(var message:value.path("messages")){
                    String peer=WhatsAppClient.required(message,"from");String name=peer;
                    for(var contact:value.path("contacts"))if(contact.path("wa_id").asText().equals(peer))name=contact.path("profile").path("name").asText(peer);
                    var raw=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();raw.put("id",WhatsAppClient.required(message,"id"));raw.put("message",message.path("text").path("body").asText("[WhatsApp attachment or unsupported message]"));
                    raw.put("created_time",Instant.ofEpochSecond(Long.parseLong(WhatsAppClient.required(message,"timestamp"))).toString());raw.putObject("from").put("id",peer).put("username",name);ingest(a,raw);
                }
                for(var status:value.path("statuses"))receipt(status);
            }
        }
    }
    private void receipt(JsonNode status){
        String external=WhatsAppClient.required(status,"id");String state=status.path("status").asText();
        DeliveryStatus delivery=switch(state){case "read"->DeliveryStatus.READ;case "delivered"->DeliveryStatus.DELIVERED;case "sent"->DeliveryStatus.SENT;case "failed"->DeliveryStatus.FAILED;default->null;};
        if(delivery==null)return;
        tx.executeWithoutResult(s->{workspace.lock();
            for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_wa_outbox WHERE external_id=?",(r,n)->r.getObject(1,UUID.class),external))messages.findActiveById(id).ifPresent(m->{
                if(m.getDeliveryStatus()==DeliveryStatus.READ||m.getDeliveryStatus()==DeliveryStatus.DELIVERED&&delivery!=DeliveryStatus.READ)return;
                m.setDeliveryStatus(delivery);messages.save(m);
            });
        });
    }
    private boolean own(Account a,String id){return a.id.equals(id)||a.scoped.equals(id);}
    private void ingest(Account a,JsonNode raw){
        String external=WhatsAppClient.required(raw,"id"),sender=WhatsAppClient.required(raw.path("from"),"id");boolean outbound=own(a,sender);
        JsonNode peer=raw.path("from");if(outbound){peer=null;for(var to:raw.path("to").path("data"))if(!own(a,to.path("id").asText())){if(peer!=null)return;peer=to;}if(peer==null)return;}
        String peerId=WhatsAppClient.required(peer,"id"),name=peer.path("username").asText(peerId);String body=raw.path("message").asText();if(body.isBlank())body="[WhatsApp attachment or unavailable message]";
        String text=cut(body,8000);LocalDateTime sent=parseTime(WhatsAppClient.required(raw,"created_time"));
        tx.executeWithoutResult(s->{workspace.lock();if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_wa_seen WHERE account_id=? AND external_id=?",Integer.class,a.id,external)>0)return;
            var ids=jdbc.query("SELECT conversation_id FROM onno_crm_wa_peer WHERE account_id=? AND peer_id=?",(r,n)->r.getObject(1,UUID.class),a.id,peerId);Conversation c;
            if(ids.isEmpty()){
                UUID identityId=UUID.nameUUIDFromBytes(("WHATSAPP\n"+a.inbox+"\n"+peerId).getBytes(StandardCharsets.UTF_8));var identity=identities.findActiveById(identityId).orElse(null);
                UUID customer=contacts.resolveIncoming(new CrmCustomerBinding.IncomingContact(Channel.WHATSAPP,
                        a.inbox.toString(),peerId,cut(name,200),null,"+"+peerId,null,"WHATSAPP"));
                contacts.link(customer,Channel.WHATSAPP,a.inbox.toString(),peerId,name,true);
                c=new Conversation();c.setCustomer(customer);c.setInbox(Ref.of(Inbox.class,a.inbox));c.setChannel(Channel.WHATSAPP);c.setSubject("WhatsApp · "+cut(name,200));c.setDescription(c.getSubject());c.setLastMessageAt(sent);conversations.save(c);
                jdbc.update("INSERT INTO onno_crm_wa_peer VALUES (?,?,?,NULL)",a.id,peerId,c.getId());
            }else c=conversations.findActiveById(ids.getFirst()).orElse(null);
            if(c!=null){c.setCustomer(contacts.canonical(c.getCustomer()));var m=new ConversationMessage();m.setConversation(Ref.of(Conversation.class,c.getId()));m.setChannel(Channel.WHATSAPP);
                m.setKind(outbound?MessageKind.AGENT_REPLY:MessageKind.CUSTOMER_MESSAGE);m.setDirection(outbound?MessageDirection.OUTBOUND:MessageDirection.INBOUND);m.setAuthorName(cut(outbound?a.username:name,200));m.setBody(text);m.setDescription(cut(text,100));m.setSentAt(sent);m.setDeliveryStatus(outbound?DeliveryStatus.SENT:DeliveryStatus.RECEIVED);m.setExternalMessageId(externalKey(external));messages.save(m);
                if(!outbound){jdbc.update("UPDATE onno_crm_wa_peer SET last_inbound=? WHERE account_id=? AND peer_id=? AND (last_inbound IS NULL OR last_inbound<?)",sent,a.id,peerId,sent);c.setUnreadCount(c.getUnreadCount()+1);c.setStatus(statuses.incoming());}
                if(c.getLastMessageAt()==null||!sent.isBefore(c.getLastMessageAt())){c.setLastMessageAt(sent);c.setLastMessagePreview(cut(text,180));}conversations.save(c);}
            jdbc.update("INSERT INTO onno_crm_wa_seen VALUES (?,?)",a.id,external);
        });
    }
    private void deliver(Account a){for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_wa_outbox WHERE account_id=? AND state='QUEUED'",(r,n)->r.getObject(1,UUID.class),a.id)){
        if(jdbc.update("UPDATE onno_crm_wa_outbox SET state='SENDING' WHERE message_id=? AND state='QUEUED'",id)!=1)continue;
        try{var m=messages.findActiveById(id).orElseThrow();var c=conversations.findActiveById(m.getConversation().id()).orElseThrow();if(!connection(c).canSend())throw new IllegalStateException();
            String peer=jdbc.queryForObject("SELECT peer_id FROM onno_crm_wa_outbox WHERE message_id=?",String.class,id);
            var time=jdbc.queryForObject("SELECT last_inbound FROM onno_crm_wa_peer WHERE account_id=? AND peer_id=?",java.sql.Timestamp.class,a.id,peer);
            if(time==null||time.toInstant().isBefore(Instant.now().minus(Duration.ofHours(24))))throw new IllegalStateException();
            String external=client.send(a.id,peer,m.getBody());tx.executeWithoutResult(s->{finish(id,true,external);jdbc.update("INSERT INTO onno_crm_wa_seen VALUES (?,?)",a.id,external);});
        }catch(Exception e){
            if(e instanceof WhatsAppClient.ApiFailure api)log.warn("WhatsApp reply {} failed: HTTP {}, provider code {}, subcode {}",id,api.status,api.code,api.subcode);
            else log.warn("WhatsApp reply {} failed before completion ({})",id,e.getClass().getSimpleName());
            tx.executeWithoutResult(s->finish(id,false,null));}
    }}
    private void finish(UUID id,boolean sent,String external){messages.findActiveById(id).ifPresent(m->{m.setDeliveryStatus(sent?DeliveryStatus.SENT:DeliveryStatus.FAILED);if(external!=null)m.setExternalMessageId(externalKey(external));messages.save(m);});jdbc.update("UPDATE onno_crm_wa_outbox SET state=?,external_id=? WHERE message_id=?",sent?"SENT":"FAILED",external,id);}
    static LocalDateTime parseTime(String value){
        OffsetDateTime time;
        try{time=OffsetDateTime.parse(value);}catch(java.time.format.DateTimeParseException e){time=OffsetDateTime.parse(value,DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"));}
        return time.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
    private static String externalKey(String id){return "whatsapp:"+UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));}
    private static String cut(String text,int max){return text.substring(0,Math.min(text.length(),max));}
    @PreDestroy public void stop(){ready=false;worker.shutdownNow();}
}
