package su.onno.crm.web;
import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import su.onno.crm.domain.*;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.service.*;
import su.onno.web.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** A conversation link finds the workspace that holds it; a conversation outside the viewer's workspaces stays forbidden. */
class CrmConversationWorkspaceTest {
 @Test void authorizedConversationNamesItsWorkspaceButForeignConversationIsForbidden() throws Exception {
  Principal alice=()->"alice";var access=mock(AccessService.class);when(access.roles(alice)).thenReturn(Set.of("CRM_AGENT","SALES"));when(access.hasAnyRole(eq(alice),anyList())).thenReturn(true);
  var visible=new Conversation();visible.setId(UUID.randomUUID());visible.setCustomer(UUID.randomUUID());visible.setChannel(Channel.TELEGRAM);visible.setSubject("Sales call");
  var hidden=new Conversation();hidden.setId(UUID.randomUUID());hidden.setCustomer(UUID.randomUUID());hidden.setChannel(Channel.EMAIL);
  var repository=mock(ConversationRepository.class);when(repository.findActiveById(visible.getId())).thenReturn(Optional.of(visible));when(repository.findActiveById(hidden.getId())).thenReturn(Optional.of(hidden));
  var workspaces=new CrmInboxWorkspaceService(List.of(new CrmInboxWorkspace("sales","Sales",Set.of("SALES"),c->Channel.TELEGRAM.equals(c.getChannel())),
      new CrmInboxWorkspace("support","Support",Set.of("SALES"),c->Channel.EMAIL.equals(c.getChannel()))),
      repository,access,mock(CrmWorkspaceService.class),su.onno.crm.TestBindings.readableContacts(),mock(CrmConnectionAccounts.class));
  var scoped=new CrmInboxWorkspaceController(workspaces,mock(CatalogQueryService.class),mock(CurrentUserResolver.class),mock(su.onno.web.comments.CommentService.class),mock(su.onno.web.comments.CommentAuthorAvatars.class),mock(su.onno.web.AccessSubjectResolver.class),su.onno.crm.TestBindings.readableContacts(),su.onno.crm.TestBindings.noAgents(),access);
  var http=MockMvcBuilders.standaloneSetup(scoped).build();
  http.perform(get("/api/crm/inbox-workspaces").param("conversation",visible.getId().toString()).principal(alice)).andExpect(status().isOk())
      .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].key").value("sales"));
  http.perform(get("/api/crm/inbox-workspaces").principal(alice)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
  http.perform(get("/api/crm/inbox-workspaces/sales/conversation/"+hidden.getId()).principal(alice)).andExpect(status().isForbidden());
 }
}
