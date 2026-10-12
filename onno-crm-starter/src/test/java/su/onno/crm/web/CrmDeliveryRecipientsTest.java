package su.onno.crm.web;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import su.onno.crm.domain.Channel;
import su.onno.crm.domain.ContactIdentity;
import su.onno.crm.domain.Conversation;
import su.onno.crm.repository.ContactIdentityRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.service.*;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.UiAccessService;
import su.onno.ui.comments.CommentAuthorAvatars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The delivery endpoint must not reveal addresses learned only through another user's personal account. */
class CrmDeliveryRecipientsTest {
    @Test void dropsAddressesKnownOnlyThroughAnotherUsersPersonalAccount() {
        Principal alice = () -> "alice";
        UUID personal = UUID.randomUUID(), shared = UUID.randomUUID();
        var conversation = new Conversation();
        conversation.setId(UUID.randomUUID()); conversation.setCustomer(UUID.randomUUID()); conversation.setChannel(Channel.EMAIL);
        var conversations = mock(ConversationRepository.class);
        when(conversations.findActiveById(conversation.getId())).thenReturn(Optional.of(conversation));
        var access = mock(UiAccessService.class);
        when(access.roles(alice)).thenReturn(Set.of("SUPPORT"));
        var accounts = mock(CrmConnectionAccounts.class);
        when(accounts.hiddenInboxes(alice)).thenReturn(Set.of(personal));
        var contacts = su.onno.crm.TestBindings.readableContacts();
        when(contacts.canonical(any())).thenAnswer(call -> call.getArgument(0));
        var workspaces = new CrmInboxWorkspaceService(List.of(new CrmInboxWorkspace("support", "Support", Set.of("SUPPORT"), c -> true)),
                conversations, access, mock(CrmWorkspaceService.class), contacts, accounts);
        var identities = mock(ContactIdentityRepository.class);
        when(identities.findByCustomerAndDeletionMarkFalse(conversation.getCustomer())).thenReturn(List.of(
                identity(conversation.getCustomer(), shared, "thread@example.com"),
                identity(conversation.getCustomer(), shared, "Known <known@example.com>"),
                identity(conversation.getCustomer(), personal, "private@example.com"),
                identity(conversation.getCustomer(), personal, "known@example.com")));
        var service = mock(ConversationService.class);
        when(service.delivery(conversation.getId())).thenReturn(new CrmMessageTransport.Connection(true, "Gmail", 8000)
                .withRecipients("thread@example.com", List.of("known@example.com", "private@example.com", "thread@example.com")));
        var controller = new CrmInboxController(service, mock(CurrentUserResolver.class), mock(CrmAgentIdentityResolver.class), access,
                workspaces, mock(CommentAuthorAvatars.class), mock(CrmAttachments.class), identities, contacts);

        var delivery = controller.delivery(conversation.getId(), "support", alice);

        assertThat(delivery.recipient()).isEqualTo("thread@example.com");
        assertThat(delivery.recipients()).containsExactly("known@example.com", "thread@example.com");

        // Sending to a hidden address is refused before the service sees it.
        when(access.hasAnyRole(any(), any())).thenReturn(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.send(conversation.getId(),
                        new CrmInboxController.SendMessage("Hello", List.of(), null, "private@example.com"), "support", alice))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("email addresses");
        verify(service, never()).addMessage(any(), any());
    }

    private static ContactIdentity identity(UUID customer, UUID inbox, String address) {
        var identity = new ContactIdentity();
        identity.setId(UUID.randomUUID()); identity.setCustomer(customer); identity.setChannel(Channel.EMAIL);
        identity.setConnectionKey(inbox.toString()); identity.setExternalId(address); identity.setAddress(address);
        return identity;
    }
}
