package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.*;
import su.onno.metadata.CatalogDescriptor;
import su.onno.ui.*;
import su.onno.ui.comments.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmInboxReadPerformanceTest {
    final Principal principal=()->"reader";
    final CrmInboxWorkspaceService workspaces=mock(CrmInboxWorkspaceService.class);
    final CatalogQueryService catalogs=mock(CatalogQueryService.class);
    final CrmContactService contacts=mock(CrmContactService.class);
    final List<Conversation> members=new ArrayList<>();
    CrmInboxWorkspaceController controller;
    @BeforeEach void setup() {
        var workspace=new CrmInboxWorkspace("support","Support",Set.of("READER"),c->true)
            .list(s->s.filter(Conversation::getChannel).options(Map.of("WILDBERRIES","WB","TELEGRAM","TG")));
        when(workspaces.requireWorkspace("support",principal,false)).thenReturn(workspace);
        when(workspaces.members(workspace)).thenReturn(members);
        when(workspaces.canAccess(any(),eq(principal),eq(false))).thenReturn(true);
        when(workspaces.config(workspace)).thenReturn(new CrmWorkspaceService.Config(List.of(),List.of(),"","","",true,true,true,true,true,true));
        var descriptor=mock(CatalogDescriptor.class);when(descriptor.attributes()).thenReturn(List.of());
        when(catalogs.forClass(Conversation.class)).thenReturn(descriptor);
        when(catalogs.get(eq(descriptor),any())).thenReturn(Map.of());
        var views=mock(UiViewResolver.class);var view=mock(ResolvedListView.class);
        when(views.catalogList(eq(descriptor),any(ListSpec.class))).thenReturn(view);
        when(view.title()).thenReturn("Inbox");when(view.columns()).thenReturn(List.of());when(view.filters()).thenReturn(List.of());
        controller=new CrmInboxWorkspaceController(workspaces,catalogs,mock(CurrentUserResolver.class),mock(CommentService.class),
            mock(CommentAuthorAvatars.class),mock(ApplicationEventPublisher.class),contacts,mock(ObjectProvider.class),mock(UiAccessService.class));
        ReflectionTestUtils.setField(controller,"listViews",views);
        ReflectionTestUtils.setField(controller,"states",CrmStateConfiguration.empty());
        var priorities=mock(CrmPriorityBinding.class);when(priorities.choices()).thenReturn(CrmStateConfiguration.empty());
        ReflectionTestUtils.setField(controller,"priorities",priorities);
        for(int i=0;i<100;i++) {
            var c=new Conversation();c.setId(UUID.randomUUID());c.setCustomer(UUID.randomUUID());
            c.setDescription("chat-"+i);c.setChannel(i%2==0?"WILDBERRIES":"TELEGRAM");c.setUnreadCount(i);members.add(c);
            when(contacts.get(c.getCustomer(),principal)).thenReturn(new CrmContactService.Contact("Customers",
                Map.of("id",c.getCustomer().toString(),"description","customer-"+i),List.of(),List.of(),false));
        }
    }
    @Test void decoratesOnlyTheAuthorizedFilteredSortedPage() {
        when(workspaces.canAccess(members.get(98),principal,false)).thenReturn(false);
        var params=new LinkedMultiValueMap<String,String>();params.set("limit","2");params.set("cursor","1");
        params.set("eq","channel,WILDBERRIES");params.set("sort","unreadCount");params.set("dir","desc");
        var result=(Map<?,?>)controller.read("support","",0,params,principal);
        assertThat(result.get("total")).isEqualTo(49);
        assertThat(result.get("nextCursor")).isEqualTo("3");
        var rows=(List<Map<String,Object>>)result.get("rows");
        assertThat(rows).extracting(r->r.get("id")).containsExactly(members.get(94).getId(),members.get(92).getId());
        verify(contacts,times(2)).get(any(),eq(principal));
        verify(catalogs,times(2)).get(any(),any());
        verify(contacts,never()).get(members.get(98).getCustomer(),principal);
    }
    @Test void liveReadPatchReturnsOnlyRequestedAuthorizedConversation() {
        var params=new LinkedMultiValueMap<String,String>();
        params.set("ids",members.get(75).getId().toString());
        var result=(Map<?,?>)controller.read("support","",0,params,principal);
        var rows=(List<Map<String,Object>>)result.get("rows");
        assertThat(rows).extracting(r->r.get("id")).containsExactly(members.get(75).getId());
        assertThat(result.get("total")).isEqualTo(1);
        verify(contacts,times(1)).get(members.get(75).getCustomer(),principal);
    }
    @Test void requestedIdsCannotExposeForeignOrUnauthorizedConversations() {
        when(workspaces.canAccess(members.get(75),principal,false)).thenReturn(false);
        var params=new LinkedMultiValueMap<String,String>();
        params.set("ids",members.get(75).getId()+","+UUID.randomUUID());
        var result=(Map<?,?>)controller.read("support","",0,params,principal);
        assertThat((List<?>)result.get("rows")).isEmpty();
        assertThat(result.get("total")).isEqualTo(0);
        verifyNoInteractions(contacts);
    }
    @Test void rejectsMalformedPatchIds() {
        var params=new LinkedMultiValueMap<String,String>();params.set("ids","invalid");
        assertThatThrownBy(()->controller.read("support","",0,params,principal))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void customerNameSearchStillResolvesNamesBeforePaging() {
        var params=new LinkedMultiValueMap<String,String>();params.set("limit","1");
        var result=(Map<?,?>)controller.read("support","customer-99",0,params,principal);
        assertThat(result.get("total")).isEqualTo(1);
        var rows=(List<Map<String,Object>>)result.get("rows");
        assertThat(rows.getFirst().get("id")).isEqualTo(members.get(99).getId());
    }
    @Test void emptyPageDoesNotResolveCustomerDetails() {
        var params=new LinkedMultiValueMap<String,String>();params.set("cursor","100");
        var result=(Map<?,?>)controller.read("support","",0,params,principal);
        assertThat((List<?>)result.get("rows")).isEmpty();
        assertThat(result.get("total")).isEqualTo(100);
        verify(contacts,never()).get(any(),any());verify(catalogs,never()).get(any(),any());
    }
}
