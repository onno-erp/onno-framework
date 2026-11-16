package su.onno.crm.web;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.*;
import su.onno.ui.*;
import su.onno.metadata.CatalogDescriptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CrmActionControllerTest {
 @Test void executesHostActionAndEnforcesRecordStateAndWorkspaceAccess() {
  var calls=new AtomicInteger();
  EntityView<Conversation> view=new EntityView<>() {
   public Class<Conversation> entity(){return Conversation.class;}
   public void actions(ActionSpec<Conversation> actions){actions.action("host.review").label("Review").scope(ActionScope.ROW)
    .enabledWhen(row->"allowed".equals(row.text("subject")))
    .handler(ctx->{assertThat(ctx.input("reason")).isEqualTo("Review requested");calls.incrementAndGet();return ActionResult.ok();});}
  };
  var scopes=mock(CrmInboxWorkspaceService.class);var catalogs=mock(CatalogQueryService.class);
  var descriptor=mock(CatalogDescriptor.class);var id=UUID.randomUUID();java.security.Principal principal=()->"reader";
  when(catalogs.forClass(Conversation.class)).thenReturn(descriptor);
  when(catalogs.get(su.onno.access.AccessSubject.system(),descriptor,id)).thenReturn(Map.of("subject","allowed"));
  var controller=new CrmActionController(scopes,new UiActionResolver(List.of(view)),mock(UiAccessService.class),catalogs,new UiProperties());
  controller.run("support",id,"host.review",Map.of("inputs",Map.of("reason","Review requested")),principal,su.onno.access.AccessSubject.user("reader",java.util.Set.of()));
  assertThat(calls).hasValue(1);
  when(catalogs.get(su.onno.access.AccessSubject.system(),descriptor,id)).thenReturn(Map.of("subject","blocked"));
  assertThatThrownBy(()->controller.run("support",id,"host.review",Map.of(),principal,su.onno.access.AccessSubject.user("reader",java.util.Set.of()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  when(scopes.requireConversation("support",id,principal,true)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
  assertThatThrownBy(()->controller.run("support",id,"host.review",Map.of(),principal,su.onno.access.AccessSubject.user("reader",java.util.Set.of()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  assertThat(calls).hasValue(1);
 }
 @Test void readOnlyModeRejectsHostActions() {
  var calls=new AtomicInteger();
  EntityView<Conversation> view=new EntityView<>() {
   public Class<Conversation> entity(){return Conversation.class;}
   public void actions(ActionSpec<Conversation> actions){actions.action("host.review").label("Review").scope(ActionScope.ROW)
    .handler(ctx->{calls.incrementAndGet();return ActionResult.ok();});}
  };
  var ui=new UiProperties();ui.setReadOnly(true);
  var controller=new CrmActionController(mock(CrmInboxWorkspaceService.class),new UiActionResolver(List.of(view)),mock(UiAccessService.class),mock(CatalogQueryService.class),ui);
  assertThatThrownBy(()->controller.run("support",UUID.randomUUID(),"host.review",Map.of(),()->"reader",su.onno.access.AccessSubject.user("reader",java.util.Set.of())))
   .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("read-only");
  assertThat(calls).hasValue(0);
 }
 @SuppressWarnings("unchecked")
 @Test void readOnlyModeListsHostActionsAsDisabled() {
  EntityView<Conversation> view=new EntityView<>() {
   public Class<Conversation> entity(){return Conversation.class;}
   public void actions(ActionSpec<Conversation> actions){actions.action("host.review").label("Review").scope(ActionScope.ROW)
    .handler(ctx->ActionResult.ok());}
  };
  var scopes=mock(CrmInboxWorkspaceService.class);var catalogs=mock(CatalogQueryService.class);
  var descriptor=mock(CatalogDescriptor.class);var id=UUID.randomUUID();java.security.Principal principal=()->"writer";
  when(catalogs.forClass(Conversation.class)).thenReturn(descriptor);
  when(catalogs.get(su.onno.access.AccessSubject.system(),descriptor,id)).thenReturn(Map.of());
  when(scopes.permitted(any(),any(),anyBoolean())).thenReturn(true);
  var ui=new UiProperties();
  var controller=new CrmActionController(scopes,new UiActionResolver(List.of(view)),mock(UiAccessService.class),catalogs,ui);
  assertThat((List<Map<String,Object>>)controller.list("support",id,principal)).extracting(a->a.get("enabled")).containsExactly(true);
  ui.setReadOnly(true);
  assertThat((List<Map<String,Object>>)controller.list("support",id,principal)).extracting(a->a.get("enabled")).containsExactly(false);
 }
}
