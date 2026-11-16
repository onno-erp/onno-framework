package su.onno.service;

import su.onno.command.CommandRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.command.CommandDefaults;
import su.onno.command.CommandResult;
import su.onno.command.CommandSpec;
import su.onno.command.Commands;
import su.onno.metadata.CatalogDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The shared command execution path: every check {@link CommandService} enforces for all surfaces. */
class CommandServiceTest {

    private static final AccessSubject OPERATOR = AccessSubject.user("alice", Set.of("OPERATOR"));
    private static final AccessSubject CLERK = AccessSubject.user("bob", Set.of("CLERK"));

    private final BatchRunner batch = new BatchRunner(1);
    private final CatalogQueryService catalogs = mock(CatalogQueryService.class);
    private final DocumentQueryService documents = mock(DocumentQueryService.class);
    private final AccessService access = mock(AccessService.class);
    private final WebProperties properties = new WebProperties();
    private final CatalogDescriptor descriptor = mock(CatalogDescriptor.class);
    private final List<String> choices = new ArrayList<>(List.of("new"));
    private final List<String> handled = new CopyOnWriteArrayList<>();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @AfterEach
    void close() {
        batch.destroy();
    }

    private CommandService service() {
        Commands<String> bean = new Commands<>() {
            @Override public Class<String> entity() { return String.class; }

            @Override public void define(CommandSpec<String> c) {
                c.command("approve").roles("OPERATOR")
                        .availableWhen(row -> !"closed".equals(row.text("state")))
                        .input("note", i -> i.title("Note"))
                        .defaults(ctx -> CommandDefaults.ofValues(Map.of("note", "for " + ctx.id())))
                        .run(ctx -> {
                            handled.add("approve:" + ctx.id() + ":" + ctx.input("note") + ":" + ctx.user());
                            return CommandResult.reload();
                        });
                c.collectionCommand("report").run(ctx -> {
                    handled.add("report:" + ctx.id());
                    return null;
                });
                c.dynamic(live -> choices.forEach(choice -> live.command("set_" + choice)
                        .run(ctx -> { handled.add(choice + ":" + ctx.id()); return CommandResult.ok(); })));
            }
        };
        when(catalogs.require("orders")).thenReturn(descriptor);
        doReturn(String.class).when(descriptor).javaClass();
        when(descriptor.attributes()).thenReturn(List.of());
        when(catalogs.get(any(), eq(descriptor), any())).thenReturn(Map.of("state", "open"));
        when(catalogs.inScope(any(), eq(descriptor), any(), eq(AccessMode.WRITE))).thenReturn(true);
        when(access.hasAnyRole(OPERATOR, List.of("OPERATOR"))).thenReturn(true);
        return new CommandService(catalogs, documents, access, new CommandRegistry(List.of(bean)), properties, batch);
    }

