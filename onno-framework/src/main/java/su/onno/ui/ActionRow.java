package su.onno.ui;

import su.onno.fields.Field;
import su.onno.fields.Fields;
import su.onno.repository.EnumerationPersistence;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A read-only view of one record's resolved data — a list row, or the record a detail surface
 * loaded — handed to the per-record functions of an {@link ActionSpec action}
 * ({@code .icon(...)}, {@code .label(...)}, {@code .visibleWhen(...)}, {@code .enabledWhen(...)})
 * and to a list's {@link ListSpec#rowStyle conditional row formatting}, so a single control can
 * vary by that record's state — a {@code pause} "Suspend" on a running record flipping to a
 * {@code play} "Resume" when it's stopped, or a button shown only on the rows it applies to.
 *
 * <p>It wraps the resolved row the list already computed (the same shape the grid renders), so no
 * extra query runs: raw attribute values plus the resolved {@code _display} strings refs and enums
 * carry. Read a field with {@link #text(String)} (the human/display value — for an enum this is the
 * {@code @EnumLabel} value when labelled, else the constant name), {@link #enumValue(String, Class)}
 * (typed back to your enum), or {@link #get(String)} (the raw stored value). All lookups are
 * case-insensitive.</p>
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

    /** Read a raw value using a compiler-checked field reference. */
    public <E, V> Object get(Field<E, V> field) {
        return get(Fields.name(field));
    }

    /**
     * The column read as a boolean: {@code true} for a {@code Boolean.TRUE}, the strings
     * {@code "true"}/{@code "t"}/{@code "1"} (case-insensitive, how H2/Postgres booleans read back
     * through the resolved row), or a non-zero number; {@code false} otherwise (including absent).
     */
    public boolean bool(String column) {
        Object v = get(column);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        if (v == null) {
            return false;
        }
        String s = v.toString().trim();
        return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("t") || s.equals("1");
    }

    /** Read a boolean using a compiler-checked field reference. */
    public <E> boolean bool(Field<E, Boolean> field) {
        return bool(Fields.name(field));
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

    /** Read display text using a compiler-checked field reference. */
    public <E, V> String text(Field<E, V> field) {
        return text(Fields.name(field));
    }

    /**
     * {@code column} resolved back to a constant of {@code enumType}, or {@code null} if
     * empty/unmatched. Enum fields are stored as deterministic UUIDs; display labels and legacy
     * constant-name strings are deliberately not accepted as storage identities.
     */
    public <E extends Enum<E>> E enumValue(String column, Class<E> enumType) {
        // 1) Raw UUID — how an enum value is actually stored (see EnumerationPersistence).
        Object raw = get(column);
        UUID id = null;
        if (raw instanceof UUID u) {
            id = u;
        } else if (raw != null) {
            try {
                id = UUID.fromString(raw.toString());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (id != null) {
            for (E constant : enumType.getEnumConstants()) {
                if (EnumerationPersistence.resolveId(enumType, constant).equals(id)) {
                    return constant;
                }
            }
        }
        return null;
    }

    /** Resolve an enum using a compiler-checked field reference. */
    public <T, E extends Enum<E>> E enumValue(Field<T, E> field, Class<E> enumType) {
        return enumValue(Fields.name(field), enumType);
    }
    /** The underlying row map (resolved values keyed by column) — an escape hatch for ad-hoc reads. */
    public Map<String, Object> values() {
        return data;
    }
}
