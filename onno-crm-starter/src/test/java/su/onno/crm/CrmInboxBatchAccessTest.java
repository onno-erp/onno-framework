package su.onno.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import su.onno.crm.domain.Channel;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.CrmContactService;
import su.onno.crm.service.CrmInboxWorkspace;
import su.onno.crm.service.CrmInboxWorkspaceService;
import su.onno.crm.service.CrmWorkspaceService;
import su.onno.ui.UiAccessService;

/**
 * The batch access filter has to answer exactly what asking row by row answers — it is the inbox's
 * authorization boundary, and a faster wrong answer is the worst possible outcome here.
 */
class CrmInboxBatchAccessTest {

    private final Principal alice = () -> "alice";
    private final UiAccessService access = mock(UiAccessService.class);
    private final CrmContactService contacts = mock(CrmContactService.class);

    private Conversation conversation(String channel, UUID customer) {
        var conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setCustomer(customer);
        conversation.setChannel(channel);
        return conversation;
    }

    private CrmInboxWorkspaceService service(List<CrmInboxWorkspace> definitions) {
        return new CrmInboxWorkspaceService(definitions, mock(su.onno.crm.repository.ConversationRepository.class),
                access, mock(CrmWorkspaceService.class), contacts);
    }

    @Test
    void filtersTheSameConversationsAsAskingRowByRow() {
        when(access.roles(alice)).thenReturn(Set.of("SALES"));
        UUID mine = UUID.randomUUID(), theirs = UUID.randomUUID();
        var telegram = conversation(Channel.TELEGRAM, mine);
        var email = conversation(Channel.EMAIL, mine);
        var hidden = conversation(Channel.TELEGRAM, theirs);
        var orphan = conversation(Channel.TELEGRAM, null);

        var workspace = new CrmInboxWorkspace("sales", "Sales", Set.of("SALES"),
                c -> Channel.TELEGRAM.equals(c.getChannel()));
        var service = service(List.of(workspace));
        // Only this customer's cards are readable, and only Telegram is claimed by the workspace.
        when(contacts.readable(anyList(), eq(alice))).thenReturn(Set.of(mine));
        when(contacts.canRead(mine, alice)).thenReturn(true);
        when(contacts.canRead(theirs, alice)).thenReturn(false);

        var candidates = List.of(telegram, email, hidden, orphan);
        assertThat(service.accessible(candidates, alice, false)).containsExactly(telegram);
        // The row-by-row rule agrees, conversation for conversation.
        assertThat(candidates.stream().filter(c -> service.canAccess(c, alice, false)).toList())
                .containsExactly(telegram);
    }

    /** The whole point: one question about the contacts, not one per conversation. */
    @Test
    void asksAboutTheContactsOnceForTheWholePage() {
        when(access.roles(alice)).thenReturn(Set.of("SALES"));
        UUID customer = UUID.randomUUID();
        var workspace = new CrmInboxWorkspace("all", "All", Set.of("SALES"), c -> true);
        var service = service(List.of(workspace));
        when(contacts.readable(anyList(), eq(alice))).thenReturn(Set.of(customer));

        var many = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> conversation(Channel.TELEGRAM, customer)).toList();
        assertThat(service.accessible(many, alice, false)).hasSize(50);

        verify(contacts).readable(anyList(), eq(alice));
        verify(contacts, never()).canRead(any(), any());
    }

    @Test
    void refusesEverythingWhenNoWorkspacePermitsThePrincipal() {
        when(access.roles(alice)).thenReturn(Set.of("SUPPORT"));
        var workspace = new CrmInboxWorkspace("sales", "Sales", Set.of("SALES"), c -> true);
        var service = service(List.of(workspace));

        assertThat(service.accessible(List.of(conversation(Channel.TELEGRAM, UUID.randomUUID())), alice, false))
                .isEmpty();
        // No contact was consulted: the principal had no workspace to see them through.
        verify(contacts, never()).readable(anyList(), any());
    }
}
