package su.onno.crm.channels.gmail;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class GmailLargeEmailTest {
    /** A 20 MB email as Gmail returns it ({@code format=raw}): about 27 million base64 characters in one field. */
    private static final String LARGE_EMAIL="{\"id\":\"m1\",\"raw\":\""+"QUJD".repeat(7_000_000)+"\"}";
    private static final String MAILBOX="team@example.test";

    @Test void readsAnEmailTooLargeForJacksonsDefaultStringLimit() throws Exception {
        var shared=new ObjectMapper();int limit=shared.getFactory().streamReadConstraints().getMaxStringLength();
        assertThatThrownBy(()->shared.readTree(LARGE_EMAIL)).isInstanceOf(StreamConstraintsException.class);
        assertThat(GmailClient.wholeEmails(shared).readTree(LARGE_EMAIL).path("raw").asText()).hasSize(28_000_000);
        assertThat(shared.getFactory().streamReadConstraints().getMaxStringLength()).as("shared mapper untouched").isEqualTo(limit);
        var roomy=GmailClient.wholeEmails(shared);assertThat(GmailClient.wholeEmails(roomy)).isSameAs(roomy);
    }

    @Test void waitsAsLongAsGmailAsks() {
        var now=Instant.parse("2026-11-10T12:00:00Z");
        assertThat(GmailClient.retryAt("120",null,now)).isEqualTo(now.plusSeconds(120));
        String body="{\"error\":{\"code\":429,\"message\":\"User-rate limit exceeded.  Retry after 2026-11-10T12:06:16.168Z\"}}";
        assertThat(GmailClient.retryAt(null,body,now)).isEqualTo(Instant.parse("2026-11-10T12:06:16.168Z"));
        assertThat(GmailClient.retryAt(null,"{}",now)).isEqualTo(now.plusSeconds(60));
        assertThat(GmailClient.retryAt(null,body,Instant.parse("2026-11-10T13:00:00Z"))).as("a time already past").isEqualTo(Instant.parse("2026-11-10T13:01:00Z"));
    }

    @Test void skipsAnUnreadableEmailAfterRepeatedRoundsSoTheMailboxFinishesItsImport() throws Exception {
        var json=new ObjectMapper();var client=mock(GmailClient.class);when(client.authorized()).thenReturn(true);
        when(client.get("profile")).thenReturn(json.readTree("{\"historyId\":\"7\"}"));
        when(client.get("messages?labelIds=INBOX&maxResults=50")).thenReturn(json.readTree("{\"messages\":[{\"id\":\"big\"}]}"));
        when(client.get("messages/big?format=raw")).thenThrow(new GmailClient.ApiFailure(200,"StreamConstraintsException",null,true));
        withBridge(client,(bridge,jdbc)->{
            for(int round=1;round<GmailBridge.IMPORT_ATTEMPTS;round++) {
                bridge.tick();
                assertThat(history(jdbc)).as("round %d keeps retrying",round).isNull();
                assertThat(seen(jdbc)).isEmpty();
            }
            bridge.tick();
            assertThat(seen(jdbc)).containsExactly("big");
            assertThat(history(jdbc)).isEqualTo("7");
        });
    }

    @Test void networkFailuresNeverCountAgainstAnEmail() throws Exception {
        var json=new ObjectMapper();var client=mock(GmailClient.class);when(client.authorized()).thenReturn(true);
        when(client.get("profile")).thenReturn(json.readTree("{\"historyId\":\"7\"}"));
        when(client.get("messages?labelIds=INBOX&maxResults=50")).thenReturn(json.readTree("{\"messages\":[{\"id\":\"m1\"}]}"));
        when(client.get("messages/m1?format=raw")).thenThrow(new GmailClient.ApiFailure(0,"HttpTimeoutException",null,false));
        withBridge(client,(bridge,jdbc)->{
            for(int round=0;round<GmailBridge.IMPORT_ATTEMPTS+2;round++)bridge.tick();
            assertThat(seen(jdbc)).isEmpty();assertThat(history(jdbc)).isNull();
        });
    }

    @Test void leavesARateLimitedMailboxAloneUntilGmailAllowsRequestsAgain() throws Exception {
        var client=mock(GmailClient.class);when(client.authorized()).thenReturn(true);
        when(client.get("profile")).thenThrow(new GmailClient.ApiFailure(429,"rateLimitExceeded",Instant.now().plusSeconds(3600),false));
        withBridge(client,(bridge,jdbc)->{
            bridge.tick();bridge.tick();bridge.tick();
            verify(client,times(1)).get("profile");
        });
    }

    private interface Rounds { void run(GmailBridge bridge,JdbcTemplate jdbc) throws Exception; }
    private static void withBridge(GmailClient client,Rounds rounds) throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");var jdbc=new JdbcTemplate(ds);
        var accounts=new CrmConnectionAccounts(jdbc,p->true,CrmFeatures.defaults());accounts.initialize();
        var bridge=new GmailBridge(client,jdbc,new DataSourceTransactionManager(ds),mock(InboxRepository.class),mock(ConversationRepository.class),mock(ConversationMessageRepository.class),mock(CrmContactService.class),mock(CrmWorkspaceService.class),mock(ContactIdentityRepository.class),mock(CrmConversationStatuses.class),mock(CrmAttachments.class),accounts);
        bridge.start();((ScheduledExecutorService)ReflectionTestUtils.getField(bridge,"worker")).shutdownNow(); // rounds run only when the test calls tick()
        try {
            jdbc.update("INSERT INTO onno_crm_gmail_account (account,inbox_id,history_id,active) VALUES (?,?,NULL,TRUE)",MAILBOX,UUID.randomUUID());
            jdbc.update("INSERT INTO onno_crm_gmail_html_backfill VALUES (?)",MAILBOX);jdbc.update("INSERT INTO onno_crm_gmail_files_backfill_v2 VALUES (?)",MAILBOX);
            rounds.run(bridge,jdbc);
        } finally {bridge.stop();}
    }
    private static String history(JdbcTemplate jdbc) { return jdbc.queryForObject("SELECT history_id FROM onno_crm_gmail_account WHERE account=?",String.class,MAILBOX); }
    private static List<String> seen(JdbcTemplate jdbc) { return jdbc.queryForList("SELECT external_id FROM onno_crm_gmail_seen WHERE account=?",String.class,MAILBOX); }
}
