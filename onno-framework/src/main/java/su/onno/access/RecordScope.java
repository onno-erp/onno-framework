package su.onno.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Which records of one entity a subject may see or change — a small, closed predicate tree that
 * the framework compiles to a SQL {@code WHERE} fragment (so it composes with keyset paging,
 * counts, groups and aggregates) and can also evaluate in memory.
 *
 * <p>It is deliberately <em>not</em> a Java lambda over rows: a lambda can only filter rows already
 * loaded, which breaks paging, counts and aggregates and invites leaks. Field names are entity
 * attribute names (validated at startup); values are literals or {@link Subject} placeholders, and
 * every value binds as a parameter.
 *
 * <pre>{@code
 * RecordScope.eq("owner", Subject.recordId())               // owner = :subject.recordId
 * RecordScope.in("region", Subject.attribute("regions"))    // collection attribute
 * RecordScope.eq("status", OrderStatus.ACTIVE)              // literal (enums bind as their UUID)
 * RecordScope.via("tenant")                                 // ref: target must be in *its* scope
 * RecordScope.via("tenant", RecordScope.eq("owner", Subject.recordId()))
 * RecordScope.and(a, b); RecordScope.or(a, b); RecordScope.not(a);
 * RecordScope.all(); RecordScope.none();
 * }</pre>
 */
public sealed interface RecordScope {

    /** Every record. */
    static RecordScope all() {
        return All.INSTANCE;
    }

    /** No record. */
    static RecordScope none() {
        return None.INSTANCE;
    }

    /** {@code field = value}; {@code value} is a literal or a {@link Subject} placeholder. */
    static RecordScope eq(String field, Object value) {
        return new Eq(field, value);
    }

    /** {@code field IN (values)}; {@code values} is a literal collection or a {@link Subject} placeholder. */
    static RecordScope in(String field, Object values) {
        return new In(field, values);
    }

    /** {@code field IS NULL}. */
    static RecordScope isNull(String field) {
        return new IsNull(field);
    }

    /** The referenced record (ref {@code field}) must itself be readable under its own policies. */
    static RecordScope via(String field) {
        return new Via(field, null);
    }

    /** The referenced record (ref {@code field}) must satisfy {@code targetScope}. */
    static RecordScope via(String field, RecordScope targetScope) {
        return new Via(field, Objects.requireNonNull(targetScope, "targetScope"));
    }

    static RecordScope and(RecordScope... parts) {
        return new And(List.of(parts));
    }

    static RecordScope or(RecordScope... parts) {
        return new Or(List.of(parts));
    }

    static RecordScope not(RecordScope part) {
        return new Not(part);
    }

    /** Matches every record. */
    enum All implements RecordScope { INSTANCE; @Override public String toString() { return "all"; } }

    /** Matches no record. */
    enum None implements RecordScope { INSTANCE; @Override public String toString() { return "none"; } }

    record Eq(String field, Object value) implements RecordScope {
        public Eq {
            requireField(field);
            requireValue(value, false);
        }

        @Override
        public String toString() {
            return field + " = " + value;
        }
    }

    record In(String field, Object values) implements RecordScope {
        public In {
            requireField(field);
            requireValue(values, true);
        }

        @Override
        public String toString() {
            return field + " in " + values;
        }
    }

    record IsNull(String field) implements RecordScope {
        public IsNull {
            requireField(field);
        }

        @Override
        public String toString() {
            return field + " is null";
        }
    }

    /**
     * Ref traversal. {@code target == null} inherits the target entity's own policies for the same
     * subject (a "readable target" check); otherwise the target must satisfy {@code target}.
     */
    record Via(String field, RecordScope target) implements RecordScope {
        public Via {
            requireField(field);
        }

        @Override
        public String toString() {
            return "via " + field + (target == null ? "" : " (" + target + ")");
        }
    }

    record And(List<RecordScope> parts) implements RecordScope {
        public And {
            parts = copyParts(parts);
        }

        @Override
        public String toString() {
            return "(" + String.join(" and ", parts.stream().map(Object::toString).toList()) + ")";
        }
    }

    record Or(List<RecordScope> parts) implements RecordScope {
        public Or {
            parts = copyParts(parts);
        }

        @Override
        public String toString() {
            return "(" + String.join(" or ", parts.stream().map(Object::toString).toList()) + ")";
        }
    }

    record Not(RecordScope part) implements RecordScope {
        public Not {
            Objects.requireNonNull(part, "part");
        }

        @Override
        public String toString() {
            return "not " + part;
        }
    }

    private static void requireField(String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("RecordScope field must not be blank");
        }
    }

    private static void requireValue(Object value, boolean collectionAllowed) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "RecordScope value must not be null; use RecordScope.isNull(field)");
        }
        if (value instanceof Collection<?> c && !collectionAllowed) {
            throw new IllegalArgumentException("RecordScope.eq takes a single value; use RecordScope.in for " + c);
        }
        if (value instanceof RecordScope) {
            throw new IllegalArgumentException("RecordScope value must be a literal or a Subject placeholder");
        }
    }

    private static List<RecordScope> copyParts(List<RecordScope> parts) {
        Objects.requireNonNull(parts, "parts");
        List<RecordScope> copy = new ArrayList<>(parts.size());
        for (RecordScope p : parts) copy.add(Objects.requireNonNull(p, "scope part"));
        return List.copyOf(copy);
    }

    /**
     * {@code a OR b}, simplified: {@code all} absorbs, {@code none} is the identity. Used to combine
     * the scopes of several applicable policies.
     */
    static RecordScope anyOf(List<RecordScope> scopes) {
        List<RecordScope> parts = new ArrayList<>();
        for (RecordScope s : scopes) {
            if (s == All.INSTANCE) return all();
            if (s != None.INSTANCE) parts.add(s);
        }
        if (parts.isEmpty()) return none();
        return parts.size() == 1 ? parts.get(0) : new Or(parts);
    }
}
