package su.onno.crm.channels.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramBusinessAccountsTest {
    final ObjectMapper json=new ObjectMapper();
    final Principal alice=()->"alice",bob=()->"bob";
    final TelegramClient client=mock(TelegramClient.class);
    final InboxRepository inboxes=mock(InboxRepository.class);
    final ConversationRepository conversations=mock(ConversationRepository.class);
    final ConversationMessageRepository messages=mock(ConversationMessageRepository.class);
    final Map<UUID,Inbox> storedInboxes=new HashMap<>();
    final Map<UUID,Conversation> storedChats=new HashMap<>();
    final Map<UUID,ConversationMessage> storedMessages=new HashMap<>();
    final CrmContactService contacts=mock(CrmContactService.class);
    JdbcTemplate jdbc;CrmConnectionAccounts accounts;TelegramBusinessAccounts service;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        CrmChannelAccess access=mock(CrmChannelAccess.class);
        when(access.canManage(any())).thenReturn(true);when(access.canManagePersonal(any())).thenReturn(true);
        accounts=new CrmConnectionAccounts(jdbc,access,su.onno.crm.service.CrmFeatures.defaults().withPersonalAccounts(true));accounts.initialize();
        accounts=spy(accounts);
        when(contacts.resolveIncoming(any())).thenAnswer(a->UUID.randomUUID());
        var attachments=mock(CrmAttachments.class);when(attachments.of(any())).thenReturn(List.of());when(attachments.limit()).thenReturn(10);
        when(inboxes.save(any())).thenAnswer(a->{Inbox v=a.getArgument(0);if(v.getId()==null)v.setId(UUID.randomUUID());storedInboxes.put(v.getId(),v);return v;});
        when(inboxes.findActiveById(any())).thenAnswer(a->Optional.ofNullable(storedInboxes.get(a.getArgument(0))));
        when(conversations.save(any())).thenAnswer(a->{Conversation v=a.getArgument(0);if(v.getId()==null)v.setId(UUID.randomUUID());storedChats.put(v.getId(),v);return v;});
        when(conversations.findActiveById(any())).thenAnswer(a->Optional.ofNullable(storedChats.get(a.getArgument(0))));
        when(messages.save(any())).thenAnswer(a->{ConversationMessage v=a.getArgument(0);if(v.getId()==null)v.setId(UUID.randomUUID());storedMessages.put(v.getId(),v);return v;});
        when(messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(any())).thenAnswer(a->storedMessages.values().stream().filter(m->m.getConversation().equals(a.getArgument(0))).sorted(java.util.Comparator.comparing(ConversationMessage::getSentAt)).toList());
        when(messages.findActiveById(any())).thenAnswer(a->Optional.ofNullable(storedMessages.get(a.getArgument(0))));
        service=new TelegramBusinessAccounts(jdbc,new DataSourceTransactionManager(ds),accounts,mock(CrmWorkspaceService.class),contacts,
                mock(CrmConversationStatuses.class),inboxes,conversations,messages,attachments);
        service.initialize(new TelegramClient.Bot(11,"business_bot",true),()->client);
    }
    JsonNode connection(String id,long owner,boolean enabled,boolean reply) throws Exception {
        return json.readTree("{\"id\":\""+id+"\",\"user\":{\"id\":"+owner+",\"first_name\":\"Owner\"},\"is_enabled\":"+enabled+",\"rights\":{\"can_reply\":"+reply+"}}");
    }
    void connectionEvent(String id,long owner,boolean enabled,boolean reply) throws Exception {
        JsonNode value=connection(id,owner,enabled,reply);when(client.businessConnection(id)).thenReturn(value);
        var event=json.createObjectNode();event.set("business_connection",value);receive(event);
    }
    void receive(JsonNode event) {service.receive(new TelegramClient.Update(1,0,0,0,0,"","",null,false,event));}
    void message(String connection,long sender,long peer,long id,boolean edited) {
        var event=json.createObjectNode();var m=event.putObject(edited?"edited_business_message":"business_message");
        m.put("business_connection_id",connection).put("message_id",id).put("date",Instant.now().getEpochSecond()).put("text",edited?"Edited":"Hello");
        m.putObject("from").put("id",sender).put("first_name","Customer");m.putObject("chat").put("id",peer).put("type","private");receive(event);
    }
    CrmConnectionAccounts.Account claim(Principal p,long telegramUser) {
        var a=accounts.create(TelegramBusinessAccounts.PROVIDER,su.onno.crm.service.CrmConnectionAccounts.Scope.PERSONAL,p.getName(),p);
        String url=service.authorize(a.id(),p);String token=url.substring(url.indexOf("crm_")+4);
        service.receive(new TelegramClient.Update(1,telegramUser,telegramUser,1,1,"Owner","","/start crm_"+token,true));
        return accounts.find(a.id());
    }
    @Test void requiresTelegramProofAndKeepsPersonalInboxesPrivate() throws Exception {
        connectionEvent("connection-a",100,true,true);
        message("connection-a",500,500,1,false);
        assertThat(storedChats).isEmpty();
        var a=claim(alice,100);
        message("connection-a",500,500,1,false);
        assertThat(storedChats).hasSize(1);
        assertThat(accounts.hiddenInboxes(bob)).contains(a.inboxId());
        assertThat(accounts.hiddenInboxes(alice)).doesNotContain(a.inboxId());
        assertThatThrownBy(()->service.authorize(a.id(),bob)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void scopesSamePeerToDifferentBusinessOwnersAndDeduplicatesEdits() throws Exception {
        connectionEvent("connection-a",100,true,true);var a=claim(alice,100);
        connectionEvent("connection-b",200,true,true);var b=claim(bob,200);
        message("connection-a",500,500,1,false);message("connection-b",500,500,1,false);
        message("connection-a",500,500,1,false);message("connection-a",500,500,1,true);
        assertThat(storedChats).hasSize(2);assertThat(storedMessages).hasSize(2);
        assertThat(storedChats.values()).allSatisfy(c->assertThat(c.getUnreadCount()).isEqualTo(1));
        assertThat(storedChats.values().stream().map(c->c.getInbox().id())).containsExactlyInAnyOrder(a.inboxId(),b.inboxId());
        assertThat(storedMessages.values().stream().map(ConversationMessage::getBody)).contains("Edited");
        message("connection-a",100,500,2,false); // external owner reply
        assertThat(storedMessages).hasSize(3);
        assertThat(storedChats.values().stream().filter(c->c.getInbox().id().equals(a.inboxId())).findFirst().orElseThrow().getUnreadCount()).isZero();
        message("connection-a",100,500,2,false);
        assertThat(storedMessages).hasSize(3);
    }
    @Test void expiredAndReplayedChallengesCannotBindAccounts() throws Exception {
        var a=accounts.create(TelegramBusinessAccounts.PROVIDER,su.onno.crm.service.CrmConnectionAccounts.Scope.PERSONAL,"Alice",alice);
        String url=service.authorize(a.id(),alice);String command="/start "+url.substring(url.indexOf("crm_"));
        jdbc.update("UPDATE onno_crm_tg_business_auth SET expires_at=TIMESTAMP '2000-01-01 00:00:00'");
        service.receive(new TelegramClient.Update(1,100,100,1,1,"Owner","",command,true));
        assertThat(accounts.find(a.id()).inboxId()).isNull();
        url=service.authorize(a.id(),alice);command="/start "+url.substring(url.indexOf("crm_"));
        service.receive(new TelegramClient.Update(1,100,100,1,1,"Owner","",command,true));
        service.receive(new TelegramClient.Update(2,200,200,1,1,"Other","",command,true));
        assertThat(jdbc.queryForObject("SELECT user_id FROM onno_crm_tg_business_links WHERE account_id=?",Long.class,a.id())).isEqualTo(100);
    }
    Conversation incoming() throws Exception {
        connectionEvent("connection-a",100,true,true);claim(alice,100);message("connection-a",500,500,1,false);
        return storedChats.values().iterator().next();
    }
    ConversationMessage reply(Conversation c) {
        var m=new ConversationMessage();m.setId(UUID.randomUUID());m.setConversation(Ref.of(Conversation.class,c.getId()));
        m.setBody("Reply");m.setKind(MessageKind.AGENT_REPLY);m.setDirection(MessageDirection.OUTBOUND);m.setDeliveryStatus(DeliveryStatus.QUEUED);
        messages.save(m);service.enqueue(c,m);return m;
    }
    @Test void sendsOnlyWithBusinessConnectionAndRechecksRevocation() throws Exception {
        var c=incoming();var m=reply(c);when(client.send(500,"Reply","connection-a")).thenReturn(12L);
        service.deliver();verify(client).send(500,"Reply","connection-a");verify(client,never()).send(anyLong(),anyString());
        assertThat(m.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        var blocked=reply(c);
        when(client.businessConnection("connection-a")).thenReturn(connection("connection-a",100,false,false));
        service.deliver();assertThat(blocked.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(client,times(1)).send(500,"Reply","connection-a");
    }
    @Test void rejectsStaleReplyWindowAndChangedConnectionDestination() throws Exception {
        var c=incoming();var m=reply(c);
        connectionEvent("replacement",100,true,true);
        service.deliver();assertThat(m.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(client,never()).send(anyLong(),anyString(),anyString());
        jdbc.update("UPDATE onno_crm_tg_business_chats SET last_inbound=TIMESTAMP '2000-01-01 00:00:00'");
        assertThat(service.connection(c).connected()).isFalse();
        assertThatThrownBy(()->reply(c)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disconnectCannotBeUndoneByProviderEvents() throws Exception {
        var c=incoming();var m=reply(c);var a=accounts.visible(alice).getFirst();
        service.command(a,"disconnect");connectionEvent("connection-a",100,true,true);
        assertThat(service.connection(c).connected()).isFalse();assertThat(m.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        message("connection-a",500,500,2,false);assertThat(storedChats).hasSize(1);
        assertThat(storedMessages).hasSize(2); // original incoming plus failed reply
    }
    @Test void businessContactsUseTheAccountInboxAsConnectionKey() throws Exception {
        connectionEvent("connection-a",100,true,true);var a=claim(alice,100);
        message("connection-a",500,500,1,false);
        var captured=org.mockito.ArgumentCaptor.forClass(CrmCustomerBinding.IncomingContact.class);
        verify(contacts).resolveIncoming(captured.capture());
        // Personal-inbox privacy hides identities whose connection key is a hidden inbox id.
        assertThat(captured.getValue().connectionKey()).isEqualTo(a.inboxId().toString());
    }
    @Test void transientFailureBeforeSendingLeavesTheReplyQueuedAndBacksOff() throws Exception {
        var c=incoming();var m=reply(c);
        when(client.businessConnection("connection-a")).thenThrow(new TelegramClient.ApiFailure(429,30));
        service.deliver();
        assertThat(m.getDeliveryStatus()).isEqualTo(DeliveryStatus.QUEUED);
        assertThat(jdbc.queryForObject("SELECT state FROM onno_crm_tg_business_outbox WHERE message_id=?",String.class,m.getId())).isEqualTo("QUEUED");
        verify(client,never()).send(anyLong(),anyString(),anyString());
        service.deliver(); // still backing off: no second lookup
        verify(client,times(1)).businessConnection("connection-a");
    }
    @Test void accountCommandsPublishAccountChanges() throws Exception {
        incoming();var a=accounts.visible(alice).getFirst();
        clearInvocations(accounts);service.command(a,"pause");verify(accounts).changed();
        clearInvocations(accounts);service.command(a,"resume");verify(accounts).changed();
        clearInvocations(accounts);service.command(a,"disconnect");verify(accounts).changed();
    }
    @Test void preparesFilesBeforeTheLockAndRecordsPlaceholderWhenImportFails() throws Exception {
        connectionEvent("connection-a",100,true,true);claim(alice,100);
        var event=json.createObjectNode();var m=event.putObject("business_message");
        m.put("business_connection_id","connection-a").put("message_id",9).put("date",Instant.now().getEpochSecond()).put("caption","Plan");
        m.putObject("document").put("file_id","doc");
        m.putObject("from").put("id",500).put("first_name","Customer");m.putObject("chat").put("id",500).put("type","private");
        when(client.download("doc")).thenThrow(new IllegalArgumentException("too large"));
        var update=new TelegramClient.Update(5,0,0,0,0,"","",null,false,event);
        assertThat(service.consumes(update)).isTrue();
        var prepared=service.prepare(update);
        assertThat(prepared.files()).isNotNull();
        service.receive(update,prepared);
        assertThat(storedMessages.values()).singleElement().satisfies(v->assertThat(v.getBody()).isEqualTo("[Attachment] Plan\n"+TelegramAttachments.TOO_LARGE));
    }
}
