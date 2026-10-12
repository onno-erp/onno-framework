package su.onno.ui;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
import su.onno.access.ScopedEntity;
import su.onno.access.Subject;
import su.onno.metadata.MetadataRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles the {@link RecordScope} that applies to an {@link AccessSubject} into a
 * {@link ScopeClause} — the one place record policies turn into SQL. Every query and command
 * service ANDs the clause into its {@code WHERE}, so the scope composes with keyset paging,
 * counts, groups and aggregates.
 *
 * <ul>
 *   <li>Columns are qualified with the caller's table name or alias, so a clause is safe inside
 *       correlated subqueries.</li>
 *   <li>Every value binds as a parameter; collections expand to one parameter per element.
 *       Parameter names and subquery aliases derive deterministically from the qualifier, so the
 *       SQL text is stable across requests (statement caches stay warm) and two clauses in one
 *       query never collide as long as their qualifiers differ.</li>
 *   <li>{@code via(ref)} compiles to {@code EXISTS (SELECT 1 FROM target t WHERE t._id = e.ref AND
 *       <target scope>)}; an inherited target scope is the target's own read scope for the same
 *       subject (cycles are rejected at startup by {@link RecordPolicies}).</li>
 *   <li>A subject placeholder the subject cannot supply compiles to SQL {@code NULL}, which
 *       three-valued logic treats as false — also under {@code NOT}. A scope fails closed.</li>
 * </ul>
 */
public final class RecordScopeCompiler {

    /** A boolean SQL expression that is neither true nor false: {@code NOT} keeps it unknown. */
    private static final String UNKNOWN = "(1 = CAST(NULL AS INTEGER))";

    private final RecordPolicies policies;

    public RecordScopeCompiler(RecordPolicies policies) {
        this.policies = policies;
    }

    /** A compiler with no policies — every subject is unscoped. */
    public static RecordScopeCompiler unrestricted(MetadataRegistry registry) {
        return new RecordScopeCompiler(RecordPolicies.none(registry));
    }

    public RecordPolicies policies() {
        return policies;
    }

    /** Whether {@code subject} is restricted on {@code type} in {@code mode}. */
    public boolean isScoped(Class<?> type, AccessSubject subject, AccessMode mode) {
        return policies.isScoped(type, subject, mode);
    }

    /**
     * The clause restricting {@code type}'s main table, qualified by {@code qualifier} (the table
     * name in an un-aliased query, or the alias).
     */
    public ScopeClause clause(Class<?> type, AccessSubject subject, AccessMode mode, String qualifier) {
        RecordScope scope = policies.scopeFor(type, subject, mode);
        if (scope instanceof RecordScope.All) return ScopeClause.ALL;
        ScopedEntity entity = policies.entity(type);
        if (entity == null) return ScopeClause.ALL;
        return compile(entity, scope, subject, qualifier);
    }

    /** As {@link #clause(Class, AccessSubject, AccessMode, String)} for a READ, qualified by the main table. */
    public ScopeClause read(Class<?> type, AccessSubject subject) {
        ScopedEntity entity = policies.entity(type);
        return entity == null ? ScopeClause.ALL : clause(type, subject, AccessMode.READ, entity.tableName());
    }

    /** Compile an explicit {@code scope} over {@code entity}, qualified by {@code qualifier}. */
    public ScopeClause compile(ScopedEntity entity, RecordScope scope, AccessSubject subject, String qualifier) {
        if (scope instanceof RecordScope.All) return ScopeClause.ALL;
        Naming naming = new Naming(qualifier);
        Map<String, Object> bindings = new LinkedHashMap<>();
        String sql = sql(entity, scope, subject, qualifier, naming, bindings);
        return new ScopeClause(sql, bindings);
    }

    /** Deterministic, per-compile parameter and alias names rooted at the qualifier. */
    private static final class Naming {
        private final String prefix;
        private int next;

        Naming(String qualifier) {
            String base = qualifier == null || qualifier.isEmpty() ? "x" : qualifier;
            this.prefix = "rs_" + base.replaceAll("[^A-Za-z0-9_]", "_") + "_";
        }

        String param() {
            return prefix + (next++);
        }

        String alias() {
            return "_" + prefix + "a" + (next++);
        }
    }

    private String sql(ScopedEntity e, RecordScope scope, AccessSubject subject, String q,
                       Naming naming, Map<String, Object> bindings) {
        return switch (scope) {
            case RecordScope.All a -> "1=1";
            case RecordScope.None n -> "1=0";
            case RecordScope.Eq eq -> {
                ScopedEntity.Field f = e.field(eq.field());
                if (f == null) yield "1=0";
                Object value = policies.coerce(f, resolve(eq.value(), subject));
                if (value == null) yield UNKNOWN;
                if (value == RecordPolicies.UNMATCHABLE) yield "1=0";
                yield column(q, f) + " = :" + bind(naming, bindings, value);
            }
            case RecordScope.In in -> {
                ScopedEntity.Field f = e.field(in.field());
                if (f == null) yield "1=0";
                Object raw = resolve(in.values(), subject);
                if (raw == null) yield UNKNOWN;
                List<Object> values = policies.coerceAll(f, raw);
                if (values.isEmpty()) yield "1=0";
                StringBuilder sb = new StringBuilder(column(q, f)).append(" IN (");
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(':').append(bind(naming, bindings, values.get(i)));
                }
                yield sb.append(')').toString();
            }
            case RecordScope.IsNull n -> {
                ScopedEntity.Field f = e.field(n.field());
                yield f == null ? "1=0" : column(q, f) + " IS NULL";
            }
            case RecordScope.Via via -> {
                ScopedEntity.Field f = e.field(via.field());
                if (f == null || !f.isRef()) yield "1=0";
                ScopedEntity target = policies.entityByLogicalName(f.refTarget());
                if (target == null) yield "1=0";
                RecordScope targetScope = via.target() != null
                        ? via.target()
                        : policies.scopeFor(target.type(), subject, AccessMode.READ);
                String alias = naming.alias();
                String inner = targetScope instanceof RecordScope.All
                        ? ""
                        : " AND (" + sql(target, targetScope, subject, alias, naming, bindings) + ")";
                yield "EXISTS (SELECT 1 FROM " + target.tableName() + " " + alias
                        + " WHERE " + alias + "._id = " + column(q, f) + inner + ")";
            }
            case RecordScope.And and -> join(e, and.parts(), " AND ", subject, q, naming, bindings);
            case RecordScope.Or or -> join(e, or.parts(), " OR ", subject, q, naming, bindings);
            case RecordScope.Not not -> "NOT (" + sql(e, not.part(), subject, q, naming, bindings) + ")";
        };
    }

    private String join(ScopedEntity e, List<RecordScope> parts, String op, AccessSubject subject, String q,
                        Naming naming, Map<String, Object> bindings) {
        if (parts.isEmpty()) return " AND ".equals(op) ? "1=1" : "1=0";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(op);
            sb.append('(').append(sql(e, parts.get(i), subject, q, naming, bindings)).append(')');
        }
        return sb.append(')').toString();
    }

    private static String column(String qualifier, ScopedEntity.Field f) {
        return qualifier == null || qualifier.isEmpty() ? f.column() : qualifier + "." + f.column();
    }

    private static String bind(Naming naming, Map<String, Object> bindings, Object value) {
        String name = naming.param();
        bindings.put(name, value);
        return name;
    }

    private static Object resolve(Object value, AccessSubject subject) {
        return value instanceof Subject.Value sv ? sv.resolve(subject) : value;
    }
}
