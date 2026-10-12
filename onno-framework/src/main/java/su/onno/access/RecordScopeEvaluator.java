package su.onno.access;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Evaluates a {@link RecordScope} against an in-memory record (field name to value) — the
 * counterpart of the SQL compiler for records that are not (or not yet) rows, e.g. a create's
 * post-image or app code checking a loaded aggregate. {@code via} needs storage, so it is delegated
 * to a {@link ViaCheck}.
 *
 * <p>Semantics match the SQL form (three-valued logic, see {@link #matches}); {@code in} over an
 * empty collection is false and enums compare by their stored UUID.
 */
public final class RecordScopeEvaluator {

    /** Whether the record {@code id} of {@code target} satisfies {@code scope} for the subject. */
    @FunctionalInterface
    public interface ViaCheck {
        boolean matches(ScopedEntity target, UUID id, RecordScope scope);
    }

    private final RecordPolicies policies;

    public RecordScopeEvaluator(RecordPolicies policies) {
        this.policies = policies;
    }

    /**
     * Whether {@code values} (keyed by field name, e.g. {@code "owner"}) satisfies {@code scope} on
     * {@code entity} for {@code subject}. An inherited {@code via} resolves the target's read scope
     * for the same subject and hands it to {@code via}.
     *
     * <p>Evaluation uses SQL's three-valued logic so both forms agree: a comparison involving a
     * {@code null} (a null column, or a subject placeholder the subject cannot supply) is
     * <em>unknown</em>, {@code not(unknown)} stays unknown, and unknown is finally treated as false.
     */
    public boolean matches(ScopedEntity entity, RecordScope scope, Map<String, ?> values,
                           AccessSubject subject, ViaCheck via) {
        return eval(entity, scope, values, subject, via) == Tri.TRUE;
    }

    private enum Tri {
        TRUE, FALSE, UNKNOWN;

        static Tri of(boolean b) {
            return b ? TRUE : FALSE;
        }

        Tri not() {
            return this == TRUE ? FALSE : this == FALSE ? TRUE : UNKNOWN;
        }
    }

    private Tri eval(ScopedEntity entity, RecordScope scope, Map<String, ?> values,
                     AccessSubject subject, ViaCheck via) {
        return switch (scope) {
            case RecordScope.All a -> Tri.TRUE;
            case RecordScope.None n -> Tri.FALSE;
            case RecordScope.Eq eq -> {
                ScopedEntity.Field f = entity.field(eq.field());
                if (f == null) yield Tri.FALSE;
                Object expected = policies.coerce(f, resolve(eq.value(), subject));
                Object actual = policies.coerce(f, blankToNull(values.get(eq.field())));
                if (expected == RecordPolicies.UNMATCHABLE) yield Tri.FALSE;
                if (expected == null || actual == null) yield Tri.UNKNOWN;
                yield Tri.of(same(expected, actual));
            }
            case RecordScope.In in -> {
                ScopedEntity.Field f = entity.field(in.field());
                if (f == null) yield Tri.FALSE;
                Object raw = resolve(in.values(), subject);
                if (raw == null) yield Tri.UNKNOWN;
                List<Object> expected = policies.coerceAll(f, raw);
                if (expected.isEmpty()) yield Tri.FALSE;
                Object actual = policies.coerce(f, blankToNull(values.get(in.field())));
                if (actual == null) yield Tri.UNKNOWN;
                yield Tri.of(expected.stream().anyMatch(e -> same(e, actual)));
            }
            case RecordScope.IsNull n -> Tri.of(blankToNull(values.get(n.field())) == null);
            case RecordScope.Via v -> {
                ScopedEntity.Field f = entity.field(v.field());
                if (f == null || !f.isRef()) yield Tri.FALSE;
                Object id = policies.coerce(f, blankToNull(values.get(v.field())));
                if (!(id instanceof UUID uuid)) yield Tri.FALSE;
                ScopedEntity target = policies.entityByLogicalName(f.refTarget());
                if (target == null) yield Tri.FALSE;
                RecordScope targetScope = v.target() != null
                        ? v.target()
                        : policies.scopeFor(target.type(), subject, AccessMode.READ);
                yield Tri.of(via.matches(target, uuid, targetScope));
            }
            case RecordScope.And and -> {
                Tri result = Tri.TRUE;
                for (RecordScope p : and.parts()) {
                    Tri t = eval(entity, p, values, subject, via);
                    if (t == Tri.FALSE) yield Tri.FALSE;
                    if (t == Tri.UNKNOWN) result = Tri.UNKNOWN;
                }
                yield result;
            }
            case RecordScope.Or or -> {
                Tri result = Tri.FALSE;
                for (RecordScope p : or.parts()) {
                    Tri t = eval(entity, p, values, subject, via);
                    if (t == Tri.TRUE) yield Tri.TRUE;
                    if (t == Tri.UNKNOWN) result = Tri.UNKNOWN;
                }
                yield result;
            }
            case RecordScope.Not not -> eval(entity, not.part(), values, subject, via).not();
        };
    }

    private static Object blankToNull(Object value) {
        return "".equals(value) ? null : value;
    }

    private static Object resolve(Object value, AccessSubject subject) {
        return value instanceof Subject.Value sv ? sv.resolve(subject) : value;
    }

    private static boolean same(Object a, Object b) {
        if (a == null || b == null || a == RecordPolicies.UNMATCHABLE || b == RecordPolicies.UNMATCHABLE) {
            return false;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a.getClass() != b.getClass()) {
            return Objects.equals(a.toString(), b.toString());
        }
        return a.equals(b);
    }
}
