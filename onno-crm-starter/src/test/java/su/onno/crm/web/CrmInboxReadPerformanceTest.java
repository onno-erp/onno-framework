package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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
    final Map<UUID,String> names=new HashMap<>();
    CrmInboxWorkspaceController controller;
    @BeforeEach void setup() {
        var workspace=new CrmInboxWorkspace("support","Support",Set.of("READER"),c->true)
            .list(s->s.filter(Conversation::getChannel).options(Map.of("WILDBERRIES","WB","TELEGRAM","TG")));
        when(workspaces.requireWorkspace("support",principal,false)).thenReturn(workspace);
        when(workspaces.members(workspace)).thenReturn(members);
        when(workspaces.canAccess(any(),eq(principal),eq(false))).thenReturn(true);
        // The controller filters the page as a batch now. The stub mirrors the real rule — which is
        // per-row canAccess, proven equivalent in CrmInboxBatchAccessTest — so each test below keeps
        // steering this through its own canAccess stubbing.
        when(workspaces.accessible(anyList(),eq(principal),eq(false))).thenAnswer(invocation -> {
            List<Conversation> candidates=invocation.getArgument(0);
            return candidates.stream().filter(c->workspaces.canAccess(c,principal,false)).toList();
        });
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
            names.put(c.getCustomer(),"customer-"+i);
        }
        // Rows are decorated from the page-wide lookup now; it answers for whatever it is handed.
        when(contacts.summaries(anyCollection(),eq(principal))).thenAnswer(invocation -> {
            Collection<UUID> asked=invocation.getArgument(0);
            var answer=new LinkedHashMap<UUID,CrmContactService.Summary>();
            for(UUID id:asked)
                answer.put(id,new CrmContactService.Summary("Customers",id,
                    Map.of("id",id.toString(),"description",names.getOrDefault(id,"customer-?")),""));
            return answer;
        });
    }
    /** Every customer the controller asked about, across however many batches it used. */
    @SuppressWarnings("unchecked")
    private Set<UUID> resolvedCustomers() {
        var captor=ArgumentCaptor.forClass(Collection.class);
        verify(contacts,atLeastOnce()).summaries(captor.capture(),eq(principal));
        var asked=new LinkedHashSet<UUID>();
        for(Object batch:captor.getAllValues()) asked.addAll((Collection<UUID>)batch);
        return asked;
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
        verify(catalogs,times(2)).get(any(),any());
        assertThat(resolvedCustomers())
            .containsExactlyInAnyOrder(members.get(94).getCustomer(),members.get(92).getCustomer())
            .doesNotContain(members.get(98).getCustomer());
    }
    @Test void liveReadPatchReturnsOnlyRequestedAuthorizedConversation() {
        var params=new LinkedMultiValueMap<String,String>();
        params.set("ids",members.get(75).getId().toString());
        var result=(Map<?,?>)controller.read("support","",0,params,principal);
        var rows=(List<Map<String,Object>>)result.get("rows");
        assertThat(rows).extracting(r->r.get("id")).containsExactly(members.get(75).getId());
        assertThat(result.get("total")).isEqualTo(1);
        assertThat(resolvedCustomers()).containsExactly(members.get(75).getCustomer());
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
