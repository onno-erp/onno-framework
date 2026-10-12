package su.onno.crm.channels.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GmailExternalReplyTest {
    @Test void importsExternalSentReplyClearsOnlyEarlierUnreadAndDeduplicatesCrmEcho() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);var json=new ObjectMapper();var client=mock(GmailClient.class);
        var chats=mock(ConversationRepository.class);var messages=mock(ConversationMessageRepository.class);
        var stored=new ArrayList<ConversationMessage>();var c=new Conversation();c.setId(UUID.randomUUID());c.setUnreadCount(2);
        var at=LocalDateTime.ofInstant(Instant.ofEpochMilli(2000000),ZoneId.systemDefault());
        for(int delta:new int[]{-10,10}){var m=new ConversationMessage();m.setDirection(MessageDirection.INBOUND);m.setSentAt(at.plusSeconds(delta));stored.add(m);}
        when(chats.findActiveById(c.getId())).thenReturn(Optional.of(c));
        when(messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(any())).thenAnswer(i->new ArrayList<>(stored));
        when(messages.save(any())).thenAnswer(i->{ConversationMessage m=i.getArgument(0);stored.add(m);return m;});
        var bridge=new GmailBridge(client,jdbc,new DataSourceTransactionManager(ds),mock(InboxRepository.class),chats,messages,mock(CrmContactService.class),mock(CrmWorkspaceService.class),mock(ContactIdentityRepository.class),mock(CrmConversationStatuses.class),mock(CrmAttachments.class),mock(CrmConnectionAccounts.class));
        bridge.start();bridge.stop();
        UUID inbox=UUID.randomUUID();jdbc.update("INSERT INTO onno_crm_gmail_account VALUES (?,?,?,TRUE,NULL)","owner@example.test",inbox,"1");
        jdbc.update("INSERT INTO onno_crm_gmail_thread (account,thread_id,conversation_id,reply_to) VALUES (?,?,?,?)","owner@example.test","thread",c.getId(),"customer@example.test");
        var account=((List<?>)ReflectionTestUtils.invokeMethod(bridge,"accounts")).getFirst();
        String raw=Base64.getUrlEncoder().encodeToString("From: owner@example.test\r\nTo: customer@example.test\r\nSubject: Re: Test\r\n\r\nExternal reply".getBytes(StandardCharsets.UTF_8));
        var response=json.createObjectNode().put("raw",raw).put("threadId","thread").put("internalDate","2000000");response.putArray("labelIds").add("SENT");
        when(client.get("messages/external?format=raw")).thenReturn(response);
        when(client.get("history?historyTypes=messageAdded&startHistoryId=1&maxResults=100")).thenReturn(json.readTree("{\"historyId\":\"2\",\"history\":[{\"messagesAdded\":[{\"message\":{\"id\":\"external\",\"labelIds\":[\"SENT\"]}}]}]}"));
        ReflectionTestUtils.invokeMethod(bridge,"sync",account);
        assertThat(c.getUnreadCount()).isEqualTo(1);assertThat(stored).hasSize(3);
        assertThat(stored.getLast().getDirection()).isEqualTo(MessageDirection.OUTBOUND);
        assertThat(stored.getLast().getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(jdbc.queryForObject("SELECT reply_to FROM onno_crm_gmail_thread",String.class)).isEqualTo("customer@example.test");
        ReflectionTestUtils.invokeMethod(bridge,"ingest",account,"external");assertThat(stored).hasSize(3);
        var crm=new ConversationMessage();crm.setExternalMessageId("gmail:crm-send");stored.add(crm);
        when(client.get("messages/crm-send?format=raw")).thenReturn(response);
        ReflectionTestUtils.invokeMethod(bridge,"ingest",account,"crm-send");assertThat(stored).hasSize(4);
        response.put("threadId","unrelated");when(client.get("messages/unrelated?format=raw")).thenReturn(response);
        ReflectionTestUtils.invokeMethod(bridge,"ingest",account,"unrelated");assertThat(stored).hasSize(4);
    }
}
