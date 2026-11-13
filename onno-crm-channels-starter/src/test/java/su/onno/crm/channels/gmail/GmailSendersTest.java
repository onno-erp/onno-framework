package su.onno.crm.channels.gmail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import su.onno.crm.domain.*;
import su.onno.crm.service.CrmConnectionAccounts.Scope;
import su.onno.crm.service.CrmMessageTransport;
import su.onno.types.Ref;

/** Choosing the mailbox a reply is sent from (From), and the answer finding its way back. */
class GmailSendersTest {
    private static MimeMessage parse(String raw) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
    }

    @Test void offersSharedMailboxesAndOnlyTheReplyingUsersOwnPersonalOne() {
        try (var f = new GmailFixture()) {
            f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            f.mailbox("office@studio.test", Scope.SHARED, null, "Office");
            f.mailbox("alice@studio.test", Scope.PERSONAL, "alice", "Alice");
            f.mailbox("bob@studio.test", Scope.PERSONAL, "bob", "Bob");
            var conversation = f.conversation("info@studio.test", "t1", "client@example.com");
            Principal alice = () -> "alice";

            var plain = f.bridge.connection(conversation);
            assertThat(plain.sender()).isEqualTo("info@studio.test");
            assertThat(plain.senders()).extracting(CrmMessageTransport.Sender::id)
                    .containsExactly("info@studio.test", "office@studio.test");
            assertThat(plain.senders().getFirst().label()).isEqualTo("Info · info@studio.test");

            var forAlice = f.bridge.connection(conversation, alice);
            assertThat(forAlice.sender()).isEqualTo("info@studio.test");
            assertThat(forAlice.senders()).extracting(CrmMessageTransport.Sender::id)
                    .containsExactlyInAnyOrder("info@studio.test", "office@studio.test", "alice@studio.test")
                    .doesNotContain("bob@studio.test");
        }
    }

    @Test void pausedOrDisconnectedMailboxesAreNotOffered() {
        try (var f = new GmailFixture()) {
            f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var office = f.mailbox("office@studio.test", Scope.SHARED, null, "Office");
            f.mailbox("events@studio.test", Scope.SHARED, null, "Events");
            f.jdbc.update("UPDATE onno_crm_gmail_account SET active=FALSE WHERE account='events@studio.test'");
            when(office.authorized()).thenReturn(false);
            var conversation = f.conversation("info@studio.test", "t1", "client@example.com");
            assertThat(f.bridge.connection(conversation).senders()).extracting(CrmMessageTransport.Sender::id)
                    .containsExactly("info@studio.test");
        }
    }

    @Test void aReplyFromAnotherMailboxStartsItsOwnThreadAndTheAnswerComesBackToTheConversation() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var office = f.mailbox("office@studio.test", Scope.SHARED, null, "Office");
            var conversation = f.conversation("info@studio.test", "info-thread", "client@example.com");
            when(office.send(anyString(), nullable(String.class)))
                    .thenReturn(f.json.createObjectNode().put("id", "sent-1").put("threadId", "office-thread"));

            var reply = new ConversationMessage();
            reply.setId(UUID.randomUUID());
            reply.setConversation(Ref.of(Conversation.class, conversation.getId()));
            reply.setBody("Let's talk on Monday");
            reply.setRecipient("client@example.com");
            reply.setSenderAccount("office@studio.test");
            f.messageStore.add(reply);
            f.bridge.enqueue(conversation, reply);
            ReflectionTestUtils.invokeMethod(f.bridge, "deliver", f.account("info@studio.test"));

            var raw = ArgumentCaptor.forClass(String.class);
            verify(office).send(raw.capture(), isNull());
            verify(info, never()).send(anyString(), any());
            var sent = parse(raw.getValue());
            assertThat(sent.getFrom()[0].toString()).isEqualTo("office@studio.test");
            assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("client@example.com");
            assertThat(sent.getSubject()).isEqualTo("Booking");
            assertThat(sent.getHeader("In-Reply-To", null)).isNull();
            assertThat(reply.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
            assertThat(f.jdbc.queryForObject("SELECT conversation_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=?",
                    UUID.class, "office@studio.test", "office-thread")).isEqualTo(conversation.getId());

            // The client's answer arrives in office@'s thread and joins the same conversation.
            when(office.get("messages/answer?format=raw")).thenReturn(f.raw("client@example.com", "office@studio.test",
                    "Re: Booking", "Monday works", "office-thread", 3_000_000_000_000L, "INBOX", "UNREAD"));
            ReflectionTestUtils.invokeMethod(f.bridge, "ingest", f.account("office@studio.test"), "answer");
            assertThat(f.messagesOf(conversation.getId())).extracting(ConversationMessage::getBody).contains("Monday works");
            assertThat(f.conversationStore).hasSize(1);

            // The next reply from office@ continues office@'s own thread.
            var next = new ConversationMessage();
            next.setId(UUID.randomUUID());
            next.setConversation(Ref.of(Conversation.class, conversation.getId()));
            next.setBody("See you then");
            next.setRecipient("client@example.com");
            next.setSenderAccount("office@studio.test");
            f.messageStore.add(next);
            when(office.send(anyString(), eq("office-thread")))
                    .thenReturn(f.json.createObjectNode().put("id", "sent-2").put("threadId", "office-thread"));
            f.bridge.enqueue(conversation, next);
            ReflectionTestUtils.invokeMethod(f.bridge, "deliver", f.account("info@studio.test"));
            verify(office).send(raw.capture(), eq("office-thread"));
            assertThat(parse(raw.getValue()).getSubject()).isEqualTo("Re: Booking");
            assertThat(next.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        }
    }

    @Test void aNewThreadToTheOtherMailboxStaysInThatMailboxsOwnInbox() throws Exception {
        try (var f = new GmailFixture()) {
            f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var office = f.mailbox("office@studio.test", Scope.SHARED, null, "Office");
            var conversation = f.conversation("info@studio.test", "info-thread", "client@example.com");
            // office@ once sent a reply for the info@ conversation.
            f.jdbc.update("INSERT INTO onno_crm_gmail_thread (account,thread_id,conversation_id,reply_to,subject) VALUES (?,?,?,?,?)",
                    "office@studio.test", "office-thread", conversation.getId(), "client@example.com", "Booking");
            when(office.get("messages/fresh?format=raw")).thenReturn(f.raw("client@example.com", "office@studio.test",
                    "Another question", "Hello again", "fresh-thread", 3_000_000_000_000L, "INBOX", "UNREAD"));
            ReflectionTestUtils.invokeMethod(f.bridge, "ingest", f.account("office@studio.test"), "fresh");
            UUID routed = f.jdbc.queryForObject("SELECT conversation_id FROM onno_crm_gmail_thread WHERE account=? AND thread_id=?",
                    UUID.class, "office@studio.test", "fresh-thread");
            assertThat(routed).isNotEqualTo(conversation.getId());
            assertThat(f.conversationStore.get(routed).getInbox().id()).isEqualTo(f.inboxOf("office@studio.test"));
        }
    }

    @Test void aRateLimitedSendStaysQueuedAndPausesOnlyTheSendingMailbox() throws Exception {
        try (var f = new GmailFixture()) {
            f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var office = f.mailbox("office@studio.test", Scope.SHARED, null, "Office");
            var conversation = f.conversation("info@studio.test", "info-thread", "client@example.com");
            when(office.send(anyString(), nullable(String.class))).thenThrow(new GmailClient.ApiFailure(429, "rateLimitExceeded", java.time.Duration.ofMinutes(5), null));
            var reply = new ConversationMessage();
            reply.setId(UUID.randomUUID());
            reply.setConversation(Ref.of(Conversation.class, conversation.getId()));
            reply.setBody("Hello");
            reply.setSenderAccount("office@studio.test");
            f.messageStore.add(reply);
            f.bridge.enqueue(conversation, reply);
            ReflectionTestUtils.invokeMethod(f.bridge, "deliver", f.account("info@studio.test"));
            assertThat(f.jdbc.queryForObject("SELECT state FROM onno_crm_gmail_outbox WHERE message_id=?", String.class, reply.getId())).isEqualTo("QUEUED");
            assertThat(reply.getDeliveryStatus()).isNotEqualTo(DeliveryStatus.FAILED);
            assertThat(f.bridge.paused(f.account("office@studio.test"))).isTrue();
            assertThat(f.bridge.paused(f.account("info@studio.test"))).isFalse();
        }
    }
}
