package su.onno.crm.channels.gmail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import su.onno.crm.domain.*;
import su.onno.crm.service.CrmConnectionAccounts.Scope;
import su.onno.types.Ref;

/** The sending mailbox's Gmail signature under outgoing email (onno.crm.channels.gmail.signature). */
class GmailSignatureTest {
    private static final String SIGNATURE =
            "<div>Anna Petrova<br>Studio <b>Wedding</b></div><img src=\"https://lh3.googleusercontent.com/logo.png\" width=\"120\">";

    private static MimeMessage parse(String raw) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
    }

    private static BodyPart alternative(Multipart alternatives, String type) throws Exception {
        for (int i = 0; i < alternatives.getCount(); i++) if (alternatives.getBodyPart(i).isMimeType(type)) return alternatives.getBodyPart(i);
        throw new AssertionError("no " + type + " part");
    }

    @Test void aSignedEmailCarriesTheSignatureAsHtmlAndAsText() throws Exception {
        var sent = parse(GmailMail.compose("wedding@studio.test", "client@example.com", "Booking", "",
                "Hello <Anna> & co,\nsee you Monday.", List.of(), UUID.randomUUID(), SIGNATURE));
        assertThat(sent.isMimeType("multipart/alternative")).isTrue();
        var alternatives = (Multipart) sent.getContent();

        String plain = alternative(alternatives, "text/plain").getContent().toString();
        assertThat(plain).startsWith("Hello <Anna> & co,\nsee you Monday.\n\n-- \n")
                .contains("Anna Petrova").contains("Studio Wedding").doesNotContain("<b>");

        String html = alternative(alternatives, "text/html").getContent().toString();
        assertThat(html).contains("Hello &lt;Anna&gt; &amp; co,<br>see you Monday.")
                .contains("<div class=\"gmail_signature\" data-smartmail=\"gmail_signature\">" + SIGNATURE + "</div>")
                .contains("https://lh3.googleusercontent.com/logo.png");
    }

    @Test void filesFollowTheSignedTextInAMixedEmail() throws Exception {
        var sent = parse(GmailMail.reply("wedding@studio.test", "client@example.com", "Booking", "<original@example.com>",
                "Contract attached", List.of(new GmailMail.Outgoing("contract.pdf", "application/pdf", new byte[]{1, 2, 3})),
                UUID.randomUUID(), SIGNATURE));
        assertThat(sent.getSubject()).isEqualTo("Re: Booking");
        assertThat(sent.isMimeType("multipart/mixed")).isTrue();
        var mixed = (Multipart) sent.getContent();
        assertThat(mixed.getBodyPart(0).isMimeType("multipart/alternative")).isTrue();
        assertThat(mixed.getBodyPart(1).getDisposition()).isEqualTo(Part.ATTACHMENT);
        assertThat(mixed.getBodyPart(1).getFileName()).isEqualTo("contract.pdf");
    }

    @Test void aFormattedReplyIsTheHtmlPartAboveTheSignatureAndTheTextIsThePlainPart() throws Exception {
        var sent = parse(GmailMail.compose("wedding@studio.test", "client@example.com", "Booking", "",
                "Hello Anna,\nsee you Monday.", "<p>Hello <b>Anna</b>,</p><p>see you <i>Monday</i>.</p>", List.of(), UUID.randomUUID(), SIGNATURE));
        var alternatives = (Multipart) sent.getContent();
        assertThat(alternative(alternatives, "text/plain").getContent().toString())
                .startsWith("Hello Anna,\nsee you Monday.\n\n-- \n").doesNotContain("<b>");
        assertThat(alternative(alternatives, "text/html").getContent().toString())
                .isEqualTo("<div dir=\"auto\"><p>Hello <b>Anna</b>,</p><p>see you <i>Monday</i>.</p></div><br>"
                        + "<div class=\"gmail_signature\" data-smartmail=\"gmail_signature\">" + SIGNATURE + "</div>");
    }

    @Test void aFormattedReplyWithoutASignatureStillHasBothParts() throws Exception {
        var sent = parse(GmailMail.reply("office@studio.test", "client@example.com", "Booking", "<original@example.com>",
                "Plain", "<b>Plain</b>", List.of(), UUID.randomUUID(), null));
        assertThat(sent.isMimeType("multipart/alternative")).isTrue();
        var alternatives = (Multipart) sent.getContent();
        assertThat(alternative(alternatives, "text/plain").getContent().toString()).isEqualTo("Plain");
        assertThat(alternative(alternatives, "text/html").getContent().toString()).isEqualTo("<div dir=\"auto\"><b>Plain</b></div>");
    }

    @Test void withoutASignatureTheEmailIsPlainTextAsBefore() throws Exception {
        for (String none : new String[]{null, "", "  "}) {
            var sent = parse(GmailMail.compose("office@studio.test", "client@example.com", "Booking", "", "Plain",
                    List.of(), UUID.randomUUID(), none));
            assertThat(sent.isMimeType("text/plain")).isTrue();
            assertThat(sent.getContent().toString()).isEqualTo("Plain");
        }
    }

    @Test void theClientReadsTheSendAsSignatureSanitizedAndCachesIt() {
        var client = spy(new GmailClient(new ObjectMapper(), "client.json", "tokens.json", "http://localhost/callback"));
        var answer = new ObjectMapper().createObjectNode()
                .put("sendAsEmail", "wedding@studio.test")
                .put("signature", "<div onclick=\"steal()\">Anna</div><script>alert(1)</script>");
        doReturn(answer).when(client).get("settings/sendAs/wedding%40studio.test");

        assertThat(client.signature("Wedding@Studio.test ")).isEqualTo("<div>Anna</div>");
        assertThat(client.signature("wedding@studio.test")).isEqualTo("<div>Anna</div>");
        verify(client, times(1)).get(anyString());
    }

    @Test void aMailboxWithoutASignatureHasNone() {
        var client = spy(new GmailClient(new ObjectMapper(), "client.json", "tokens.json", "http://localhost/callback"));
        doReturn(new ObjectMapper().createObjectNode().put("sendAsEmail", "office@studio.test").put("signature", ""))
                .when(client).get(anyString());
        assertThat(client.signature("office@studio.test")).isNull();
    }

    @Test void eachMailboxSignsWithItsOwnSignatureWhenEnabled() throws Exception {
        try (var f = new GmailFixture()) {
            f.properties.getGmail().getSignature().setEnabled(true);
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var wedding = f.mailbox("wedding@studio.test", Scope.SHARED, null, "Wedding");
            when(info.signature("info@studio.test")).thenReturn("<div>Office team</div>");
            when(wedding.signature("wedding@studio.test")).thenReturn("<div>Daria, wedding desk</div>");
            when(wedding.send(anyString(), nullable(String.class)))
                    .thenReturn(f.json.createObjectNode().put("id", "sent-1").put("threadId", "wedding-thread"));
            var conversation = f.conversation("info@studio.test", "info-thread", "client@example.com");

            send(f, conversation, "wedding@studio.test", "Congratulations!");

            var raw = ArgumentCaptor.forClass(String.class);
            verify(wedding).send(raw.capture(), isNull());
            var html = alternative((Multipart) parse(raw.getValue()).getContent(), "text/html").getContent().toString();
            assertThat(html).contains("Daria, wedding desk").doesNotContain("Office team");
        }
    }

    @Test void signaturesAreOffByDefault() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            when(info.send(anyString(), nullable(String.class)))
                    .thenReturn(f.json.createObjectNode().put("id", "sent-1").put("threadId", "info-thread"));
            var conversation = f.conversation("info@studio.test", "info-thread", "client@example.com");

            send(f, conversation, null, "Plain reply");

            var raw = ArgumentCaptor.forClass(String.class);
            verify(info).send(raw.capture(), any());
            assertThat(parse(raw.getValue()).isMimeType("text/plain")).isTrue();
            verify(info, never()).signature(anyString());
        }
    }

    @Test void aSignatureGmailCannotGiveNeverHoldsTheReplyBack() throws Exception {
        try (var f = new GmailFixture()) {
            f.properties.getGmail().getSignature().setEnabled(true);
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            when(info.signature(anyString())).thenThrow(new GmailClient.ApiFailure(503, "backendError", null, null));
            when(info.send(anyString(), nullable(String.class)))
                    .thenReturn(f.json.createObjectNode().put("id", "sent-1").put("threadId", "info-thread"));
            var conversation = f.conversation("info@studio.test", "info-thread", "client@example.com");

            var reply = send(f, conversation, null, "Still delivered");

            var raw = ArgumentCaptor.forClass(String.class);
            verify(info).send(raw.capture(), any());
            assertThat(parse(raw.getValue()).getContent().toString()).isEqualTo("Still delivered");
            assertThat(reply.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        }
    }

    @Test void theBridgeSendsAQueuedReplysFormattedHtml() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            when(info.send(anyString(), nullable(String.class)))
                    .thenReturn(f.json.createObjectNode().put("id", "sent-1").put("threadId", "info-thread"));
            var conversation = f.conversation("info@studio.test", "info-thread", "client@example.com");

            send(f, conversation, null, "See you Monday", "See you <b>Monday</b>");

            var raw = ArgumentCaptor.forClass(String.class);
            verify(info).send(raw.capture(), any());
            var alternatives = (Multipart) parse(raw.getValue()).getContent();
            assertThat(alternative(alternatives, "text/html").getContent().toString()).contains("See you <b>Monday</b>");
            assertThat(alternative(alternatives, "text/plain").getContent().toString()).isEqualTo("See you Monday");
        }
    }

    private static ConversationMessage send(GmailFixture f, Conversation conversation, String senderAccount, String body) {
        return send(f, conversation, senderAccount, body, null);
    }

    private static ConversationMessage send(GmailFixture f, Conversation conversation, String senderAccount, String body, String html) {
        var reply = new ConversationMessage();
        reply.setBodyHtml(html);
        reply.setId(UUID.randomUUID());
        reply.setConversation(Ref.of(Conversation.class, conversation.getId()));
        reply.setBody(body);
        reply.setRecipient("client@example.com");
        reply.setSenderAccount(senderAccount);
        f.messageStore.add(reply);
        f.bridge.enqueue(conversation, reply);
        ReflectionTestUtils.invokeMethod(f.bridge, "deliver", f.account("info@studio.test"));
        return reply;
    }
}
