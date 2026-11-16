package su.onno.command;

import org.junit.jupiter.api.Test;
import su.onno.access.AccessSubject;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An {@link CommandContext} always carries the {@link AccessSubject} its handler runs for (#462):
 * a handler passes it to {@code RecordAccess}, so a context without one cannot be built.
 */
class CommandContextTest {

    private static final AccessSubject USER = AccessSubject.user("u@ctx.test", Set.of("STAFF"));

    @Test
    void aContextWithoutASubjectCannotBeBuilt() {
        assertThatThrownBy(() -> new CommandContext("catalogs", "orders", null, "u", Map.of(), Map.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CommandContext.from("catalogs", "orders", null, "u", Map.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aCopyKeepsTheSubjectAndCannotDropIt() {
        CommandContext ctx = CommandContext.from("catalogs", "orders", null, "u", Map.of(), USER);
        UUID id = UUID.randomUUID();

        assertThat(ctx.withId(id).subject()).isSameAs(USER);
        assertThat(ctx.withId(id).id()).isEqualTo(id);
        assertThat(ctx.withSubject(AccessSubject.system()).subject()).isSameAs(AccessSubject.system());
        assertThatThrownBy(() -> ctx.withSubject(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromSplitsTheBodyAndBindsTheSubject() {
        UUID id = UUID.randomUUID();
        CommandContext ctx = CommandContext.from("documents", "orders", id, "u", Map.of("inputs", Map.of(
                "reason", "late",
                "count", 3,
                "lines", List.of(Map.of("book", "b-1", "qty", 2)))), USER);

        assertThat(ctx.subject()).isSameAs(USER);
        assertThat(ctx.id()).isEqualTo(id);
        assertThat(ctx.input("reason")).isEqualTo("late");
        assertThat(ctx.input("count")).isEqualTo("3");
        assertThat(ctx.inputRows("lines")).containsExactly(Map.of("book", "b-1", "qty", "2"));
    }

    @Test
    void missingInputsAndRowsBecomeEmpty() {
        CommandContext ctx = new CommandContext("catalogs", "orders", null, "u", null, null, USER);

        assertThat(ctx.inputs()).isEmpty();
        assertThat(ctx.rows()).isEmpty();
        assertThat(CommandContext.from("catalogs", "orders", null, "u", null, USER).inputs()).isEmpty();
    }
}
