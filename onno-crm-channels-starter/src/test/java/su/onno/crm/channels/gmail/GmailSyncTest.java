package su.onno.crm.channels.gmail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import su.onno.crm.domain.*;
import su.onno.crm.service.CrmConnectionAccounts.Scope;

/** Sync that keeps going past a bad message, backs off when Gmail asks, and imports more than 50 emails. */
class GmailSyncTest {
    private static final String HISTORY = "history?historyTypes=messageAdded&historyTypes=labelAdded&startHistoryId=1&maxResults=100";

    @Test void aMessageThatCannotBeReadIsRetriedThenSkippedWithoutBlockingTheRest() throws Exception {
        try (var f = new GmailFixture()) {
            var gmail = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            when(gmail.get(HISTORY)).thenReturn(f.json.readTree("""
                {"historyId":"9","history":[{"messagesAdded":[
                  {"message":{"id":"huge","labelIds":["INBOX"]}},
                  {"message":{"id":"fine","labelIds":["INBOX"]}}]}]}"""));
            when(gmail.get("messages/huge?format=raw")).thenThrow(new GmailClient.ApiFailure(200, null, null, "StreamConstraintsException"));
            when(gmail.get("messages/fine?format=raw")).thenReturn(f.raw("client@example.com", "info@studio.test", "Hello", "Hi there", "t-fine", 1_000_000L, "INBOX", "UNREAD"));
            var account = f.account("info@studio.test");

            ReflectionTestUtils.invokeMethod(f.bridge, "sync", account);
            assertThat(f.seen("info@studio.test", "fine")).isTrue();
            assertThat(f.seen("info@studio.test", "huge")).isFalse();
            assertThat(f.jdbc.queryForObject("SELECT history_id FROM onno_crm_gmail_account WHERE account=?", String.class, "info@studio.test")).isEqualTo("9");
            assertThat(f.jdbc.queryForMap("SELECT attempts,last_failure FROM onno_crm_gmail_failed WHERE external_id='huge'"))
                    .containsEntry("ATTEMPTS", 1).containsEntry("LAST_FAILURE", "200 StreamConstraintsException");

            // Retried once its wait is over; the third failure records it as seen and skipped.
            for (int attempt = 2; attempt <= GmailBridge.MAX_ATTEMPTS; attempt++) {
                f.jdbc.update("UPDATE onno_crm_gmail_failed SET retry_at=NULL");
                ReflectionTestUtils.invokeMethod(f.bridge, "retryFailed", account);
            }
            assertThat(f.seen("info@studio.test", "huge")).isTrue();
            verify(gmail, times(GmailBridge.MAX_ATTEMPTS)).get("messages/huge?format=raw");
            f.jdbc.update("UPDATE onno_crm_gmail_failed SET retry_at=NULL");
            ReflectionTestUtils.invokeMethod(f.bridge, "retryFailed", account);
            verify(gmail, times(GmailBridge.MAX_ATTEMPTS)).get("messages/huge?format=raw");
        }
    }

