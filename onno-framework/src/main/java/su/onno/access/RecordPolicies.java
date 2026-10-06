package su.onno.access;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.EnumerationValueDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.types.PolyRef;
import su.onno.types.Ref;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The validated set of {@link RecordAccessPolicy} beans and the one place that decides which
 * {@link RecordScope} applies to a subject. Construction validates every policy against the
 * metadata and fails fast (unknown entity or field, a scope over a secret/polymorphic field,
 * {@code via} on a non-ref or through a cycle, a register scoped by a non-dimension, a dead policy
 * whose roles cannot read the entity at all, or a policy without a read scope).
 *
 * <p>Enforcement (the SQL compiler and the query/command services) lives in the UI starter; this
 * class is pure resolution and is safe to use from any module.
 */
public final class RecordPolicies {

    private final MetadataRegistry registry;
    private final Map<Class<?>, List<RecordAccessPolicy>> byEntity;
    private final Map<Class<?>, ScopedEntity> entities = new HashMap<>();
    private final Map<String, ScopedEntity> entitiesByLogicalName = new HashMap<>();

    /** No policies: every subject is unscoped. */
    public static RecordPolicies none(MetadataRegistry registry) {
        return new RecordPolicies(registry, List.of());
    }

    public RecordPolicies(MetadataRegistry registry, Collection<RecordAccessPolicy> policies) {
        this.registry = registry;
        for (CatalogDescriptor d : registry.allCatalogs()) index(ScopedEntity.of(d));
        for (DocumentDescriptor d : registry.allDocuments()) index(ScopedEntity.of(d));
        for (AccumulationRegisterDescriptor d : registry.allRegisters()) index(ScopedEntity.of(d));
        for (InformationRegisterDescriptor d : registry.allInformationRegisters()) index(ScopedEntity.of(d));

        Map<Class<?>, List<RecordAccessPolicy>> grouped = new LinkedHashMap<>();
        for (RecordAccessPolicy p : policies) {
            grouped.computeIfAbsent(p.entityType(), k -> new ArrayList<>()).add(p);
        }
        grouped.replaceAll((k, v) -> List.copyOf(v));
        this.byEntity = Collections.unmodifiableMap(grouped);
        validate();
    }

    private void index(ScopedEntity e) {
        entities.put(e.type(), e);
        entitiesByLogicalName.putIfAbsent(e.logicalName(), e);
    }

    public MetadataRegistry registry() {
        return registry;
    }

    /** Whether no policy is declared at all. */
    public boolean isEmpty() {
        return byEntity.isEmpty();
    }

    /** Every declared policy, grouped by entity. */
    public Map<Class<?>, List<RecordAccessPolicy>> all() {
        return byEntity;
    }

    /** The declared policies for {@code type} (empty when none). */
    public List<RecordAccessPolicy> policiesFor(Class<?> type) {
        return byEntity.getOrDefault(type, List.of());
    }

    /** The scope-able shape of a registered catalog/document/register, or {@code null}. */
    public ScopedEntity entity(Class<?> type) {
        return entities.get(type);
    }

    /** The scope-able shape of a catalog or document by its logical name (ref targets), or {@code null}. */
    public ScopedEntity entityByLogicalName(String logicalName) {
        return entitiesByLogicalName.get(logicalName);
    }

    /**
     * The effective scope for {@code subject} on {@code type}: {@link RecordScope#all()} when the
     * subject is exempt, system, or no policy applies; otherwise the OR of every applicable policy's
     * scope for {@code mode}.
     */
    public RecordScope scopeFor(Class<?> type, AccessSubject subject, AccessMode mode) {
        if (subject == null) {
            throw new IllegalArgumentException("AccessSubject is required; pass AccessSubject.system() for trusted code");
        }
        if (subject.superuser()) return RecordScope.all();
        List<RecordAccessPolicy> policies = byEntity.get(type);
        if (policies == null) return RecordScope.all();
        List<RecordScope> scopes = new ArrayList<>();
        for (RecordAccessPolicy p : policies) {
            if (!p.appliesTo(subject)) continue;
            if (p.isExempt(subject)) return RecordScope.all();
            scopes.add(p.scope(mode));
        }
        return scopes.isEmpty() ? RecordScope.all() : RecordScope.anyOf(scopes);
    }

    /** Whether {@code subject} sees a restricted subset of {@code type} in {@code mode}. */
    public boolean isScoped(Class<?> type, AccessSubject subject, AccessMode mode) {
        return !(scopeFor(type, subject, mode) instanceof RecordScope.All);
    }

