package su.onno.crm.channels.gmail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.*;
import su.onno.crm.service.CrmConnectionAccounts.Scope;
import su.onno.crm.service.CrmInboxWorkspaceService;

/** Read state mirrored to Gmail, and finding archived mail and bringing it back. */
class GmailMailboxTest {
    private static final Principal ALICE = () -> "alice";

    @Test void markingAConversationReadClearsUnreadInGmailOnlyWhereTheGrantAllowsIt() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            var office = f.mailbox("office@studio.test", Scope.SHARED, null, "Office");
            when(info.canModify()).thenReturn(true);
            when(office.canModify()).thenReturn(false);
            var conversation = f.conversation("info@studio.test", "t1", "client@example.com");
            f.messageStore.add(message(conversation, MessageDirection.INBOUND, "gmail:info@studio.test:aaa"));
            f.messageStore.add(message(conversation, MessageDirection.INBOUND, "gmail:office@studio.test:bbb"));
            f.messageStore.add(message(conversation, MessageDirection.OUTBOUND, "gmail:sent"));

            f.bridge.markReadInGmail(conversation.getId());

            var body = ArgumentCaptor.forClass(JsonNode.class);
            verify(info).post(eq("messages/batchModify"), body.capture());
            assertThat(body.getValue().toString()).isEqualTo("{\"ids\":[\"aaa\"],\"removeLabelIds\":[\"UNREAD\"]}");
            verify(office, never()).post(anyString(), any());
        }
    }

    @Test void readSyncIsBestEffort() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            when(info.canModify()).thenReturn(true);
            when(info.post(anyString(), any())).thenThrow(new GmailClient.ApiFailure(429, "rateLimitExceeded", null, null));
            var conversation = f.conversation("info@studio.test", "t1", "client@example.com");
            f.messageStore.add(message(conversation, MessageDirection.INBOUND, "gmail:info@studio.test:aaa"));
            assertThatCode(() -> f.bridge.markReadInGmail(conversation.getId())).doesNotThrowAnyException();
            assertThat(f.bridge.paused(f.account("info@studio.test"))).isTrue();
        }
    }

    @Test void restoreImportsAnArchivedThreadMovesItToTheInboxAndIsIdempotent() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            when(info.canModify()).thenReturn(true);
            when(info.get("messages/m2?format=minimal")).thenReturn(f.json.readTree("{\"id\":\"m2\",\"threadId\":\"archived\"}"));
            when(info.get("threads/archived?format=minimal")).thenReturn(f.json.readTree("""
                {"id":"archived","messages":[
                  {"id":"m1","labelIds":["SENT"]},
                  {"id":"m2","labelIds":["CATEGORY_PERSONAL"]},
                  {"id":"junk","labelIds":["SPAM"]}]}"""));
            when(info.get("messages/m1?format=raw")).thenReturn(f.raw("info@studio.test", "client@example.com",
                    "Re: Venue", "Our answer", "archived", 2_000_000L, "SENT"));
            when(info.get("messages/m2?format=raw")).thenReturn(f.raw("client@example.com", "info@studio.test",
                    "Venue", "Is the venue free in June?", "archived", 1_000_000L));
            var account = f.account("info@studio.test");

            var first = f.bridge.restore(account, "m2", true);
            var second = f.bridge.restore(account, "m2", true);

            assertThat(first.conversationId()).isEqualTo(second.conversationId());
            assertThat(first.movedToInbox()).isTrue();
            assertThat(f.messagesOf(first.conversationId())).extracting(ConversationMessage::getBody)
                    .containsExactlyInAnyOrder("Is the venue free in June?", "Our answer");
            verify(info, never()).get("messages/junk?format=raw");
            verify(info, times(1)).get("messages/m2?format=raw");
            var body = ArgumentCaptor.forClass(JsonNode.class);
            verify(info, times(2)).post(eq("threads/archived/modify"), body.capture());
            assertThat(body.getValue().toString()).isEqualTo("{\"addLabelIds\":[\"INBOX\"]}");
        }
    }

    @Test void restoreWithoutTheModifyGrantImportsButLeavesGmailAlone() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            when(info.get("messages/m2?format=minimal")).thenReturn(f.json.readTree("{\"id\":\"m2\",\"threadId\":\"t\"}"));
            when(info.get("threads/t?format=minimal")).thenReturn(f.json.readTree("{\"messages\":[{\"id\":\"m2\",\"labelIds\":[]}]}"));
            when(info.get("messages/m2?format=raw")).thenReturn(f.raw("client@example.com", "info@studio.test", "Venue", "Hello", "t", 1_000_000L));
            var restored = f.bridge.restore(f.account("info@studio.test"), "m2", true);
            assertThat(restored.movedToInbox()).isFalse();
            assertThat(restored.conversationId()).isNotNull();
            verify(info, never()).post(anyString(), any());
        }
    }

    @Test void endpointsListSearchAndRestoreOnlyMailboxesThePrincipalMayRead() throws Exception {
        try (var f = new GmailFixture()) {
            var info = f.mailbox("info@studio.test", Scope.SHARED, null, "Info");
            f.mailbox("alice@studio.test", Scope.PERSONAL, "alice", "Alice");
            f.mailbox("bob@studio.test", Scope.PERSONAL, "bob", "Bob");
            when(info.canModify()).thenReturn(true);
            var workspaces = mock(CrmInboxWorkspaceService.class);
            when(workspaces.canAccess(any(), eq(ALICE), eq(false))).thenReturn(true);
            var controller = new GmailMailboxController(f.bridge, workspaces, f.conversations);

            assertThat(controller.accounts(ALICE)).containsExactly(
                    new GmailMailboxController.Mailbox("alice@studio.test", "Alice · alice@studio.test", false),
                    new GmailMailboxController.Mailbox("info@studio.test", "Info · info@studio.test", true));
            verify(workspaces).requireAccess(ALICE, false);

            assertThat(status(() -> controller.search("bob@studio.test", "venue", null, ALICE))).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(status(() -> controller.search("nobody@studio.test", "venue", null, ALICE))).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(status(() -> controller.search("info@studio.test", " ", null, ALICE))).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(status(() -> controller.search("info@studio.test", "x".repeat(501), null, ALICE))).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(status(() -> controller.search("info@studio.test", "venue", "bad token!", ALICE))).isEqualTo(HttpStatus.BAD_REQUEST);

            var known = f.conversation("info@studio.test", "t-known", "client@example.com");
            when(info.get("messages?maxResults=20&q=venue+in%3Aanywhere&pageToken=p1")).thenReturn(f.json.readTree("""
                {"messages":[{"id":"a","threadId":"t-known"},{"id":"b","threadId":"t-new"}],"nextPageToken":"p2"}"""));
            when(info.get("messages/a?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")).thenReturn(f.json.readTree("""
                {"id":"a","threadId":"t-known","labelIds":["INBOX"],"snippet":"See you","internalDate":"1700000000000",
                 "payload":{"headers":[{"name":"From","value":"Client <client@example.com>"},{"name":"Subject","value":"Booking"}]}}"""));
            when(info.get("messages/b?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")).thenReturn(f.json.readTree("""
                {"id":"b","threadId":"t-new","labelIds":["CATEGORY_UPDATES"],"snippet":"Old enquiry","internalDate":"1600000000000",
                 "payload":{"headers":[{"name":"From","value":"lead@example.com"},{"name":"Subject","value":"Venue"}]}}"""));
            var page = controller.search("INFO@studio.test", "venue in:anywhere", "p1", ALICE);
            assertThat(page.nextPageToken()).isEqualTo("p2");
            assertThat(page.messages()).containsExactly(
                    new GmailMailboxController.Hit("a", "t-known", "Client <client@example.com>", "Booking", "See you",
                            "2023-11-14T22:13:20Z", false, known.getId().toString()),
                    new GmailMailboxController.Hit("b", "t-new", "lead@example.com", "Venue", "Old enquiry",
                            "2020-09-13T12:26:40Z", true, null));

            assertThat(status(() -> controller.restore(new GmailMailboxController.RestoreRequest("info@studio.test", "../x", true), ALICE)))
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(status(() -> controller.restore(new GmailMailboxController.RestoreRequest("bob@studio.test", "abc", true), ALICE)))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            verify(workspaces, atLeastOnce()).requireAccess(ALICE, true);
            when(info.get("messages/gone?format=minimal")).thenThrow(new GmailClient.ApiFailure(404));
            assertThat(status(() -> controller.restore(new GmailMailboxController.RestoreRequest("info@studio.test", "gone", false), ALICE)))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            when(info.get("messages/busy?format=minimal")).thenThrow(new GmailClient.ApiFailure(429, "rateLimitExceeded", null, null));
            assertThat(status(() -> controller.restore(new GmailMailboxController.RestoreRequest("info@studio.test", "busy", false), ALICE)))
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Test void withoutInboxAccessNothingIsListed() {
        try (var f = new GmailFixture()) {
            var workspaces = mock(CrmInboxWorkspaceService.class);
            doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(workspaces).requireAccess(any(), anyBoolean());
            var controller = new GmailMailboxController(f.bridge, workspaces, f.conversations);
            assertThat(status(() -> controller.accounts(ALICE))).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(() -> controller.search("info@studio.test", "venue", null, ALICE))).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(() -> controller.restore(new GmailMailboxController.RestoreRequest("info@studio.test", "m", true), ALICE)))
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    private static HttpStatus status(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        var thrown = catchThrowable(call);
        assertThat(thrown).as("a ResponseStatusException").isInstanceOf(ResponseStatusException.class);
        return HttpStatus.valueOf(((ResponseStatusException) thrown).getStatusCode().value());
    }

    private static ConversationMessage message(Conversation conversation, MessageDirection direction, String external) {
        var m = new ConversationMessage();
        m.setId(UUID.randomUUID());
        m.setConversation(su.onno.types.Ref.of(Conversation.class, conversation.getId()));
        m.setDirection(direction);
        m.setExternalMessageId(external);
        return m;
    }
}
