package su.onno.crm.channels.gmail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import su.onno.crm.domain.*;
import su.onno.crm.service.CrmConnectionAccounts.Scope;
import su.onno.crm.service.CrmHistoryImport;
import su.onno.ui.media.MediaStorage;

class GmailHistoryImportTest {
    private static final String MAILBOX = "wedding@studio.test";

    @Test void importsOlderThreadsAsQuietHistoryAPageAtATime() throws Exception {
        try (var f = new GmailFixture()) {
            var gmail = historyMailbox(f, 2);
            UUID closed = UUID.randomUUID();
            when(f.statuses.close()).thenReturn(closed);
            var quiet = new ArrayList<Boolean>();
            doAnswer(call -> {
                ConversationMessage m = call.getArgument(0);
                if (m.getId() == null) m.setId(UUID.randomUUID());
                if (!f.messageStore.contains(m)) f.messageStore.add(m);
                quiet.add(CrmHistoryImport.active());
                return m;
            }).when(f.messages).save(any());
            when(gmail.get("threads?labelIds=INBOX&maxResults=2")).thenReturn(page(f, "p2", "t1", "t2"));
            when(gmail.get("threads?labelIds=INBOX&maxResults=2&pageToken=p2")).thenReturn(page(f, null, "t3"));
            // The studio wrote first; the couple answered. Gmail lists a thread oldest first.
            when(gmail.get("threads/t1?format=full")).thenReturn(thread(f,
                    message(f, "m0", "t1", 1_000, "Studio <wedding@studio.test>", "Venue ideas", "Here are three venues.", "SENT"),
                    message(f, "m1", "t1", 2_000, "Anna Couple <anna@example.com>", "Re: Venue ideas", "We love the second one.", "INBOX", "UNREAD")));
            when(gmail.get("threads/t2?format=full")).thenReturn(thread(f,
                    message(f, "m2", "t2", 3_000, "Shop <news@shop.test>", "Sale", "50% off", "INBOX", "CATEGORY_PROMOTIONS"),
                    message(f, "m3", "t2", 4_000, "Anna Couple <anna@example.com>", "Draft", "unsent", "DRAFT")));
            when(gmail.get("threads/t3?format=full")).thenReturn(thread(f,
                    message(f, "m4", "t3", 5_000, "Anna Couple <anna@example.com>", "Guests", "We are 80 guests.", "INBOX")));

            f.bridge.importHistory(f.account(MAILBOX));

            assertThat(f.conversationStore).hasSize(1);
            var conversation = f.conversationStore.values().iterator().next();
            assertThat(conversation.getStatus()).as("a conversation history starts is closed").isEqualTo(closed);
            assertThat(conversation.getUnreadCount()).as("history is read").isZero();
            var stored = f.messagesOf(conversation.getId());
            assertThat(stored).as("the couple's answer is stored first, so the studio's earlier email can join its thread")
                    .extracting(ConversationMessage::getKind).containsExactly(MessageKind.CUSTOMER_MESSAGE, MessageKind.AGENT_REPLY);
            assertThat(stored.stream().sorted(Comparator.comparing(ConversationMessage::getSentAt)).map(ConversationMessage::getExternalMessageId))
                    .containsExactly("gmail:m0", "gmail:" + MAILBOX + ":m1");
            assertThat(quiet).as("every save runs as a history import").containsOnly(true).hasSize(2);
            assertThat(progress(f)).containsEntry("label", "INBOX").containsEntry("page_token", "p2").containsEntry("messages", 2);
            assertThat(f.seen(MAILBOX, "m2")).as("promotions are left out").isFalse();
            assertThat(f.seen(MAILBOX, "m3")).as("drafts are left out").isFalse();

            f.bridge.importHistory(f.account(MAILBOX));

            assertThat(f.messagesOf(conversation.getId())).hasSize(3);
            assertThat(conversation.getUnreadCount()).isZero();
            assertThat(progress(f)).containsEntry("messages", 3).containsEntry("threads", 3);
            assertThat(progress(f).get("finished_at")).as("the last page finishes the import").isNotNull();

            f.bridge.importHistory(f.account(MAILBOX));
            verify(gmail, times(2)).get(startsWith("threads?"));
            verify(gmail, never()).get(contains("format=raw"));
        }
    }