    @Test void networkFailuresAndGmailOutagesNeverCountAgainstAnEmail() throws Exception {
        try (var f = new GmailFixture()) {
            var gmail = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            when(gmail.get("messages/m1?format=raw")).thenThrow(new GmailClient.ApiFailure(0, null, null, "HttpTimeoutException"));
            when(gmail.get("messages/m2?format=raw")).thenThrow(new GmailClient.ApiFailure(503, "backendError", null, null));
            var account = f.account("info@studio.test");
            for (int round = 0; round < GmailBridge.MAX_ATTEMPTS + 2; round++)
                for (String id : List.of("m1", "m2"))
                    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(f.bridge, "ingest", account, id)).isInstanceOf(GmailClient.ApiFailure.class);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_gmail_failed", Integer.class)).isZero();
            assertThat(f.seen("info@studio.test", "m1")).isFalse();
            assertThat(f.seen("info@studio.test", "m2")).isFalse();
        }
    }

    @Test void mimeThatCannotBeParsedIsCountedNotThrown() throws Exception {
        try (var f = new GmailFixture()) {
            var gmail = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var broken = f.json.createObjectNode().put("raw", Base64.getUrlEncoder().encodeToString("no headers at all".getBytes())).put("threadId", "t");
            when(gmail.get("messages/broken?format=raw")).thenReturn(broken);
            ReflectionTestUtils.invokeMethod(f.bridge, "ingest", f.account("info@studio.test"), "broken");
            assertThat(f.jdbc.queryForObject("SELECT attempts FROM onno_crm_gmail_failed WHERE external_id='broken'", Integer.class)).isEqualTo(1);
            assertThat(f.messageStore).isEmpty();
        }
    }

    @Test void aRateLimitPausesOnlyThatMailboxUntilRetryAfter() throws Exception {
        try (var f = new GmailFixture()) {
            var limited = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var other = f.mailbox("office@studio.test", Scope.SHARED, null, "Office");
            when(limited.get(HISTORY)).thenThrow(new GmailClient.ApiFailure(429, "rateLimitExceeded", Duration.ofMinutes(7), null));
            when(other.get(HISTORY)).thenReturn(f.json.readTree("{\"historyId\":\"1\"}"));
            when(other.get(startsWith("messages?labelIds=INBOX&maxResults=50"))).thenReturn(f.json.createObjectNode());
            when(limited.get(startsWith("messages?labelIds=INBOX&maxResults=50"))).thenReturn(f.json.createObjectNode());

            f.bridge.tick();
            f.bridge.tick();

            verify(limited, times(1)).get(HISTORY);
            verify(other, times(2)).get(HISTORY);
            assertThat(f.bridge.paused(f.account("info@studio.test"))).isTrue();
            assertThat(f.bridge.paused(f.account("office@studio.test"))).isFalse();
            // A pause is not an error that needs a reconnect.
            var record = f.accounts.visible(() -> "admin").stream().filter(a -> a.label().equals("Info")).findFirst().orElseThrow();
            assertThat(f.bridge.view(record).state()).isEqualTo("CONNECTED");
            assertThat(f.bridge.view(record).description()).contains("slow down");
        }
    }

    @Test void withoutRetryAfterTheBackoffDoublesUpToAnHour() {
        assertThat(GmailBridge.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(GmailBridge.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(GmailBridge.backoff(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(GmailBridge.backoff(20)).isEqualTo(Duration.ofHours(1));
    }

    @Test void firstImportPagesThroughTheInboxUpToTheConfiguredLimitAndAge() throws Exception {
        try (var f = new GmailFixture()) {
            f.properties.getGmail().setInitialImportLimit(3);
            f.properties.getGmail().setInitialImportDays(30);
            var gmail = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            f.jdbc.update("UPDATE onno_crm_gmail_account SET history_id=NULL");
            when(gmail.get("profile")).thenReturn(f.json.readTree("{\"historyId\":\"77\"}"));
            when(gmail.get("messages?labelIds=INBOX&maxResults=3&q=newer_than%3A30d"))
                    .thenReturn(f.json.readTree("{\"messages\":[{\"id\":\"m1\"},{\"id\":\"m2\"}],\"nextPageToken\":\"p2\"}"));
            when(gmail.get("messages?labelIds=INBOX&maxResults=1&q=newer_than%3A30d&pageToken=p2"))
                    .thenReturn(f.json.readTree("{\"messages\":[{\"id\":\"m3\"}],\"nextPageToken\":\"p3\"}"));
            for (String id : List.of("m1", "m2", "m3"))
                when(gmail.get("messages/" + id + "?format=raw")).thenReturn(f.raw("client@example.com", "info@studio.test",
                        "Hello " + id, "Body " + id, "t-" + id, 1_000_000L, "INBOX"));
            ReflectionTestUtils.invokeMethod(f.bridge, "sync", f.account("info@studio.test"));

            assertThat(List.of("m1", "m2", "m3")).allMatch(id -> f.seen("info@studio.test", id));
            verify(gmail, never()).get(contains("pageToken=p3"));
            assertThat(f.jdbc.queryForObject("SELECT history_id FROM onno_crm_gmail_account WHERE account=?", String.class, "info@studio.test")).isEqualTo("77");
            // Mail already read in Gmail is imported as read.
            assertThat(f.conversationStore.values()).allMatch(c -> c.getUnreadCount() == 0);
        }
    }

    @Test void mailMovedBackToTheInboxIsImported() throws Exception {
        try (var f = new GmailFixture()) {
            var gmail = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            when(gmail.get(HISTORY)).thenReturn(f.json.readTree("""
                {"historyId":"3","history":[{"labelsAdded":[
                  {"message":{"id":"unarchived"},"labelIds":["INBOX"]},
                  {"message":{"id":"starred"},"labelIds":["STARRED"]}]}]}"""));
            when(gmail.get("messages/unarchived?format=raw")).thenReturn(f.raw("client@example.com", "info@studio.test",
                    "Old question", "Still relevant", "t-old", 1_000_000L, "INBOX"));
            ReflectionTestUtils.invokeMethod(f.bridge, "sync", f.account("info@studio.test"));
            assertThat(f.seen("info@studio.test", "unarchived")).isTrue();
            verify(gmail, never()).get("messages/starred?format=raw");
        }
    }

    @Test void expiredHistoryRescansInboxAndSentMailSinceTheLatestImport() throws Exception {
        try (var f = new GmailFixture()) {
            var gmail = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var conversation = f.conversation("info@studio.test", "t-known", "client@example.com");
            long since = f.jdbc.queryForObject("SELECT last_received FROM onno_crm_gmail_thread", java.sql.Timestamp.class).toInstant()
                    .minus(Duration.ofDays(1)).getEpochSecond();
            when(gmail.get(HISTORY)).thenThrow(new GmailClient.ApiFailure(404));
            when(gmail.get("profile")).thenReturn(f.json.readTree("{\"historyId\":\"500\"}"));
            when(gmail.get("messages?labelIds=INBOX&maxResults=100&q=after%3A" + since))
                    .thenReturn(f.json.readTree("{\"messages\":[{\"id\":\"gap\"}]}"));
            when(gmail.get("messages?labelIds=SENT&maxResults=100&q=after%3A" + since))
                    .thenReturn(f.json.readTree("{\"messages\":[{\"id\":\"sent-from-gmail\"}]}"));
            when(gmail.get("messages/gap?format=raw")).thenReturn(f.raw("client@example.com", "info@studio.test",
                    "Re: Booking", "Sent while sync was off", "t-known", 2_000_000_000_000L, "INBOX", "UNREAD"));
            when(gmail.get("messages/sent-from-gmail?format=raw")).thenReturn(f.raw("info@studio.test", "client@example.com",
                    "Re: Booking", "Answered from Gmail", "t-known", 2_000_000_100_000L, "SENT"));
            ReflectionTestUtils.invokeMethod(f.bridge, "sync", f.account("info@studio.test"));

            assertThat(f.messagesOf(conversation.getId())).extracting(ConversationMessage::getBody)
                    .containsExactly("Sent while sync was off", "Answered from Gmail");
            assertThat(f.jdbc.queryForObject("SELECT history_id FROM onno_crm_gmail_account WHERE account=?", String.class, "info@studio.test")).isEqualTo("500");
        }
    }
}
