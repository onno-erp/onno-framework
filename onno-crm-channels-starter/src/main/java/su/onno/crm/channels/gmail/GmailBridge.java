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

/** Single-account local CRM adapter. Gmail account/thread IDs, not email display names, own routes. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(GmailBridge.class)
@Component
@ConditionalOnProperty(name="onno.crm.channels.gmail.enabled",havingValue="true")
public class GmailBridge implements CrmMessageTransport,CrmChannelConnection {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GmailBridge.class);
    private final GmailClient client;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final InboxRepository inboxes;private final CustomerRepository customers;
    private final ConversationRepository conversations;private final ConversationMessageRepository messages;
    private final ContactIdentityRepository identities;private final CrmContactService contacts;private final CrmWorkspaceService workspace;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"crm-gmail");t.setDaemon(true);return t;});
    private volatile boolean ready;private volatile String failure="";
    private final su.onno.crm.service.CrmConversationStatuses statuses;
    public GmailBridge(GmailClient client,JdbcTemplate jdbc,PlatformTransactionManager transactions,InboxRepository inboxes,
        CustomerRepository customers,ConversationRepository conversations,ConversationMessageRepository messages,CrmContactService contacts,CrmWorkspaceService workspace,ContactIdentityRepository identities, su.onno.crm.service.CrmConversationStatuses statuses) {
        this.statuses=statuses;
        this.client=client;this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.inboxes=inboxes;this.customers=customers;
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
        groupExistingConversations();
        ready=true;worker.scheduleWithFixedDelay(this::tick,2,15,TimeUnit.SECONDS);
    }
    private record Account(String email,UUID inbox,String history,boolean active) {}
    private Account account(){var rows=jdbc.query("SELECT account,inbox_id,history_id,active FROM onno_crm_gmail_account",(rs,n)->new Account(rs.getString(1),rs.getObject(2,UUID.class),rs.getString(3),rs.getBoolean(4)));return rows.isEmpty()?null:rows.getFirst();}
    public synchronized void connect(JsonNode token) {
        String email=client.profile(token).path("emailAddress").asText();if(email.isBlank())throw new IllegalArgumentException("Google did not identify a mailbox");
        var old=account();if(old!=null&&!old.email.equalsIgnoreCase(email))throw new IllegalArgumentException("Reconnect the original Gmail account; switching mailboxes requires a separate inbox");
        // Persist credentials before activating the account. A failed DB commit is retryable by reauthorizing.
        client.saveTokens(token);
        tx.executeWithoutResult(s->{workspace.lock();if(old==null){var inbox=new Inbox();inbox.setDescription("Gmail · "+email);inbox.setAddress(email);inbox.setChannel(Channel.EMAIL);inboxes.save(inbox);jdbc.update("INSERT INTO onno_crm_gmail_account VALUES (?,?,NULL,TRUE)",email,inbox.getId());}
            else{jdbc.update("UPDATE onno_crm_gmail_account SET active=TRUE WHERE account=?",email);inboxes.findActiveById(old.inbox).ifPresent(i->{i.setActive(true);inboxes.save(i);});}});failure="";
    }
    public String key(){return "gmail";}
    public View view(){var a=ready?account():null;String state=!client.configured()?"UNAVAILABLE":a==null?"DISCONNECTED":!failure.isBlank()?"ERROR":a.active?"CONNECTED":"PAUSED";
        return new View("gmail","Gmail","EMAIL",state,a==null?"":a.email,"Sync recent inbox mail and reply from this CRM. Attachments are shown as text placeholders.",!client.configured()?List.of():a==null?List.of("connect"):List.of("connect","check",a.active?"pause":"resume"));}
    public synchronized void command(String action,String credential){var a=account();if(a==null)throw new IllegalArgumentException("Connect a Google account first");
        if(action.equals("check")){client.get("profile");failure="";return;}
        if(!action.equals("pause")&&!action.equals("resume"))throw new IllegalArgumentException("Unsupported Gmail action");
        boolean active=action.equals("resume");if(active)client.get("profile");
        tx.executeWithoutResult(s->{workspace.lock();jdbc.update("UPDATE onno_crm_gmail_account SET active=? WHERE account=?",active,a.email);inboxes.findActiveById(a.inbox).ifPresent(i->{i.setActive(active);inboxes.save(i);});});failure="";
    }
    public Connection connection(Conversation c){if(!ready||c.getChannel()!=Channel.EMAIL||c.getInbox()==null)return new Connection(false,"Gmail is not connected",8000);var a=account();
        boolean mapped=a!=null&&a.inbox.equals(c.getInbox().id())&&a.active&&client.authorized()&&jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_thread WHERE account=? AND conversation_id=?",Integer.class,a.email,c.getId())>0;
        return new Connection(mapped,mapped?"Gmail":"Gmail is not connected or is paused",8000);}
    public void enqueue(Conversation c,ConversationMessage message){if(!connection(c).connected())throw new IllegalArgumentException("Gmail is not connected");var a=account();
        String thread=jdbc.queryForObject("SELECT thread_id FROM onno_crm_gmail_thread WHERE account=? AND conversation_id=? ORDER BY last_received DESC NULLS LAST,thread_id DESC FETCH FIRST 1 ROW ONLY",String.class,a.email,c.getId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_outbox WHERE message_id=?",Integer.class,message.getId())==0)jdbc.update("INSERT INTO onno_crm_gmail_outbox VALUES (?,?,?,'QUEUED')",message.getId(),a.email,thread);
        else if(jdbc.update("UPDATE onno_crm_gmail_outbox SET state='QUEUED' WHERE message_id=? AND state='FAILED'",message.getId())!=1)throw new IllegalArgumentException("Reply is already queued or sent");}
    synchronized void tick(){if(!ready||!client.authorized())return;var a=account();if(a==null||!a.active)return;
        try {deliver(a);sync(a);failure="";}catch(Exception e){failure="Gmail synchronization failed; check connection or reconnect";Throwable root=e;while(root.getCause()!=null)root=root.getCause();log.warn("Gmail sync deferred: {} / {} at {}",e.getClass().getSimpleName(),root.getClass().getSimpleName(),root.getStackTrace().length==0?"unknown":root.getStackTrace()[0]);}}
    private void sync(Account a) throws Exception {
        if(a.history==null){String checkpoint=client.get("profile").path("historyId").asText();var page=client.get("messages?labelIds=INBOX&maxResults=50");
            for(var item:page.path("messages"))ingest(a,item.path("id").asText());jdbc.update("UPDATE onno_crm_gmail_account SET history_id=? WHERE account=?",checkpoint,a.email);return;}
        String next="",checkpoint=a.history;
        do{JsonNode page;try{page=client.get("history?historyTypes=messageAdded&startHistoryId="+GmailClient.encode(a.history)+"&maxResults=100"+(next.isBlank()?"":"&pageToken="+GmailClient.encode(next)));}
            catch(GmailClient.ApiFailure e){if(e.status==404){jdbc.update("UPDATE onno_crm_gmail_account SET history_id=NULL WHERE account=?",a.email);return;}throw e;}
            for(var history:page.path("history"))for(var added:history.path("messagesAdded")){var m=added.path("message");boolean inbox=false;for(var label:m.path("labelIds"))if(label.asText().equals("INBOX"))inbox=true;if(inbox)ingest(a,m.path("id").asText());}
            checkpoint=page.path("historyId").asText(checkpoint);next=page.path("nextPageToken").asText();
        }while(!next.isBlank());jdbc.update("UPDATE onno_crm_gmail_account SET history_id=? WHERE account=?",checkpoint,a.email);
    }
    private void ingest(Account a,String id) throws Exception {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_seen WHERE account=? AND external_id=?",Integer.class,a.email,id)>0)return;
        JsonNode raw;try{raw=client.get("messages/"+GmailClient.encode(id)+"?format=raw");}catch(GmailClient.ApiFailure e){if(e.status==404)return;throw e;}
        var mail=GmailMail.read(raw.path("raw").asText());String thread=raw.path("threadId").asText();
        tx.executeWithoutResult(s->{workspace.lock();if(jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_seen WHERE account=? AND external_id=?",Integer.class,a.email,id)>0)return;
            var ids=jdbc.query("SELECT conversation_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=?",(rs,n)->rs.getObject(1,UUID.class),a.email,thread);Conversation c;
            if(ids.isEmpty()){
                UUID identityId=UUID.nameUUIDFromBytes(("EMAIL\n"+a.inbox+"\n"+mail.sender().toLowerCase(Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var identity=identities.findActiveById(identityId).orElse(null);
                Customer customer=identity==null?null:customers.findActiveById(contacts.canonical(identity.getCustomer().id())).orElse(null);
                if(customer==null){customer=new Customer();customer.setDescription(cut(mail.name(),200));customer.setEmail(mail.sender());customer.setSource("Gmail "+a.email);customers.save(customer);}
                c=conversationForCustomer(a,customer.getId());
                if(c==null){c=new Conversation();c.setLastMessageAt(LocalDateTime.ofInstant(mail.sentAt(),ZoneId.systemDefault()));c.setCustomer(Ref.of(Customer.class,customer.getId()));c.setInbox(Ref.of(Inbox.class,a.inbox));c.setChannel(Channel.EMAIL);c.setSubject(cut(mail.subject(),240));c.setDescription(c.getSubject());conversations.save(c);}
                jdbc.update("INSERT INTO onno_crm_gmail_thread (account,thread_id,conversation_id,reply_to,subject,reference_id,last_received) VALUES (?,?,?,?,?,?,?)",a.email,thread,c.getId(),cut(mail.replyTo(),320),cut(mail.subject(),998),cut(mail.messageId(),998),LocalDateTime.ofInstant(mail.sentAt(),ZoneId.systemDefault()));
            }else c=conversations.findActiveById(ids.getFirst()).orElse(null);
            if(c!=null){UUID canonical=contacts.canonical(c.getCustomer().id());c.setCustomer(Ref.of(Customer.class,canonical));UUID senderIdentity=UUID.nameUUIDFromBytes(("EMAIL\n"+a.inbox+"\n"+mail.sender().toLowerCase(Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var linked=identities.findActiveById(senderIdentity).orElse(null);
                if(linked==null||contacts.canonical(linked.getCustomer().id()).equals(canonical))contacts.link(canonical,Channel.EMAIL,a.inbox.toString(),mail.sender().toLowerCase(Locale.ROOT),mail.sender(),false);
                var message=new ConversationMessage();message.setConversation(Ref.of(Conversation.class,c.getId()));message.setChannel(Channel.EMAIL);message.setKind(MessageKind.CUSTOMER_MESSAGE);message.setDirection(MessageDirection.INBOUND);message.setAuthorName(cut(mail.name(),200));
                message.setBody(cut(mail.text(),8000));message.setDescription(cut(mail.text(),100));message.setSentAt(LocalDateTime.ofInstant(mail.sentAt(),ZoneId.systemDefault()));message.setDeliveryStatus(DeliveryStatus.RECEIVED);message.setExternalMessageId(cut("gmail:"+a.email+":"+id,240));messages.save(message);
                if(c.getLastMessageAt()==null||!message.getSentAt().isBefore(c.getLastMessageAt())){c.setLastMessageAt(message.getSentAt());c.setLastMessagePreview(cut(mail.text(),180));}
                jdbc.update("UPDATE onno_crm_gmail_thread SET reply_to=?,subject=?,reference_id=?,last_received=? WHERE account=? AND thread_id=? AND (last_received IS NULL OR last_received<=?)",cut(mail.replyTo(),320),cut(mail.subject(),998),cut(mail.messageId(),998),message.getSentAt(),a.email,thread,message.getSentAt());
                c.setUnreadCount(c.getUnreadCount()+1);c.setStatus(statuses.incoming());conversations.save(c);}
            jdbc.update("INSERT INTO onno_crm_gmail_seen VALUES (?,?)",a.email,id);
        });
    }
    private void deliver(Account a){for(UUID id:jdbc.query("SELECT message_id FROM onno_crm_gmail_outbox WHERE account=? AND state='QUEUED'",(rs,n)->rs.getObject(1,UUID.class),a.email)){
        if(jdbc.update("UPDATE onno_crm_gmail_outbox SET state='SENDING' WHERE message_id=? AND state='QUEUED'",id)!=1)continue;
        try{var message=messages.findActiveById(id).orElseThrow();var c=conversations.findActiveById(message.getConversation().id()).orElseThrow();if(!connection(c).connected())throw new IllegalStateException();
            var row=jdbc.queryForMap("SELECT thread_id,reply_to,subject,reference_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=(SELECT thread_id FROM onno_crm_gmail_outbox WHERE message_id=?)",a.email,id);
            var sent=client.send(GmailMail.reply(a.email,row.get("reply_to").toString(),row.get("subject").toString(),row.get("reference_id").toString(),message.getBody(),id),row.get("thread_id").toString());
            tx.executeWithoutResult(s->finish(id,true,sent.path("id").asText()));
        }catch(Exception e){tx.executeWithoutResult(s->finish(id,false,null));}
    }}
    private Conversation conversationForCustomer(Account a,UUID customer) {
        UUID canonical=contacts.canonical(customer);
        for(UUID id:jdbc.query("SELECT DISTINCT conversation_id FROM onno_crm_gmail_thread WHERE account=?",(rs,n)->rs.getObject(1,UUID.class),a.email)) {
            var c=conversations.findActiveById(id).orElse(null);
            if(c!=null&&contacts.canonical(c.getCustomer().id()).equals(canonical))return c;
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
                UUID customer=contacts.canonical(source.getCustomer().id());var target=groups.get(customer);
                if(target==null){groups.put(customer,source);continue;}
                for(var message:history){message.setConversation(Ref.of(Conversation.class,target.getId()));messages.save(message);}
                if(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)='onno_comments' AND TABLE_SCHEMA=CURRENT_SCHEMA()",Integer.class)>0)
                    jdbc.update("UPDATE onno_comments SET _entity_id=? WHERE _entity_type='catalogs' AND _entity_name='crm_conversations' AND _entity_id=?",target.getId(),id);
                if(target.getAssignee()==null)target.setAssignee(source.getAssignee());
                if(source.getPriority().ordinal()>target.getPriority().ordinal())target.setPriority(source.getPriority());
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