    @Test void filesStayInGmailUntilSomeoneOpensThem() throws Exception {
        try (var f = new GmailFixture()) {
            var gmail = historyMailbox(f, 5);
            when(f.attachments.limit()).thenReturn(10);
            when(f.attachments.reference(anyString())).thenAnswer(call -> "/api/media/" + call.getArgument(0));
            when(f.attachments.store(anyList())).thenAnswer(call -> String.join("\n", call.<List<String>>getArgument(0)));
            var withFile = message(f, "m1", "t1", 2_000, "Anna <anna@example.com>", "Brochure", "See attached.", "INBOX");
            attach(withFile, "1", "Our brochure (final).pdf", "application/pdf", "first-id", 22_000_000);
            when(gmail.get("threads?labelIds=INBOX&maxResults=5")).thenReturn(page(f, null, "t1"));
            when(gmail.get("threads/t1?format=full")).thenReturn(thread(f, withFile));

            f.bridge.importHistory(f.account(MAILBOX));

            var message = f.messageStore.getFirst();
            assertThat(message.getAttachments()).matches("/api/media/gmail/[0-9a-f-]{36}/Our_brochure_final_.pdf");
            verify(gmail, never()).get(contains("/attachments/"));

            var storage = new GmailMediaFiles.Storage(mock(MediaStorage.class), provider(f.bridge));
            String key = message.getAttachments().substring("/api/media/".length());
            var loaded = storage.load(key).orElseThrow();
            assertThat(loaded.filename()).isEqualTo("Our brochure (final).pdf");
            assertThat(loaded.contentType()).isEqualTo("application/pdf");
            verify(gmail, never()).get(contains("/attachments/"));

            // Gmail hands out a new attachment id on every read, so the file is found again by its part.
            var current = message(f, "m1", "t1", 2_000, "Anna <anna@example.com>", "Brochure", "See attached.", "INBOX");
            attach(current, "1", "Our brochure (final).pdf", "application/pdf", "second-id", 7);
            when(gmail.get("messages/m1?format=full")).thenReturn(current);
            when(gmail.get("messages/m1/attachments/second-id")).thenReturn(f.json.createObjectNode()
                    .put("size", 7).put("data", Base64.getUrlEncoder().encodeToString("%PDF-1.".getBytes(StandardCharsets.US_ASCII))));
            assertThat(loaded.resource().contentLength()).isEqualTo(7);
            try (var bytes = loaded.resource().getInputStream()) {
                assertThat(new String(bytes.readAllBytes(), StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.");
            }
            verify(gmail, times(1)).get("messages/m1/attachments/second-id");
            assertThat(storage.load("gmail/" + UUID.randomUUID() + "/x.pdf")).as("an unknown file").isEmpty();
        }
    }

    @Test void otherMediaKeysReachTheApplicationStorage() {
        var delegate = mock(MediaStorage.class);
        var storage = new GmailMediaFiles.Storage(delegate, provider(null));
        storage.load("2026/11/a.png");
        verify(delegate).load("2026/11/a.png");
        assertThat(storage.load("gmail/" + UUID.randomUUID() + "/a.png")).isEmpty();
        verifyNoMoreInteractions(delegate);
    }

    @Test void waitsForTheFirstImportAndStaysOffUnlessEnabled() throws Exception {
        try (var f = new GmailFixture()) {
            var gmail = f.mailbox(MAILBOX, Scope.SHARED, null, "Wedding");
            f.bridge.importHistory(f.account(MAILBOX));
            f.properties.getGmail().getHistoryImport().setEnabled(true);
            f.jdbc.update("UPDATE onno_crm_gmail_account SET history_id=NULL WHERE account=?", MAILBOX);
            f.bridge.importHistory(f.account(MAILBOX));
            verify(gmail, never()).get(anyString());
        }
    }

    @Test void mailboxFailuresAreLeftToTheRoundAndAThreadGmailCannotReturnIsSkipped() throws Exception {
        try (var f = new GmailFixture()) {
            var gmail = historyMailbox(f, 5);
            when(gmail.get("threads?labelIds=INBOX&maxResults=5"))
                    .thenThrow(new GmailClient.ApiFailure(500, "backendError", null, null))
                    .thenThrow(new GmailClient.ApiFailure(429, "rateLimitExceeded", Duration.ofMinutes(5), null))
                    .thenReturn(page(f, null, "gone", "t1"));
            for (int status : new int[] {500, 429})
                assertThatThrownBy(() -> f.bridge.importHistory(f.account(MAILBOX)))
                        .isInstanceOf(GmailClient.ApiFailure.class).extracting("status").isEqualTo(status);
            assertThat(progress(f)).containsEntry("messages", 0).containsEntry("page_token", null).containsEntry("finished_at", null);

            when(gmail.get("threads/gone?format=full")).thenThrow(new GmailClient.ApiFailure(404, "notFound", null, null));
            when(gmail.get("threads/t1?format=full")).thenReturn(thread(f,
                    message(f, "m1", "t1", 1_000, "Anna <anna@example.com>", "Hello", "We are 80 guests.", "INBOX")));
            f.bridge.importHistory(f.account(MAILBOX));
            assertThat(progress(f)).containsEntry("messages", 1).containsEntry("threads", 2);
            assertThat(progress(f).get("finished_at")).isNotNull();
        }
    }

    @Test void anExpiredPageStartsTheLabelAgain() throws Exception {
        try (var f = new GmailFixture()) {
            var gmail = historyMailbox(f, 5);
            when(gmail.get("threads?labelIds=INBOX&maxResults=5")).thenReturn(f.json.createObjectNode().put("resultSizeEstimate", 0));
            f.bridge.importHistory(f.account(MAILBOX)); // an empty label finishes at once
            f.jdbc.update("UPDATE onno_crm_gmail_history_import SET page_token='old',finished_at=NULL WHERE account=?", MAILBOX);
            when(gmail.get("threads?labelIds=INBOX&maxResults=5&pageToken=old")).thenThrow(new GmailClient.ApiFailure(400, "invalidArgument", null, null));
            f.bridge.importHistory(f.account(MAILBOX));
            assertThat(progress(f)).containsEntry("page_token", null).containsEntry("finished_at", null);
        }
    }

    @Test void readsGmailsFullFormatLikeRawMime() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var message = json.createObjectNode().put("id", "m").put("internalDate", "1700000000000");
        var payload = message.putObject("payload").put("mimeType", "multipart/mixed").put("partId", "");
        headers(payload, "From", "=?UTF-8?B?0JDQvdC90LA=?= <anna@example.com>", "Reply-To", "planner@example.com",
                "Subject", "Свадьба в июне", "Message-ID", "<abc@example.com>");
        var alternative = payload.putArray("parts").addObject().put("mimeType", "multipart/alternative").put("partId", "0");
        var parts = alternative.putArray("parts");
        var plain = parts.addObject().put("mimeType", "text/plain").put("partId", "0.0");
        headers(plain, "Content-Type", "text/plain; charset=KOI8-R");
        plain.putObject("body").put("data", data("Привет! Нас будет 80.", Charset.forName("KOI8-R")));
        var html = parts.addObject().put("mimeType", "text/html").put("partId", "0.1");
        // Gmail sends text as UTF-8 even when the part declares another charset.
        headers(html, "Content-Type", "text/html; charset=windows-1252");
        html.putObject("body").put("data", data("<p onclick=\"x()\">Привет! <b>80</b></p><script>alert(1)</script>", StandardCharsets.UTF_8));
        attach(message, "1", "plan.pdf", "application/pdf", "att", 1234);
        var logo = ((ArrayNode) payload.path("parts")).addObject().put("mimeType", "image/png").put("partId", "2").put("filename", "");
        headers(logo, "Content-Disposition", "inline", "Content-ID", "<logo@sig>");
        logo.putObject("body").put("attachmentId", "logo").put("size", 300);

        var mail = GmailMail.readFull(message);

        assertThat(mail.sender()).isEqualTo("anna@example.com");
        assertThat(mail.name()).isEqualTo("Анна");
        assertThat(mail.replyTo()).isEqualTo("planner@example.com");
        assertThat(mail.subject()).isEqualTo("Свадьба в июне");
        assertThat(mail.messageId()).isEqualTo("<abc@example.com>");
        assertThat(mail.text()).contains("Привет! Нас будет 80.").contains("[Attachment: plan.pdf]");
        assertThat(mail.html()).contains("Привет!").doesNotContain("script").doesNotContain("onclick");
        assertThat(mail.files()).isEmpty();
        assertThat(mail.remote()).extracting(GmailMail.RemoteFile::partId, GmailMail.RemoteFile::filename, GmailMail.RemoteFile::contentId)
                .containsExactly(tuple("1", "plan.pdf", null), tuple("2", "attachment", "logo@sig"));
        assertThat(mail.sentAt().toEpochMilli()).isEqualTo(1_700_000_000_000L);
    }

    @Test void mediaNamesKeepToWhatAMediaKeyAllows() {
        assertThat(GmailBridge.mediaName("Договор (итог).pdf")).isEqualTo("_.pdf");
        assertThat(GmailBridge.mediaName("../../etc/passwd")).doesNotContain("..").doesNotContain("/");
        assertThat(GmailBridge.mediaName("")).isEqualTo("file");
    }

    private static GmailClient historyMailbox(GmailFixture f, int threadsPerRound) {
        var options = f.properties.getGmail().getHistoryImport();
        options.setEnabled(true);
        options.setThreadsPerRound(threadsPerRound);
        return f.mailbox(MAILBOX, Scope.SHARED, null, "Wedding");
    }

    private static Map<String, Object> progress(GmailFixture f) {
        var row = f.jdbc.queryForMap("SELECT label,page_token,threads,messages,finished_at FROM onno_crm_gmail_history_import WHERE account=?", MAILBOX);
        var lower = new HashMap<String, Object>();
        row.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
        return lower;
    }

    private static ObjectNode page(GmailFixture f, String next, String... threads) {
        var page = f.json.createObjectNode();
        var list = page.putArray("threads");
        for (String id : threads) list.addObject().put("id", id);
        if (next != null) page.put("nextPageToken", next);
        return page;
    }

    private static ObjectNode thread(GmailFixture f, ObjectNode... messages) {
        var thread = f.json.createObjectNode();
        var list = thread.putArray("messages");
        for (var m : messages) list.add(m);
        return thread;
    }

    private static ObjectNode message(GmailFixture f, String id, String thread, long date, String from, String subject, String text, String... labels) {
        var m = f.json.createObjectNode().put("id", id).put("threadId", thread).put("internalDate", String.valueOf(date));
        var ids = m.putArray("labelIds");
        for (String label : labels) ids.add(label);
        var payload = m.putObject("payload").put("mimeType", "multipart/mixed").put("partId", "");
        headers(payload, "From", from, "Subject", subject, "Message-ID", "<" + id + "@example.com>");
        var plain = payload.putArray("parts").addObject().put("mimeType", "text/plain").put("partId", "0");
        headers(plain, "Content-Type", "text/plain; charset=UTF-8");
        plain.putObject("body").put("data", data(text, StandardCharsets.UTF_8));
        return m;
    }

    private static void attach(ObjectNode message, String partId, String filename, String type, String attachmentId, long size) {
        var part = ((ArrayNode) message.path("payload").path("parts")).addObject().put("mimeType", type).put("partId", partId).put("filename", filename);
        headers(part, "Content-Disposition", "attachment; filename=\"" + filename + "\"");
        part.putObject("body").put("attachmentId", attachmentId).put("size", size);
    }

    private static void headers(ObjectNode part, String... pairs) {
        var list = part.has("headers") ? (ArrayNode) part.get("headers") : part.putArray("headers");
        for (int i = 0; i < pairs.length; i += 2) list.addObject().put("name", pairs[i]).put("value", pairs[i + 1]);
    }

    private static String data(String text, Charset charset) {
        return Base64.getUrlEncoder().encodeToString(text.getBytes(charset));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GmailBridge> provider(GmailBridge bridge) {
        ObjectProvider<GmailBridge> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bridge);
        return provider;
    }
}
