package su.onno.crm.channels.gmail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import su.onno.crm.domain.*;
import su.onno.crm.service.CrmConnectionAccounts.Scope;
import su.onno.types.Ref;

/** Who else is on an email: To and Cc read from mail, suggested for a reply (reply-all), and sent as Cc. */
class GmailRecipientsTest {
    private static MimeMessage parse(String raw) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
    }

    /** Raw MIME as Gmail's {@code format=raw} carries it: To and Cc with an encoded name. */
    private static String received(String from, String to, String cc) throws Exception {
        var message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(from));
        message.setHeader("To", to);
        if (cc != null) message.setHeader("Cc", cc);
        message.setSubject("Venue visit");
        message.setText("See you there", "UTF-8");
        message.saveChanges();
        var out = new ByteArrayOutputStream();
        message.writeTo(out);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    @Test void readsToAndCcFromRawMail() throws Exception {
        var mail = GmailMail.read(received("venue@villa.test",
                "Studio <info@studio.test>, =?UTF-8?B?Sm9zw6kgR2FyY8OtYQ==?= <jose@villa.test>",
                "\"Rossi, Marco\" <marco@villa.test>, giulia@villa.test, undisclosed-recipients:;"));
        assertThat(mail.to()).containsExactly("Studio <info@studio.test>", "José García <jose@villa.test>");
        assertThat(mail.cc()).containsExactly("\"Rossi, Marco\" <marco@villa.test>", "giulia@villa.test");
        assertThat(su.onno.crm.service.EmailAddresses.split(su.onno.crm.service.EmailAddresses.join(mail.cc()))).isEqualTo(mail.cc());
    }

    @Test void mailWithoutCcOrWithAnUnreadableHeaderStillParses() throws Exception {
        var mail = GmailMail.read(received("venue@villa.test", "info@studio.test", null));
        assertThat(mail.to()).containsExactly("info@studio.test");
        assertThat(mail.cc()).isEmpty();
        assertThat(GmailMail.read(received("venue@villa.test", "info@studio.test", "\"unterminated <x@y.test>")).text()).isEqualTo("See you there");
    }

    @Test void readsToAndCcFromGmailsFullFormat() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
            {"id":"m1","threadId":"t1","internalDate":"1700000000000","payload":{"mimeType":"text/plain",
              "headers":[{"name":"From","value":"venue@villa.test"},{"name":"To","value":"info@studio.test"},
                         {"name":"cc","value":"=?UTF-8?Q?Zo=C3=AB?= <zoe@villa.test>, Ben <ben@villa.test>"},
                         {"name":"Subject","value":"Visit"}],
              "body":{"size":5,"data":"%s"}}}
            """.formatted(Base64.getUrlEncoder().encodeToString("Hello".getBytes(StandardCharsets.UTF_8))));
        var mail = GmailMail.readFull(json);
        assertThat(mail.to()).containsExactly("info@studio.test");
        assertThat(mail.cc()).containsExactly("Zoë <zoe@villa.test>", "Ben <ben@villa.test>");
    }

    @Test void composePutsTheCcInTheMimeAndRefusesHeaderInjection() throws Exception {
        var sent = parse(GmailMail.compose("info@studio.test", "client@example.com", "Visit", "", "Hello", null, List.of(), UUID.randomUUID(), null,
                List.of("Zoë Martin <zoe@villa.test>", "ben@villa.test")));
        assertThat(sent.getRecipients(Message.RecipientType.TO)).extracting(Object::toString).containsExactly("client@example.com");
        var cc = (InternetAddress[]) sent.getRecipients(Message.RecipientType.CC);
        assertThat(cc).extracting(InternetAddress::getAddress).containsExactly("zoe@villa.test", "ben@villa.test");
        assertThat(cc[0].getPersonal()).isEqualTo("Zoë Martin");
        assertThat(parse(GmailMail.compose("info@studio.test", "client@example.com", "Visit", "", "Hello", List.of(), UUID.randomUUID()))
                .getRecipients(Message.RecipientType.CC)).isNull();

        for (String bad : List.of("ben@villa.test\r\nBcc: spy@example.com", "ben@villa.test, spy@example.com", "group: a@b.test;", "not an address"))
            assertThatThrownBy(() -> GmailMail.compose("info@studio.test", "client@example.com", "Visit", "", "Hello", null, List.of(),
                    UUID.randomUUID(), null, List.of(bad))).as(bad).isInstanceOf(Exception.class);
    }

    @Test void receivedMailKeepsItsRecipientsAndSuggestsReplyAll() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            f.mailbox("events@studio.test", Scope.SHARED, null, "Events");
            var conversation = f.conversation("info@studio.test", "t1", "venue@villa.test");
            assertThat(f.bridge.connection(conversation).cc()).isEmpty();

            var raw = f.json.createObjectNode().put("raw", received("venue@villa.test",
                    "Studio <INFO@studio.test>, Marco <marco@villa.test>",
                    "events@studio.test, venue@villa.test, giulia@villa.test, MARCO@villa.test"))
                    .put("threadId", "t1").put("internalDate", "3000000000000");
            raw.putArray("labelIds").add("INBOX").add("UNREAD");
            when(info.get("messages/m1?format=raw")).thenReturn(raw);
            ReflectionTestUtils.invokeMethod(f.bridge, "ingest", f.account("info@studio.test"), "m1");

            var stored = f.messagesOf(conversation.getId()).getLast();
            assertThat(stored.getSentTo()).isEqualTo("Studio <INFO@studio.test>, Marco <marco@villa.test>");
            assertThat(stored.getCopyTo()).isEqualTo("events@studio.test, venue@villa.test, giulia@villa.test, MARCO@villa.test");
            // Not the studio's own mailboxes, not the recipient, each person once.
            assertThat(f.bridge.connection(conversation).cc()).containsExactly("Marco <marco@villa.test>", "giulia@villa.test");
            assertThat(f.bridge.connection(conversation, () -> "alice").cc()).containsExactly("Marco <marco@villa.test>", "giulia@villa.test");
        }
    }

    @Test void aQueuedReplySendsItsCcAndSendsItAgainOnRetry() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var conversation = f.conversation("info@studio.test", "t1", "client@example.com");
            when(info.send(anyString(), any()))
                    .thenThrow(new GmailClient.ApiFailure(500, "backendError", null, null))
                    .thenReturn(f.json.createObjectNode().put("id", "sent-1").put("threadId", "t1"));
            var reply = new ConversationMessage();
            reply.setId(UUID.randomUUID());
            reply.setConversation(Ref.of(Conversation.class, conversation.getId()));
            reply.setBody("Copying Marco");
            reply.setRecipient("client@example.com");
            reply.setCopyTo("\"Rossi, Marco\" <marco@villa.test>, giulia@villa.test");
            f.messageStore.add(reply);

            f.bridge.enqueue(conversation, reply);
            ReflectionTestUtils.invokeMethod(f.bridge, "deliver", f.account("info@studio.test"));
            assertThat(reply.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
            f.bridge.enqueue(conversation, reply); // the retry command re-queues the same message
            ReflectionTestUtils.invokeMethod(f.bridge, "deliver", f.account("info@studio.test"));
            assertThat(reply.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);

            var raw = ArgumentCaptor.forClass(String.class);
            verify(info, times(2)).send(raw.capture(), eq("t1"));
            for (String attempt : raw.getAllValues()) {
                var cc = (InternetAddress[]) parse(attempt).getRecipients(Message.RecipientType.CC);
                assertThat(cc).extracting(InternetAddress::getAddress).containsExactly("marco@villa.test", "giulia@villa.test");
                assertThat(cc[0].getPersonal()).isEqualTo("Rossi, Marco");
            }
        }
    }

    @Test void aNewThreadStillCopiesTheChosenCc() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var conversation = f.conversation("info@studio.test", "t1", "client@example.com");
            when(info.send(anyString(), isNull())).thenReturn(f.json.createObjectNode().put("id", "sent-1").put("threadId", "t2"));
            var reply = new ConversationMessage();
            reply.setId(UUID.randomUUID());
            reply.setConversation(Ref.of(Conversation.class, conversation.getId()));
            reply.setBody("New subject");
            reply.setSubject("Contract");
            reply.setRecipient("client@example.com");
            reply.setCopyTo("giulia@villa.test");
            f.messageStore.add(reply);
            f.bridge.enqueue(conversation, reply);
            ReflectionTestUtils.invokeMethod(f.bridge, "deliver", f.account("info@studio.test"));
            var raw = ArgumentCaptor.forClass(String.class);
            verify(info).send(raw.capture(), isNull());
            assertThat(parse(raw.getValue()).getRecipients(Message.RecipientType.CC)).extracting(Object::toString).containsExactly("giulia@villa.test");
        }
    }
}
