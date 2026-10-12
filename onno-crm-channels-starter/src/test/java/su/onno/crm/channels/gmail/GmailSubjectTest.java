package su.onno.crm.channels.gmail;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

class GmailSubjectTest {
    MimeMessage parse(String raw) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
    }
    @Test void repliesKeepSubjectAndReference() throws Exception {
        var mail=parse(GmailMail.reply("agent@example.com","client@example.com","Booking","<original@example.com>","Hello",UUID.randomUUID()));
        assertThat(mail.getSubject()).isEqualTo("Re: Booking");
        assertThat(mail.getHeader("In-Reply-To",null)).isEqualTo("<original@example.com>");
    }
    @Test void editedSubjectIsExactAndHasNoOldThreadReference() throws Exception {
        var mail=parse(GmailMail.compose("agent@example.com","client@example.com","Our event — proposal","","Hello",List.of(),UUID.randomUUID()));
        assertThat(mail.getSubject()).isEqualTo("Our event — proposal");
        assertThat(mail.getHeader("In-Reply-To",null)).isNull();
        assertThat(mail.getHeader("References",null)).isNull();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings={"Booking", "New proposal", "", "recipient-change"})
    void deliveryRoutesChangedSubjectsAndSubsequentReplies(String subject) throws Exception {
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        var client=org.mockito.Mockito.mock(GmailClient.class);
        var chats=org.mockito.Mockito.mock(su.onno.crm.repository.ConversationRepository.class);
        var messages=org.mockito.Mockito.mock(su.onno.crm.repository.ConversationMessageRepository.class);
        var attachments=org.mockito.Mockito.mock(su.onno.crm.service.CrmAttachments.class);
        var c=new su.onno.crm.domain.Conversation();c.setId(UUID.randomUUID());c.setChannel("EMAIL");
        UUID inbox=UUID.randomUUID();c.setInbox(su.onno.types.Ref.of(su.onno.crm.domain.Inbox.class,inbox));
        var m=new su.onno.crm.domain.ConversationMessage();m.setId(UUID.randomUUID());m.setConversation(su.onno.types.Ref.of(su.onno.crm.domain.Conversation.class,c.getId()));m.setBody("Hello");m.setSubject("recipient-change".equals(subject)?null:subject);
        if("recipient-change".equals(subject))m.setRecipient("work@example.com");
        org.mockito.Mockito.when(chats.findActiveById(c.getId())).thenReturn(Optional.of(c));
        org.mockito.Mockito.when(messages.findActiveById(m.getId())).thenReturn(Optional.of(m));
        org.mockito.Mockito.when(client.authorized()).thenReturn(true);
        var bridge=new GmailBridge(client,jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),org.mockito.Mockito.mock(su.onno.crm.repository.InboxRepository.class),chats,messages,org.mockito.Mockito.mock(su.onno.crm.service.CrmContactService.class),org.mockito.Mockito.mock(su.onno.crm.service.CrmWorkspaceService.class),org.mockito.Mockito.mock(su.onno.crm.repository.ContactIdentityRepository.class),org.mockito.Mockito.mock(su.onno.crm.service.CrmConversationStatuses.class),attachments,org.mockito.Mockito.mock(su.onno.crm.service.CrmConnectionAccounts.class));
        bridge.start();bridge.stop();org.springframework.test.util.ReflectionTestUtils.setField(bridge,"ready",true);
        jdbc.update("INSERT INTO onno_crm_gmail_account VALUES (?,?,?,TRUE,NULL)","agent@example.com",inbox,"1");
        jdbc.update("INSERT INTO onno_crm_gmail_thread (account,thread_id,conversation_id,reply_to,subject,reference_id,last_received) VALUES (?,?,?,?,?,?,?)","agent@example.com","old",c.getId(),"client@example.com","Booking","<original@example.com>",m.getSentAt().minusDays(1));
        boolean changed=subject!=null&&!subject.equals("Booking");
        var sent=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("id","sent").put("threadId",changed?"new":"old");
        org.mockito.Mockito.when(client.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(sent);
        bridge.enqueue(c,m);
        var account=((List<?>)org.springframework.test.util.ReflectionTestUtils.invokeMethod(bridge,"accounts")).getFirst();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(bridge,"deliver",account);
        var raw=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).send(raw.capture(),org.mockito.ArgumentMatchers.eq(changed?null:"old"));
        assertThat(parse(raw.getValue()).getSubject()).isEqualTo("recipient-change".equals(subject)?"Booking":changed?subject:"Re: Booking");
        assertThat(parse(raw.getValue()).getAllRecipients()[0].toString()).isEqualTo("recipient-change".equals(subject)?"work@example.com":"client@example.com");
        if(changed)assertThat(parse(raw.getValue()).getHeader("In-Reply-To",null)).isNull();
        assertThat(m.getDeliveryStatus()).isEqualTo(su.onno.crm.domain.DeliveryStatus.SENT);
        var next=new su.onno.crm.domain.ConversationMessage();next.setId(UUID.randomUUID());bridge.enqueue(c,next);
        assertThat(jdbc.queryForObject("SELECT thread_id FROM onno_crm_gmail_outbox WHERE message_id=?",String.class,next.getId())).isEqualTo(changed?"new":"old");
    }
    @Test void rejectsHeaderInjection() {
        assertThatThrownBy(()->GmailMail.compose("agent@example.com","client@example.com","Hi\r\nBcc: other@example.com","","Hello",List.of(),UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
}
