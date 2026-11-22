package su.onno.service;

import su.onno.command.CommandRegistry;
import org.junit.jupiter.api.Test;
import su.onno.annotations.Catalog;
import su.onno.command.CommandDefaults;
import su.onno.command.CommandResult;
import su.onno.command.CommandSpec;
import su.onno.command.Commands;
import su.onno.repository.EnumerationPersistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Merging, per-record state and the JSON descriptors of {@link CommandRegistry}. */
class CommandRegistryTest {

    enum Status { RUNNING, STOPPED }

    @Catalog(name = "RegistryBooks")
    static class Book {
    }

    private static <E> Commands<E> bean(Class<E> entity, java.util.function.Consumer<CommandSpec<E>> body) {
        return new Commands<>() {
            @Override public Class<E> entity() { return entity; }
            @Override public void define(CommandSpec<E> commands) { body.accept(commands); }
        };
    }

    private static Map<String, Object> row(Status status) {
        return Map.of("status", EnumerationPersistence.resolveId(Status.class, status));
    }

    @Test
    void firstBeanAndFirstDeclarationWinDuplicateKeys() {
        @SuppressWarnings("rawtypes")
        List<Commands> beans = List.of(
                bean(Integer.class, c -> {
                    c.command("a").title("First").run(ctx -> CommandResult.ok());
                    c.command("a").title("Dup").run(ctx -> CommandResult.ok());
                }),
                bean(Integer.class, c -> c.command("a").title("Second").run(ctx -> CommandResult.ok())));
        CommandRegistry registry = new CommandRegistry(beans);
        assertThat(registry.commands(Integer.class)).singleElement()
                .satisfies(c -> assertThat(c.title()).isEqualTo("First"));
        assertThat(registry.entities()).containsExactly(Integer.class);
    }

    enum TestRole implements su.onno.access.RoleKey { MANAGER }

    @Test
    void commandRolesMustBeDeclaredRoles() {
        @SuppressWarnings("rawtypes")
        List<Commands> beans = List.of(bean(Integer.class, c -> {
            c.command("ok").roles("manager", "ROLE_MANAGER").run(ctx -> CommandResult.ok());
            c.command("typo").roles("MANGER").run(ctx -> CommandResult.ok());
        }));
        CommandRegistry registry = new CommandRegistry(beans);
        su.onno.access.RoleGrants grants = su.onno.access.RoleGrants.of(TestRole.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.requireKnownRoles(grants))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MANGER")
                .hasMessageContaining("Integer#typo");
        // An application without grants has no role vocabulary to check against.
        registry.requireKnownRoles(su.onno.access.RoleGrants.open());
    }

    @Test
    void lateBoundCommandsResolvePerRequest() {
        List<String> live = new ArrayList<>(List.of("x"));
        @SuppressWarnings("rawtypes")
        List<Commands> beans = List.of(bean(String.class, c -> {
            c.collectionCommand("fixed").run(ctx -> CommandResult.ok());
            c.dynamic(d -> live.forEach(k -> d.command(k).group("Change status").run(ctx -> CommandResult.ok())));
        }));
        CommandRegistry registry = new CommandRegistry(beans);
        assertThat(registry.hasDynamicCommands(String.class)).isTrue();
        assertThat(registry.staticCommands(String.class)).extracting(c -> c.key()).containsExactly("fixed");
        assertThat(registry.commands(String.class)).extracting(c -> c.key()).containsExactly("fixed", "x");
        live.set(0, "y");
        assertThat(registry.find(String.class, "y")).isNotNull();
        assertThat(registry.find(String.class, "x")).isNull();
    }

    @Test
    void rowStateCoversOnlyPerRecordRecordCommands_andThrowingFunctionsFallBack() {
        @SuppressWarnings("rawtypes")
        List<Commands> beans = List.of(bean(Status.class, c -> {
            c.command("toggle").title("Toggle")
                    .title(r -> r.enumValue("status", Status.class) == Status.STOPPED ? "Resume" : "Suspend")
                    .run(ctx -> CommandResult.ok());
            c.command("stop").availableWhen(r -> r.enumValue("status", Status.class) == Status.RUNNING)
                    .run(ctx -> CommandResult.ok());
            c.command("broken").enabledWhen(r -> { throw new IllegalStateException(); })
                    .run(ctx -> CommandResult.ok());
            c.command("plain").run(ctx -> CommandResult.ok());
            c.collectionCommand("all").run(ctx -> CommandResult.ok());
        }));
        CommandRegistry registry = new CommandRegistry(beans);
        assertThat(registry.hasPerRecordCommands(Status.class)).isTrue();

        Map<String, Object> state = registry.rowState(Status.class, row(Status.STOPPED), List.of());
        assertThat(state).containsOnlyKeys("toggle", "stop", "broken");
        assertThat(state.get("toggle")).isEqualTo(Map.of("visible", true, "enabled", true, "title", "Resume"));
        assertThat(state.get("stop")).isEqualTo(Map.of("visible", false, "enabled", true));
        assertThat(state.get("broken")).isEqualTo(Map.of("visible", true, "enabled", true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void descriptorCarriesBehaviourOnly() {
        CommandSpec<Object> spec = new CommandSpec<>();
        spec.command("cancel").title("Cancel order").description("Cancels it.").group("Lifecycle")
                .input("reason", i -> i.title("Reason").longText().required())
                .inputRows("lines", g -> g.title("Lines")
                        .column("book", col -> col.title("Book").reference(Book.class)))
                .defaults(ctx -> CommandDefaults.none())
                .run(ctx -> CommandResult.ok());
        spec.collectionCommand("report").run(ctx -> CommandResult.ok());

        Map<String, Object> cancel = CommandRegistry.descriptor(spec.commands().get(0));
        assertThat(cancel).containsOnlyKeys("key", "title", "description", "target", "group", "form", "dynamicForm");
        assertThat(cancel).containsEntry("target", "record").containsEntry("group", "Lifecycle")
                .containsEntry("dynamicForm", true);
        List<Map<String, Object>> form = (List<Map<String, Object>>) cancel.get("form");
        assertThat(form.get(0)).containsEntry("kind", "field").containsEntry("key", "reason")
                .containsEntry("label", "Reason").containsEntry("type", "longText").containsEntry("required", true);
        assertThat(form.get(1)).containsEntry("kind", "group").containsEntry("key", "lines");
        assertThat((List<Map<String, Object>>) form.get(1).get("columns")).singleElement()
                .satisfies(col -> assertThat(col).containsEntry("type", "reference")
                        .containsEntry("reference", "RegistryBooks").containsEntry("refKind", "catalog"));

        assertThat(CommandRegistry.descriptor(spec.commands().get(1)))
                .isEqualTo(Map.of("key", "report", "title", "report", "target", "collection"));
    }
}
