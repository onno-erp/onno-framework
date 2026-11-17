package su.onno.command;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One resolved command, built by {@link CommandBuilder}.
 *
 * @param key           stable key, unique within the entity (REST path segment, MCP tool suffix)
 * @param target        whether it runs on one record or on the collection
 * @param title         fixed human title (falls back to the key); used wherever no record is in hand
 * @param titleFn       optional per-record title (record commands only)
 * @param description   optional longer explanation (MCP tool description, tooltip), may be blank
 * @param group         optional semantic grouping label ("Change status"), may be blank
 * @param roles         roles allowed to run it on top of entity write access; empty = every writer
 * @param availableFn   optional per-record availability (record commands only)
 * @param enabledFn     optional per-record enabled state (record commands only)
 * @param inputs        scalar inputs collected before it runs
 * @param inputGroups   repeatable row groups collected before it runs
 * @param defaultsFn    optional server-computed opening values of the inputs
 * @param handler       what it does
 * @param <E>           the owning entity
 */
public record Command<E>(String key, CommandTarget target, String title,
                         Function<RecordRow<E>, String> titleFn, String description, String group,
                         List<String> roles, Predicate<RecordRow<E>> availableFn,
                         Predicate<RecordRow<E>> enabledFn, List<CommandInput> inputs,
                         List<CommandInputGroup> inputGroups,
                         Function<CommandContext, CommandDefaults> defaultsFn,
                         Function<CommandContext, CommandResult> handler) {

    public Command {
        description = description == null ? "" : description;
        group = group == null ? "" : group;
        roles = roles == null ? List.of() : List.copyOf(roles);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        inputGroups = inputGroups == null ? List.of() : List.copyOf(inputGroups);
    }

    /** Whether it runs on one record (needs an id). */
    public boolean isRecordCommand() {
        return target == CommandTarget.RECORD;
    }

    /** Whether it declares inputs (scalar or row group) a caller collects before running it. */
    public boolean hasInputs() {
        return !inputs.isEmpty() || !inputGroups.isEmpty();
    }

    /** Whether the inputs' opening values are computed server-side ({@code defaults}). */
    public boolean hasDynamicDefaults() {
        return defaultsFn != null && hasInputs();
    }

    /** Whether its title, availability or enabled state varies per record. */
    public boolean isPerRecord() {
        return titleFn != null || availableFn != null || enabledFn != null;
    }
}
