package su.onno.command;

import su.onno.fields.Field;
import su.onno.fields.Fields;
import su.onno.metadata.AttributeDescriptor;
import su.onno.repository.EnumerationPersistence;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A read-only view of one record's resolved data (a list row, or a loaded record), handed to the
 * per-record functions of a {@link Command} ({@code title(row -> …)}, {@code availableWhen},
 * {@code enabledWhen}) and to a list's conditional row formatting, so a command can vary by that
 * record's state — "Suspend" on a running record, "Resume" on a stopped one, or a command offered
 * only where it applies.
 *
 * <p>It wraps the resolved row the query already computed, so no extra query runs: raw attribute
 * values plus the resolved {@code _display} strings refs and enums carry. Read a field with
 * {@link #text(String)} (the display value — for an enum the {@code @EnumLabel} value when
 * labelled, else the constant name), {@link #enumValue(String, Class)} (typed back to your enum),
 * or {@link #get(String)} (the raw stored value). All lookups are case-insensitive.</p>
 *
 * <p>{@code E} is the owning entity. Typed reads accept only its getters (including inherited
 * getters); string reads remain an escape hatch for storage-column names.</p>
 *
 * <pre>
 * c.command("toggle")
 *  .title(row -&gt; row.enumValue(Job::getStatus, Status.class) == Status.STOPPED ? "Resume" : "Suspend")
 *  .run(ctx -&gt; ...);
 * </pre>
 */
public final class RecordRow<E> {

    private final Map<String, Object> data;
    private final List<AttributeDescriptor> attributes;

    public RecordRow(Map<String, Object> data) {
        this(data, List.of());
    }

    /**
     * Wrap a storage-keyed row with its entity's attributes. Typed getters resolve Java property
     * names to the declared storage columns, including custom {@code @Attribute(name = "...")}
     * names. String getters and {@link #values()} retain their existing column-based contract.
     */
    public RecordRow(Map<String, Object> data, List<AttributeDescriptor> attributes) {
        this.data = data == null ? Map.of() : data;
        this.attributes = List.copyOf(attributes);
    }

    private String column(Field<?, ?> field) {
        String name = Fields.name(field);
        for (AttributeDescriptor attribute : attributes) {
            if (attribute.fieldName().equals(name)) {
                return attribute.columnName();
            }
        }
        return name;
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
    public <V> Object get(Field<? super E, V> field) {
        return get(column(field));
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
    public boolean bool(Field<? super E, Boolean> field) {
        return bool(column(field));
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
    public <V> String text(Field<? super E, V> field) {
        return text(column(field));
    }

    /**
     * {@code column} resolved back to a constant of {@code enumType}, or {@code null} if
     * empty/unmatched. Enum fields are stored as deterministic UUIDs; display labels and legacy
     * constant-name strings are deliberately not accepted as storage identities.
     */
    public <V extends Enum<V>> V enumValue(String column, Class<V> enumType) {
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
            for (V constant : enumType.getEnumConstants()) {
                if (EnumerationPersistence.resolveId(enumType, constant).equals(id)) {
                    return constant;
                }
            }
        }
        return null;
    }

    /** Resolve an enum using a compiler-checked field reference. */
    public <V extends Enum<V>> V enumValue(Field<? super E, V> field, Class<V> enumType) {
        return enumValue(column(field), enumType);
    }
    /** The underlying row map (resolved values keyed by column) — an escape hatch for ad-hoc reads. */
    public Map<String, Object> values() {
        return data;
    }
}
