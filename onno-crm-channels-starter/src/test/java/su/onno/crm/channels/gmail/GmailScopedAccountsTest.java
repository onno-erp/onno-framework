package su.onno.crm.channels.gmail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class GmailScopedAccountsTest {
    @Test void multipleMailboxesKeepOwnershipCredentialsAndPauseStateSeparate() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");var jdbc=new JdbcTemplate(ds);
        var accounts=new CrmConnectionAccounts(jdbc,p->p.getName().equals("admin"),su.onno.crm.service.CrmFeatures.defaults().withPersonalAccounts(true));accounts.initialize();
        var personal=accounts.create("gmail",su.onno.crm.service.CrmConnectionAccounts.Scope.PERSONAL,"Mine",()->"alice");var shared=accounts.create("gmail",su.onno.crm.service.CrmConnectionAccounts.Scope.SHARED,"Team",()->"admin");
        var client=mock(GmailClient.class);var first=mock(GmailClient.class);var second=mock(GmailClient.class);
        when(client.forAccount(personal.id())).thenReturn(first);when(client.forAccount(shared.id())).thenReturn(second);when(client.configured()).thenReturn(true);when(first.authorized()).thenReturn(true);when(second.authorized()).thenReturn(true);
        var json=new ObjectMapper();var token1=json.createObjectNode().put("access_token","first");var token2=json.createObjectNode().put("access_token","second");
        when(client.profile(token1)).thenReturn(json.createObjectNode().put("emailAddress","alice@example.test"));when(client.profile(token2)).thenReturn(json.createObjectNode().put("emailAddress","team@example.test"));
        var inboxes=mock(InboxRepository.class);when(inboxes.save(any())).thenAnswer(i->{Inbox inbox=i.getArgument(0);if(inbox.getId()==null)inbox.setId(UUID.randomUUID());return inbox;});
        var bridge=new GmailBridge(client,jdbc,new DataSourceTransactionManager(ds),inboxes,mock(ConversationRepository.class),mock(ConversationMessageRepository.class),mock(CrmContactService.class),mock(CrmWorkspaceService.class),mock(ContactIdentityRepository.class),mock(CrmConversationStatuses.class),mock(CrmAttachments.class),accounts);
        bridge.start();try {
            bridge.connect(token1,personal.id());bridge.connect(token2,shared.id());
            // New mailboxes import files from their first sync, so they are never backfilled.
            assertThat(jdbc.queryForList("SELECT account FROM onno_crm_gmail_files_backfill_v2",String.class))
                    .containsExactlyInAnyOrder("alice@example.test","team@example.test");
            assertThat(bridge.view(personal).account()).isEqualTo("alice@example.test");assertThat(bridge.view(shared).account()).isEqualTo("team@example.test");
            assertThat(accounts.find(personal.id()).inboxId()).isNotEqualTo(accounts.find(shared.id()).inboxId());
            assertThat(accounts.hiddenInboxes(()->"admin")).containsExactly(accounts.find(personal.id()).inboxId());
            verify(first).saveTokens(token1);verify(second).saveTokens(token2);verify(client,never()).saveTokens(any());
            bridge.command(personal,"pause");assertThat(bridge.view(personal).state()).isEqualTo("PAUSED");assertThat(bridge.view(shared).state()).isEqualTo("CONNECTED");
            assertThatThrownBy(()->bridge.connect(token1,shared.id())).isInstanceOf(IllegalArgumentException.class);
            bridge.command(personal,"disconnect");verify(first).disconnect();verify(second,never()).disconnect();
        } finally {bridge.stop();}
    }
}
