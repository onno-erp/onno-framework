package su.onno.command;

import su.onno.access.AccessSubject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What a {@link Command} handler receives: the entity it ran on, (for a record command) the
 * record's id, the values of its scalar inputs and any repeatable row groups. The handler does
 * whatever it likes with the services its {@link Commands} bean injected — it's just a method on a
 * Spring bean.
 *
 * @param kind   {@code "catalogs"} or {@code "documents"}
 * @param name   the entity's route name
 * @param id     the target record's id, or {@code null} for a collection command
 * @param user   the caller's username, for the handler's own checks (may be {@code null})
 * @param inputs values of the scalar inputs, keyed by input key (never null)
 * @param rows    collected row groups, keyed by group key; each group is a list of {column → value}
 *                rows (never null)
 * @param subject who runs the command — pass it to {@code RecordAccess} when the handler reads or
 *                changes other records on the caller's behalf; never {@code null} (trusted code
 *                passes {@link AccessSubject#system()}). The framework has already checked that the
 *                caller may write the target record ({@link #id()}).
 */
public record CommandContext(String kind, String name, UUID id, String user, Map<String, String> inputs,
                            Map<String, List<Map<String, String>>> rows, AccessSubject subject) {

    public CommandContext {
        inputs = inputs == null ? Map.of() : inputs;
        rows = rows == null ? Map.of() : rows;
        Objects.requireNonNull(subject, "subject is required; pass AccessSubject.system() for trusted code");
    }

    /** A copy bound to {@code subject}. */
    public CommandContext withSubject(AccessSubject subject) {
        return new CommandContext(kind, name, id, user, inputs, rows, subject);
    }

    /** A copy targeting record {@code id} (a batch runs the shared inputs once per id). */
    public CommandContext withId(UUID id) {
        return new CommandContext(kind, name, id, user, inputs, rows, subject);
    }

    /** The current value of scalar input {@code key}, or {@code ""} if absent. */
    public String input(String key) {
        return inputs.getOrDefault(key, "");
    }

    /**
     * The rows collected for row group {@code key}, or an empty list if absent. Each row is a
     * {@code column → value} map (values are strings, as declared by the columns' {@link InputType}).
     */
    public List<Map<String, String>> inputRows(String key) {
        return rows.getOrDefault(key, List.of());
    }

    /**
     * Split a command request body ({@code {"inputs": {key: value | rows[]}}}) into scalar inputs
     * and row groups: a value that is a JSON array is a row group (each element a {column → value}
     * object), everything else is a scalar coerced to a string. Every surface (REST, MCP) reads the
     * same shape. {@code subject} is the caller the
     * handler runs for.
     */
    public static CommandContext from(String kind, String name, UUID id, String user, Map<String, Object> body,
                                     AccessSubject subject) {
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        if (body != null && body.get("inputs") instanceof Map<?, ?> raw) {
            raw.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (v instanceof List<?> list) {
                    groups.put(key, rowsOf(list));
                } else {
                    scalars.put(key, v == null ? "" : String.valueOf(v));
                }
            });
        }
        return new CommandContext(kind, name, id, user, scalars, groups, subject);
    }

    /** Coerce a JSON array of row objects into a list of {@code column → string} maps. */
    private static List<Map<String, String>> rowsOf(List<?> list) {
        List<Map<String, String>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            Map<String, String> row = new LinkedHashMap<>();
            if (o instanceof Map<?, ?> cells) {
                cells.forEach((c, val) -> row.put(String.valueOf(c), val == null ? "" : String.valueOf(val)));
            }
            out.add(row);
        }
        return out;
    }
}
