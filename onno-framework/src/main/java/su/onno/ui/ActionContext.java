package su.onno.ui;

import su.onno.access.AccessSubject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a custom action handler receives when invoked: the entity it ran on, (for row/detail
 * actions) the record's id, the current values of any toolbar {@link InputSpec inputs} / scalar
 * action-form fields, and any repeatable {@link InputSpec#group(String, java.util.function.Consumer)
 * row group} the form collected. The handler does whatever it likes with the services its
 * {@link EntityView} bean injected — it's just a method on a Spring bean.
 *
 * @param kind   {@code "catalogs"} or {@code "documents"}
 * @param name   the entity's route name
 * @param id     the target record's id, or {@code null} for a toolbar (list-level) action
 * @param user   the authenticated username, for the handler's own checks
 * @param inputs current values of the scalar inputs, keyed by input key (never null)
 * @param rows    collected row groups, keyed by group key; each group is a list of {column → value}
 *                rows (never null)
 * @param subject who runs the action — pass it to {@code RecordAccess} when the handler reads or
 *                changes other records on the caller's behalf; {@code null} only when constructed
 *                by code predating record policies. The framework has already checked that the
 *                caller may write the target record ({@link #id()}).
 */
public record ActionContext(String kind, String name, UUID id, String user, Map<String, String> inputs,
                            Map<String, List<Map<String, String>>> rows, AccessSubject subject) {

    public ActionContext {
        inputs = inputs == null ? Map.of() : inputs;
        rows = rows == null ? Map.of() : rows;
    }

    /** Constructor predating {@link #subject()}. */
    public ActionContext(String kind, String name, UUID id, String user, Map<String, String> inputs,
                         Map<String, List<Map<String, String>>> rows) {
        this(kind, name, id, user, inputs, rows, null);
    }

    /** Backwards-compatible constructor for a scalar-only action (no row groups). */
    public ActionContext(String kind, String name, UUID id, String user, Map<String, String> inputs) {
        this(kind, name, id, user, inputs, Map.of(), null);
    }

    /** A copy bound to {@code subject}. */
    public ActionContext withSubject(AccessSubject subject) {
        return new ActionContext(kind, name, id, user, inputs, rows, subject);
    }

    /** A copy targeting record {@code id} (a batch runs the shared inputs once per id). */
    public ActionContext withId(UUID id) {
        return new ActionContext(kind, name, id, user, inputs, rows, subject);
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
     * Split an action request body ({@code {"inputs": {key: value | rows[]}}}) into scalar inputs and
     * row groups: a value that is a JSON array is a row group (each element a {column → value}
     * object), everything else is a scalar coerced to a string. Shared by the entity-action and
     * page-action endpoints so both read the same wire shape.
     */
    public static ActionContext from(String kind, String name, UUID id, String user, Map<String, Object> body) {
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
        return new ActionContext(kind, name, id, user, scalars, groups);
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
