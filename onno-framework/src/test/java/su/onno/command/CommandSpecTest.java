package su.onno.command;

import org.junit.jupiter.api.Test;
import su.onno.annotations.Catalog;
import su.onno.repository.EnumerationPersistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The command declaration DSL: targets, per-record functions, inputs, and late-bound commands. */
class CommandSpecTest {

    enum Status { RUNNING, STOPPED }

    @Catalog(name = "CommandSpecBooks")
    static class Book {
    }

    @Test
    void recordRow_readsId_displayText_andEnumValue() {
        UUID id = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("_id", id.toString());
        data.put("status", EnumerationPersistence.resolveId(Status.class, Status.STOPPED).toString());
        data.put("status_display", "STOPPED");
        data.put("name", "Acme");

        RecordRow<Object> row = new RecordRow<>(data);
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.text("status")).isEqualTo("STOPPED");
        assertThat(row.enumValue("status", Status.class)).isEqualTo(Status.STOPPED);
        assertThat(row.text("missing")).isEmpty();
        assertThat(new RecordRow<>(Map.of("status", "STOPPED")).enumValue("status", Status.class)).isNull();
        assertThat(new RecordRow<>(Map.of("CODE", "C-1")).get("code")).isEqualTo("C-1");
        assertThat(new RecordRow<>(null).id()).isNull();
    }

    @Test
    void recordCommand_capturesPerRecordFunctions() {
        CommandSpec<Object> spec = new CommandSpec<>();
        spec.command("toggle").title("Toggle")
                .title(row -> row.enumValue("status", Status.class) == Status.STOPPED ? "Resume" : "Suspend")
                .description("Pauses or resumes the job.")
                .group("Lifecycle")
                .roles("OPERATOR")
                .availableWhen(row -> row.enumValue("status", Status.class) != null)
                .enabledWhen(row -> true)
                .run(ctx -> CommandResult.ok());

        Command<Object> c = spec.commands().getFirst();
        assertThat(c.target()).isEqualTo(CommandTarget.RECORD);
        assertThat(c.isPerRecord()).isTrue();
        assertThat(c.title()).isEqualTo("Toggle");
        assertThat(c.description()).isEqualTo("Pauses or resumes the job.");
        assertThat(c.group()).isEqualTo("Lifecycle");
        assertThat(c.roles()).containsExactly("OPERATOR");
        RecordRow<Object> stopped = new RecordRow<>(Map.of(
                "status", EnumerationPersistence.resolveId(Status.class, Status.STOPPED)));
        assertThat(c.titleFn().apply(stopped)).isEqualTo("Resume");
        assertThat(c.availableFn().test(stopped)).isTrue();
    }

    @Test
    void staticCommand_isNotPerRecord_andTitleDefaultsToKey() {
        CommandSpec<Object> spec = new CommandSpec<>();
        spec.collectionCommand("ping").run(ctx -> CommandResult.ok());

        Command<Object> c = spec.commands().getFirst();
        assertThat(c.target()).isEqualTo(CommandTarget.COLLECTION);
        assertThat(c.isPerRecord()).isFalse();
        assertThat(c.title()).isEqualTo("ping");
        assertThat(c.description()).isEmpty();
        assertThat(c.group()).isEmpty();
    }

    @Test
    void inputsAndRowGroupsResolve() {
        CommandSpec<Object> spec = new CommandSpec<>();
        spec.collectionCommand("receive")
                .input("reason", i -> i.title("Reason").longText().required())
                .input("kind", i -> i.oneOf("A", "B").defaultValue("A"))
                .input("book", i -> i.reference(Book.class))
                .inputRows("lines", g -> g.title("Lines").required()
                        .column("qty", c -> c.title("Qty").number().required()))
                .defaults(ctx -> CommandDefaults.ofValues(Map.of("reason", "x")))
                .run(ctx -> CommandResult.ok());

        Command<Object> c = spec.commands().getFirst();
        assertThat(c.hasInputs()).isTrue();
        assertThat(c.hasDynamicDefaults()).isTrue();
        assertThat(c.inputs()).extracting(CommandInput::type)
                .containsExactly(InputType.LONG_TEXT, InputType.SELECT, InputType.REFERENCE);
        assertThat(c.inputs().get(0).required()).isTrue();
        assertThat(c.inputs().get(1).options()).containsExactly("A", "B");
        assertThat(c.inputs().get(1).defaultValue()).isEqualTo("A");
        assertThat(c.inputs().get(2).reference()).isEqualTo("CommandSpecBooks");
        assertThat(c.inputs().get(2).refKind()).isEqualTo("catalog");
        CommandInputGroup lines = c.inputGroups().getFirst();
        assertThat(lines.required()).isTrue();
        assertThat(lines.columns()).singleElement().extracting(CommandInput::type).isEqualTo(InputType.NUMBER);
    }

    @Test
    void invalidDeclarationsFailFast() {
        CommandSpec<Object> noHandler = new CommandSpec<>();
        noHandler.command("x");
        assertThatThrownBy(noHandler::commands).hasMessageContaining("run(");

        CommandSpec<Object> perRecordCollection = new CommandSpec<>();
        perRecordCollection.collectionCommand("y").availableWhen(r -> true).run(ctx -> CommandResult.ok());
        assertThatThrownBy(perRecordCollection::commands).hasMessageContaining("per-record");

        CommandSpec<Object> badRef = new CommandSpec<>();
        badRef.command("z").input("r", i -> i.reference(String.class)).run(ctx -> CommandResult.ok());
        assertThatThrownBy(badRef::commands).hasMessageContaining("neither");
    }

    @Test
    void dynamicProvider_isLateBound_andPreservesItsDeclarationPosition() {
        AtomicInteger evaluations = new AtomicInteger();
        List<String> liveKeys = new ArrayList<>(List.of("one", "two"));
        CommandSpec<Object> spec = new CommandSpec<>();
        spec.command("before").run(ctx -> CommandResult.ok());
        spec.dynamic(live -> {
            evaluations.incrementAndGet();
            for (String key : liveKeys) {
                live.command(key).title(key).run(ctx -> CommandResult.ok());
            }
        });
        spec.collectionCommand("after").run(ctx -> CommandResult.ok());

        assertThat(spec.hasDynamicCommands()).isTrue();
        assertThat(spec.commands()).extracting(Command::key).containsExactly("before", "after");
        assertThat(evaluations).hasValue(0);
        assertThat(spec.resolveCommands()).extracting(Command::key)
                .containsExactly("before", "one", "two", "after");
        liveKeys.remove("one");
        liveKeys.add(0, "three");
        assertThat(spec.resolveCommands()).extracting(Command::key)
                .containsExactly("before", "three", "two", "after");
        assertThat(evaluations).hasValue(2);
    }
}
