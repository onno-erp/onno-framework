package su.onno.ui;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A read-only view of one list row, handed to the per-row functions of a row {@link ActionSpec
 * action} ({@code .icon(...)}, {@code .label(...)}, {@code .visibleWhen(...)},
 * {@code .enabledWhen(...)}) so a single control can vary by that row's state — a {@code pause}
 * "Suspend" on a running record flipping to a {@code play} "Resume" when it's stopped, or a button
 * shown only on the rows it applies to.
 *
 * <p>It wraps the resolved row the list already computed (the same shape the grid renders), so no
 * extra query runs: raw attribute values plus the resolved {@code _display} strings refs and enums
 * carry. Read a field with {@link #text(String)} (the human/display value — for an enum this is the
 * constant name), {@link #enumValue(String, Class)} (typed back to your enum), or {@link
 * #get(String)} (the raw stored value). All lookups are case-insensitive.</p>
 *
 * <pre>
 * a.action("toggle").scope(ActionScope.ROW)
 *  .icon(row -&gt; row.enumValue("status", Status.class) == Status.STOPPED ? "play" : "pause")
 *  .label(row -&gt; row.enumValue("status", Status.class) == Status.STOPPED ? "Resume" : "Suspend")
 *  .handler(ctx -&gt; ...);
 * </pre>
 */
public final class ActionRow {

    private final Map<String, Object> data;

    public ActionRow(Map<String, Object> data) {
        this.data = data == null ? Map.of() : data;
    }

    /** This row's id ({@code _id}), or {@code null} if absent/unparseable. */
    public UUID id() {
        Object v = get("_id");
        if (v == null) {
            return null;
        }
        if (v instanceof UUID u) {
            return u;
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The raw stored value of {@code column} (a ref/enum reads back as its UUID), or {@code null}. */
    public Object get(String column) {
        if (column == null) {
            return null;
        }
        Object v = data.get(column);
        if (v != null) {
            return v;
        }
        v = data.get(column.toLowerCase(Locale.ROOT));
        if (v != null) {
            return v;
        }
        return data.get(column.toUpperCase(Locale.ROOT));
    }

    /**
     * The display string of {@code column}: the resolved {@code {column}_display} (a ref's label, an
     * enum's constant name) when present, else the raw value as text, else {@code ""} — never null.
     */
    public String text(String column) {
        Object display = get(column + "_display");
        Object v = display != null ? display : get(column);
        return v == null ? "" : v.toString();
    }

    /**
     * {@code column} resolved back to a constant of {@code enumType} (matched on the enum's name, the
     * value {@link #text(String)} returns for an enum column), or {@code null} if empty/unmatched.
     */
    public <E extends Enum<E>> E enumValue(String column, Class<E> enumType) {
        String name = text(column);
        if (name.isEmpty()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The underlying row map (resolved values keyed by column) — an escape hatch for ad-hoc reads. */
    public Map<String, Object> values() {
        return data;
    }
}
