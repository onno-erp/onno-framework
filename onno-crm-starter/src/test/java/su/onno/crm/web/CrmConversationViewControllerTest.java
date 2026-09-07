package su.onno.crm.web;
import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import su.onno.crm.domain.*;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.service.*;
import su.onno.ui.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CrmConversationViewControllerTest {
 @Test void authorizedRecordOpensChatButForeignRecordAndWorkspaceRemainForbidden() throws Exception {
  Principal alice=()->"alice";var access=mock(UiAccessService.class);when(access.roles(alice)).thenReturn(Set.of("CRM_AGENT","SALES"));when(access.hasAnyRole(eq(alice),anyList())).thenReturn(true);
  var visible=new Conversation();visible.setId(UUID.randomUUID());visible.setChannel(Channel.TELEGRAM);visible.setSubject("Sales call");
  var hidden=new Conversation();hidden.setId(UUID.randomUUID());hidden.setChannel(Channel.EMAIL);
  var repository=mock(ConversationRepository.class);when(repository.findActiveById(visible.getId())).thenReturn(Optional.of(visible));when(repository.findActiveById(hidden.getId())).thenReturn(Optional.of(hidden));
  var workspaces=new CrmInboxWorkspaceService(List.of(new CrmInboxWorkspace("sales","Sales",Set.of("SALES"),c->c.getChannel()==Channel.TELEGRAM)),repository,access,mock(CrmWorkspaceService.class));
  ObjectProvider<DivKitController> generic=mock(ObjectProvider.class);
  var controller=new CrmConversationViewController(workspaces,access,generic);
  var scoped=new CrmInboxWorkspaceController(workspaces,mock(CatalogQueryService.class),mock(CurrentUserResolver.class),mock(su.onno.ui.comments.CommentService.class),mock(su.onno.ui.comments.CommentAuthorAvatars.class),mock(org.springframework.context.ApplicationEventPublisher.class));
  var http=MockMvcBuilders.standaloneSetup(controller,scoped).build();
  http.perform(get("/api/divkit/catalogs/crm_conversations/"+visible.getId()).principal(alice)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("crmInboxWorkspaces"))).andExpect(content().string(org.hamcrest.Matchers.containsString(visible.getId().toString())));
  http.perform(get("/api/divkit/catalogs/crm_conversations/"+hidden.getId()).principal(alice)).andExpect(status().isForbidden());
  http.perform(get("/api/crm/inbox-workspaces/sales/conversation/"+hidden.getId()).principal(alice)).andExpect(status().isForbidden());
  verifyNoInteractions(generic);
 }
}