    private static ServiceException.Status status(Throwable error) {
        return ((ServiceException) error).status();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listingFiltersByRolesAndResolvesLateBoundCommands() {
        CommandService service = service();
        Map<String, Object> asOperator = service.listing("catalogs", "orders", first, OPERATOR);
        assertThat((List<Map<String, Object>>) asOperator.get("commands")).extracting(m -> m.get("key"))
                .containsExactly("approve", "report", "set_new");
        assertThat((Map<String, Object>) asOperator.get("state")).containsOnlyKeys("approve");

        Map<String, Object> asClerk = service.listing("catalogs", "orders", null, CLERK);
        assertThat((List<Map<String, Object>>) asClerk.get("commands")).extracting(m -> m.get("key"))
                .containsExactly("report", "set_new");

        choices.set(0, "done");
        assertThat((List<Map<String, Object>>) service.listing("catalogs", "orders", null, OPERATOR).get("commands"))
                .extracting(m -> m.get("key")).containsExactly("approve", "report", "set_done");
    }

    @Test
    void runsWithInputsAndTheCallersIdentity() {
        CommandService service = service();
        CommandResult result = service.run("catalogs", "orders", "approve", first,
                Map.of("inputs", Map.of("note", "ok")), OPERATOR);
        assertThat(result.refresh()).isTrue();
        assertThat(service.run("catalogs", "orders", "report", null, null, CLERK)).isEqualTo(CommandResult.ok());
        assertThat(handled).containsExactly("approve:" + first + ":ok:alice", "report:null");
        assertThat(service.defaults("catalogs", "orders", "approve", first, OPERATOR).values())
                .containsEntry("note", "for " + first);
    }

    @Test
    void enforcesRolesTargetScopeAvailabilityAndReadOnly() {
        CommandService service = service();
        assertThatThrownBy(() -> service.run("catalogs", "orders", "approve", first, Map.of(), CLERK))
                .satisfies(e -> assertThat(status(e)).isEqualTo(ServiceException.Status.FORBIDDEN));
        assertThatThrownBy(() -> service.run("catalogs", "orders", "nope", first, Map.of(), OPERATOR))
                .satisfies(e -> assertThat(status(e)).isEqualTo(ServiceException.Status.NOT_FOUND));
        assertThatThrownBy(() -> service.run("catalogs", "orders", "approve", null, Map.of(), OPERATOR))
                .satisfies(e -> assertThat(status(e)).isEqualTo(ServiceException.Status.BAD_REQUEST));
        assertThatThrownBy(() -> service.run("catalogs", "orders", "report", first, Map.of(), OPERATOR))
                .satisfies(e -> assertThat(status(e)).isEqualTo(ServiceException.Status.BAD_REQUEST));

        when(catalogs.get(any(), eq(descriptor), eq(first))).thenReturn(Map.of("state", "closed"));
        assertThatThrownBy(() -> service.run("catalogs", "orders", "approve", first, Map.of(), OPERATOR))
                .satisfies(e -> assertThat(status(e)).isEqualTo(ServiceException.Status.CONFLICT));

        when(catalogs.inScope(any(), eq(descriptor), eq(second), eq(AccessMode.WRITE))).thenReturn(false);
        assertThatThrownBy(() -> service.run("catalogs", "orders", "approve", second, Map.of(), OPERATOR))
                .satisfies(e -> assertThat(status(e)).isEqualTo(ServiceException.Status.NOT_FOUND));

        doThrow(new ServiceException(ServiceException.Status.FORBIDDEN)).when(access).requireWrite(CLERK, descriptor);
        assertThatThrownBy(() -> service.run("catalogs", "orders", "report", null, Map.of(), CLERK))
                .satisfies(e -> assertThat(status(e)).isEqualTo(ServiceException.Status.FORBIDDEN));

        properties.setReadOnly(true);
        assertThatThrownBy(() -> service.run("catalogs", "orders", "report", null, Map.of(), OPERATOR))
                .satisfies(e -> assertThat(status(e)).isEqualTo(ServiceException.Status.FORBIDDEN));
        assertThat(handled).isEmpty();
    }

    @Test
    void batchRunsEachIdWithTheSameChecks() {
        CommandService service = service();
        when(catalogs.get(any(), eq(descriptor), eq(second))).thenReturn(Map.of("state", "closed"));
        Map<String, Object> result = service.runBatch("catalogs", "orders", "approve", List.of(first, second),
                Map.of("inputs", Map.of("note", "n")), OPERATOR);
        assertThat(result).containsEntry("ok", 1).containsEntry("total", 2)
                .containsEntry("failed", List.of(second.toString()));
        assertThat(handled).containsExactly("approve:" + first + ":n:alice");
        assertThatThrownBy(() -> service.runBatch("catalogs", "orders", "report", List.of(first), Map.of(), OPERATOR))
                .satisfies(e -> assertThat(status(e)).isEqualTo(ServiceException.Status.BAD_REQUEST));
    }
}
