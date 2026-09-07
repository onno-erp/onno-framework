package su.onno.crm;

import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import su.onno.crm.domain.*;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.service.*;
import su.onno.crm.web.*;
import su.onno.ui.*;
import su.onno.ui.comments.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CrmInboxWorkspaceSecurityTest {
    Principal alice=()->"alice";
    UiAccessService access=mock(UiAccessService.class);
    ConversationRepository repository=mock(ConversationRepository.class);
    ConversationService commands=mock(ConversationService.class);
    CommentService comments=mock(CommentService.class);
    Conversation sales=new Conversation(),support=new Conversation();
    CrmInboxWorkspaceService workspaces;
    MockMvc http;
    @BeforeEach void setup() {
        sales.setId(UUID.randomUUID());sales.setChannel(Channel.TELEGRAM);
        support.setId(UUID.randomUUID());support.setChannel(Channel.EMAIL);
        when(repository.findAllActive()).thenReturn(List.of(sales,support));
        when(repository.findActiveById(sales.getId())).thenReturn(Optional.of(sales));
        when(repository.findActiveById(support.getId())).thenReturn(Optional.of(support));
        when(access.roles(alice)).thenReturn(Set.of("CRM_AGENT","SALES"));
        when(access.hasAnyRole(eq(alice),anyList())).thenReturn(true);
        var definitions=List.of(new CrmInboxWorkspace("sales","Sales",Set.of("SALES"),c->c.getChannel()==Channel.TELEGRAM),
                new CrmInboxWorkspace("support","Support",Set.of("SUPPORT"),c->c.getChannel()==Channel.EMAIL));
        workspaces=new CrmInboxWorkspaceService(definitions,repository,access,mock(CrmWorkspaceService.class));
        var users=mock(CurrentUserResolver.class);
        var controller=new CrmInboxController(commands,users,mock(CrmAgentIdentityResolver.class),access,workspaces);
        var scoped=new CrmInboxWorkspaceController(workspaces,mock(CatalogQueryService.class),users,comments,
                mock(CommentAuthorAvatars.class),mock(org.springframework.context.ApplicationEventPublisher.class));
        http=MockMvcBuilders.standaloneSetup(controller,scoped).build();
    }
    @Test void listsOnlyPermittedWorkspaces() throws Exception {
        http.perform(get("/api/crm/inbox-workspaces").principal(alice)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].key").value("sales"));
    }
    @Test void rejectsUnauthorizedWorkspaceAndForeignChatOnEveryCommand() throws Exception {
        http.perform(get("/api/crm/inbox-workspaces/support").principal(alice)).andExpect(status().isForbidden());
        for(String suffix:List.of("messages","delivery"))
            http.perform(get("/api/crm/conversations/"+support.getId()+"/"+suffix).param("workspace","sales").principal(alice)).andExpect(status().isForbidden());
        for(String suffix:List.of("messages","read","closed","status","assign-to-me","messages/"+UUID.randomUUID()+"/retry"))
            http.perform(post("/api/crm/conversations/"+support.getId()+"/"+suffix).param("workspace","sales").principal(alice).contentType("application/json").content("{\"body\":\"test\",\"closed\":true}")).andExpect(status().isForbidden());
        http.perform(get("/api/crm/conversations/"+sales.getId()+"/messages").principal(alice)).andExpect(status().isForbidden());
        verifyNoInteractions(commands);
    }
    @Test void notesCannotBypassWorkspaceMembership() throws Exception {
        String path="/api/crm/inbox-workspaces/sales/conversations/"+support.getId()+"/comments";
        http.perform(get(path).principal(alice)).andExpect(status().isForbidden());
        http.perform(post(path).principal(alice).contentType("application/json").content("{\"body\":\"hidden\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(comments);
    }
    @Test void readOnlyRoleDoesNotGrantWriteAccessAndRoutingRemainsUntouched() {
        var workspace=new CrmInboxWorkspace("audit","Audit",Set.of("SALES"),Set.of("SUPPORT"),c->true,c->c);
        var scoped=new CrmInboxWorkspaceService(List.of(workspace),repository,access,mock(CrmWorkspaceService.class));
        assertThat(scoped.requireConversation("audit",sales.getId(),alice,false)).isSameAs(sales);
        assertThatThrownBy(()->scoped.requireConversation("audit",sales.getId(),alice,true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(repository,never()).save(any());
    }
    @Test void genericGateBlocksAgentsAndLeavesAdminToolsAvailable() {
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation->java.util.stream.Stream.of(new CrmInboxWorkspace("sales","Sales",Set.of("SALES"),c->true)));
        var policy=new OnnoCrmAutoConfiguration().crmWorkspaceGenericAccess(provider);
        assertThat(policy.allows(Set.of("CRM_AGENT"),"catalog","crmconversations",false)).isFalse();
        assertThat(policy.allows(Set.of("CRM_MANAGER"),"catalog","crmconversationmessages",true)).isFalse();
        assertThat(policy.allows(Set.of("ADMIN"),"catalog","crmconversations",true)).isTrue();
        assertThat(policy.allows(Set.of("CRM_AGENT"),"catalog","crmcustomers",false)).isTrue();
    }
}
