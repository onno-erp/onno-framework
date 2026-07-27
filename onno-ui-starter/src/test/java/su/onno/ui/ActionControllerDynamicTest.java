package su.onno.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.metadata.CatalogDescriptor;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionControllerDynamicTest {

    private final BatchRunner batch = new BatchRunner(1);

    @AfterEach
    void closeBatchRunner() {
        batch.destroy();
    }

    @Test
    void menuDescriptorsAndExecutionResolveTheCurrentProviderOutput() {
        List<String> choices = new ArrayList<>(List.of("new"));
        List<UUID> handled = new CopyOnWriteArrayList<>();
        EntityView<String> view = new EntityView<>() {
            @Override public Class<String> entity() { return String.class; }

            @Override public void actions(ActionSpec actions) {
                actions.dynamic(live -> {
                    for (String choice : choices) {
                        live.action(choice).label("Set " + choice).scope(ActionScope.ROW)
                                .menu("Change status")
                                .visibleWhen(row -> row.id() != null)
                                .form(form -> form.input("note").label("Note"))
                                .formDefaults(ctx -> ActionSpec.FormDefaults.ofValues(
                                        Map.of("note", "Current " + choice)))
                                .roles("OPERATOR")
                                .handler(ctx -> {
                                    handled.add(ctx.id());
                                    return ActionResult.ok();
                                });
                    }
                });
            }
        };
        UiActionResolver resolver = new UiActionResolver(List.of(view));
        CatalogQueryService catalogs = mock(CatalogQueryService.class);
        DocumentQueryService documents = mock(DocumentQueryService.class);
        UiAccessService access = mock(UiAccessService.class);
        UiProperties properties = mock(UiProperties.class);
        CatalogDescriptor descriptor = mock(CatalogDescriptor.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Principal principal = () -> "alice";
        when(catalogs.require("orders")).thenReturn(descriptor);
        doReturn(String.class).when(descriptor).javaClass();
        when(catalogs.get(descriptor, first)).thenReturn(Map.of("_id", first));
        when(properties.isReadOnly()).thenReturn(false);
        when(access.hasAnyRole(principal, List.of("OPERATOR"))).thenReturn(true);
        ActionController controller =
                new ActionController(catalogs, documents, access, resolver, properties, batch);

        Map<String, Object> initial =
                controller.descriptors("catalogs", "orders", first, principal);
        assertThat(actionKeys(initial)).containsExactly("new");
        assertThat(firstAction(initial))
                .containsEntry("dynamicForm", true)
                .containsKey("form");
        assertThat(((Map<?, ?>) initial.get("rowActions")).containsKey("new")).isTrue();
        assertThat(controller.formDefaults("catalogs", "orders", "new", first, principal))
                .extractingByKey("values")
                .isEqualTo(Map.of("note", "Current new"));

        choices.clear();
        choices.add("done");
        Map<String, Object> refreshed =
                controller.descriptors("catalogs", "orders", first, principal);
        assertThat(actionKeys(refreshed)).containsExactly("done");
        verify(access, atLeastOnce()).requireWrite(principal, descriptor);

        controller.run("catalogs", "orders", "done", first, Map.of(), principal);
        Map<String, Object> batchResult = controller.runBatch(
                "catalogs", "orders", "done",
                Map.of("ids", List.of(first.toString(), second.toString())), principal);
        assertThat(batchResult).containsEntry("ok", 2).containsEntry("total", 2);
        assertThat(handled).containsExactly(first, first, second);
        verify(access, atLeastOnce()).hasAnyRole(principal, List.of("OPERATOR"));

        choices.clear();
        assertThatThrownBy(() ->
                controller.run("catalogs", "orders", "done", first, Map.of(), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> actionKeys(Map<String, Object> response) {
        return ((List<Map<String, Object>>) response.get("actions")).stream()
                .map(action -> action.get("key"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstAction(Map<String, Object> response) {
        return ((List<Map<String, Object>>) response.get("actions")).getFirst();
    }
}