    /** Whether any policy restricts {@code subject} anywhere — an "external user". */
    public boolean restricts(AccessSubject subject) {
        if (subject.superuser()) return false;
        for (Class<?> type : byEntity.keySet()) {
            if (isScoped(type, subject, AccessMode.READ) || isScoped(type, subject, AccessMode.WRITE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The field values every applicable, non-exempt policy fills into a record {@code subject}
     * creates, with {@link Subject} placeholders resolved. A placeholder the subject cannot supply
     * is omitted (the write scope then rejects the record, failing closed).
     */
    public Map<String, Object> defaultsFor(Class<?> type, AccessSubject subject) {
        if (subject.superuser()) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        for (RecordAccessPolicy p : policiesFor(type)) {
            if (!p.appliesTo(subject) || p.isExempt(subject)) continue;
            p.defaultValues().forEach((field, raw) -> {
                Object value = raw instanceof Subject.Value sv ? sv.resolve(subject) : raw;
                if (value != null) values.put(field, value);
            });
        }
        return values;
    }

    /**
     * A stable fingerprint of what restricts {@code subject}: empty for an unrestricted subject,
     * otherwise a short hash of its roles, identity record and attributes. Embedded in keyset
     * cursors so a cursor minted for one subject cannot be replayed by another.
     */
    public String fingerprint(AccessSubject subject) {
        if (!(subject instanceof AccessSubject.User user) || !restricts(subject)) return "";
        StringBuilder raw = new StringBuilder();
        raw.append(new TreeSet<>(user.roles())).append('|').append(user.recordId()).append('|');
        new TreeMap<>(user.attributes()).forEach((k, v) -> raw.append(k).append('=').append(v).append(';'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every field (per entity) some policy scopes by directly — candidates for an index. */
    public Map<ScopedEntity, Set<ScopedEntity.Field>> scopedFields() {
        Map<ScopedEntity, Set<ScopedEntity.Field>> out = new LinkedHashMap<>();
        for (List<RecordAccessPolicy> ps : byEntity.values()) {
            for (RecordAccessPolicy p : ps) {
                ScopedEntity e = entities.get(p.entityType());
                if (e == null) continue;
                collectFields(e, p.readScope(), out);
                collectFields(e, p.writeScope(), out);
            }
        }
        return out;
    }

    private void collectFields(ScopedEntity e, RecordScope scope, Map<ScopedEntity, Set<ScopedEntity.Field>> out) {
        if (scope == null) return;
        switch (scope) {
            case RecordScope.Eq eq -> add(out, e, e.field(eq.field()));
            case RecordScope.In in -> add(out, e, e.field(in.field()));
            case RecordScope.IsNull n -> add(out, e, e.field(n.field()));
            case RecordScope.Via via -> {
                ScopedEntity.Field f = e.field(via.field());
                add(out, e, f);
                if (via.target() != null && f != null) {
                    ScopedEntity t = entitiesByLogicalName.get(f.refTarget());
                    if (t != null) collectFields(t, via.target(), out);
                }
            }
            case RecordScope.And and -> and.parts().forEach(p -> collectFields(e, p, out));
            case RecordScope.Or or -> or.parts().forEach(p -> collectFields(e, p, out));
            case RecordScope.Not not -> collectFields(e, not.part(), out);
            case RecordScope.All a -> { }
            case RecordScope.None n -> { }
        }
    }

    private static void add(Map<ScopedEntity, Set<ScopedEntity.Field>> out, ScopedEntity e, ScopedEntity.Field f) {
        if (f != null && !"_id".equals(f.column())) {
            out.computeIfAbsent(e, k -> new LinkedHashSet<>()).add(f);
        }
    }

    // ---------------------------------------------------------------- value coercion

    /** Marker for a value that cannot match its column; the predicate compiles to false. */
    public static final Object UNMATCHABLE = new Object() {
        @Override
        public String toString() {
            return "<unmatchable>";
        }
    };

    /**
     * Coerce a resolved scope value (a literal, or what a {@link Subject} placeholder produced) to
     * the bindable form of {@code field}'s column: UUID columns take a {@link UUID} (from a UUID,
     * its string form, a {@link Ref}, a {@link PolyRef} or an enum constant); other columns take the
     * value as-is. Returns {@code null} for {@code null} and {@link #UNMATCHABLE} for a value that
     * can never equal the column (e.g. a malformed UUID string).
     */
    public Object coerce(ScopedEntity.Field field, Object value) {
        if (value == null) return null;
        if (field.uuidColumn()) {
            if (value instanceof UUID u) return u;
            if (value instanceof Ref<?> r) return r.id();
            if (value instanceof PolyRef p) return p.id();
            if (value instanceof Enum<?> e) {
                UUID id = enumId(e);
                return id == null ? UNMATCHABLE : id;
            }
            try {
                return UUID.fromString(value.toString());
            } catch (IllegalArgumentException malformed) {
                return UNMATCHABLE;
            }
        }
        if (value instanceof Enum<?> e) return e.name();
        return value;
    }

    /** Coerce each element of a collection-valued scope value; {@code null} elements are dropped. */
    public List<Object> coerceAll(ScopedEntity.Field field, Object value) {
        List<Object> out = new ArrayList<>();
        if (value == null) return out;
        Iterable<?> items = value instanceof Collection<?> c ? c
                : value instanceof Object[] arr ? List.of(arr) : List.of(value);
        for (Object item : items) {
            Object coerced = coerce(field, item);
            if (coerced != null && coerced != UNMATCHABLE) out.add(coerced);
        }
        return out;
    }

    private UUID enumId(Enum<?> constant) {
        EnumerationDescriptor e = registry.getEnumerationDescriptor(constant.getDeclaringClass());
        if (e == null) return null;
        for (EnumerationValueDescriptor v : e.values()) {
            if (v.name().equals(constant.name())) return v.id();
        }
        return null;
    }

    // ---------------------------------------------------------------- validation

    private void validate() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Class<?>, List<RecordAccessPolicy>> entry : byEntity.entrySet()) {
            Class<?> type = entry.getKey();
            ScopedEntity e = entities.get(type);
            for (RecordAccessPolicy p : entry.getValue()) {
                String where = "RecordAccessPolicy for " + type.getName();
                if (e == null) {
                    problems.add(where + ": not a registered catalog, document or register");
                    continue;
                }
                switch (p.kind()) {
                    case CATALOG -> {
                        if (e.kind() != ScopedEntity.Kind.CATALOG) problems.add(where + ": forCatalog on a " + e.kind());
                    }
                    case DOCUMENT -> {
                        if (e.kind() != ScopedEntity.Kind.DOCUMENT) problems.add(where + ": forDocument on a " + e.kind());
                    }
                    case REGISTER -> {
                        if (!e.isRegister()) problems.add(where + ": forRegister on a " + e.kind());
                    }
                    case ANY -> { }
                }
                if (p.appliesToRoles().isEmpty()) {
                    problems.add(where + ": appliesTo(...) names no role, so the policy applies to nobody");
                } else if (!grantsRead(e, p.appliesToRoles())) {
                    problems.add(where + ": none of appliesTo " + p.appliesToRoles()
                            + " may read " + e.logicalName() + " (entity readRoles " + e.readRoles()
                            + "), so the policy is dead");
                }
                if (p.readScope() == null) {
                    problems.add(where + ": read(...) scope is required");
                } else {
                    validateScope(e, p.readScope(), where + " read", problems);
                }
                if (p.writeScope() != null && p.writeScope() != p.readScope()) {
                    validateScope(e, p.writeScope(), where + " write", problems);
                }
                p.defaultValues().forEach((field, value) -> {
                    ScopedEntity.Field f = e.field(field);
                    if (f == null || "_id".equals(f.column()) || e.isRegister()) {
                        problems.add(where + ": default field '" + field + "' is not a writable attribute of "
                                + e.logicalName());
                    }
                });
            }
        }
        // via cycles, following inherited target policies
        for (Map.Entry<Class<?>, List<RecordAccessPolicy>> entry : byEntity.entrySet()) {
            ScopedEntity e = entities.get(entry.getKey());
            if (e == null) continue;
            for (RecordAccessPolicy p : entry.getValue()) {
                Deque<Class<?>> stack = new ArrayDeque<>();
                stack.push(e.type());
                detectCycle(e, p.readScope(), stack, problems);
                detectCycle(e, p.writeScope(), stack, problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid record access policies:\n  - "
                    + String.join("\n  - ", new LinkedHashSet<>(problems)));
        }
    }

    private static boolean grantsRead(ScopedEntity e, Set<String> roles) {
        for (String r : e.readRoles()) {
            if (roles.contains(AccessSubject.normalizeRole(r))) return true;
        }
        return false;
    }

    private void validateScope(ScopedEntity e, RecordScope scope, String where, List<String> problems) {
        switch (scope) {
            case RecordScope.Eq eq -> {
                ScopedEntity.Field f = requireField(e, eq.field(), where, problems);
                if (f != null) validateLiteral(f, eq.value(), where, problems);
            }
            case RecordScope.In in -> {
                ScopedEntity.Field f = requireField(e, in.field(), where, problems);
                if (f != null && !(in.values() instanceof Subject.Value)) {
                    if (in.values() instanceof Collection<?> c) {
                        for (Object v : c) validateLiteral(f, v, where, problems);
                    } else {
                        problems.add(where + ": in('" + in.field() + "', ...) needs a collection or a Subject placeholder");
                    }
                }
            }
            case RecordScope.IsNull n -> requireField(e, n.field(), where, problems);
            case RecordScope.Via via -> {
                ScopedEntity.Field f = requireField(e, via.field(), where, problems);
                if (f == null) return;
                if (!f.isRef()) {
                    problems.add(where + ": via('" + via.field() + "') on " + e.logicalName() + " is not a reference field");
                    return;
                }
                ScopedEntity target = entitiesByLogicalName.get(f.refTarget());
                if (target == null) {
                    problems.add(where + ": via('" + via.field() + "') targets unknown entity " + f.refTarget());
                } else if (via.target() != null) {
                    validateScope(target, via.target(), where + " via " + via.field(), problems);
                }
            }
            case RecordScope.And and -> and.parts().forEach(p -> validateScope(e, p, where, problems));
            case RecordScope.Or or -> or.parts().forEach(p -> validateScope(e, p, where, problems));
            case RecordScope.Not not -> validateScope(e, not.part(), where, problems);
            case RecordScope.All a -> { }
            case RecordScope.None n -> { }
        }
    }

    private ScopedEntity.Field requireField(ScopedEntity e, String name, String where, List<String> problems) {
        ScopedEntity.Field f = e.field(name);
        if (f == null) {
            problems.add(where + ": '" + name + "' is not a "
                    + (e.isRegister() ? "dimension" : "filterable field") + " of " + e.logicalName()
                    + " (secret and polymorphic-reference fields cannot be scoped)");
        }
        return f;
    }

    private void validateLiteral(ScopedEntity.Field f, Object value, String where, List<String> problems) {
        if (value instanceof Subject.Value) return;
        if (value instanceof Enum<?> en && f.javaType().isEnum() && !f.javaType().equals(en.getDeclaringClass())) {
            problems.add(where + ": '" + f.name() + "' is a " + f.javaType().getSimpleName()
                    + ", not a " + en.getDeclaringClass().getSimpleName());
            return;
        }
        if (coerce(f, value) == UNMATCHABLE) {
            problems.add(where + ": literal " + value + " can never match '" + f.name() + "'");
        }
    }

    private void detectCycle(ScopedEntity e, RecordScope scope, Deque<Class<?>> stack, List<String> problems) {
        if (scope == null) return;
        switch (scope) {
            case RecordScope.Via via -> {
                ScopedEntity.Field f = e.field(via.field());
                if (f == null || !f.isRef()) return;
                ScopedEntity target = entitiesByLogicalName.get(f.refTarget());
                if (target == null) return;
                if (stack.contains(target.type())) {
                    if (via.target() == null) {
                        problems.add("RecordAccessPolicy via cycle: " + path(stack) + " -> " + target.type().getSimpleName());
                    }
                    if (via.target() == null) return;
                }
                stack.push(target.type());
                if (via.target() != null) {
                    detectCycle(target, via.target(), stack, problems);
                } else {
                    for (RecordAccessPolicy tp : policiesFor(target.type())) {
                        detectCycle(target, tp.readScope(), stack, problems);
                    }
                }
                stack.pop();
            }
            case RecordScope.And and -> and.parts().forEach(p -> detectCycle(e, p, stack, problems));
            case RecordScope.Or or -> or.parts().forEach(p -> detectCycle(e, p, stack, problems));
            case RecordScope.Not not -> detectCycle(e, not.part(), stack, problems);
            default -> { }
        }
    }

    private static String path(Deque<Class<?>> stack) {
        List<String> names = new ArrayList<>();
        stack.descendingIterator().forEachRemaining(c -> names.add(c.getSimpleName()));
        return String.join(" -> ", names);
    }
}
