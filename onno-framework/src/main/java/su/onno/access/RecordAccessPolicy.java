package su.onno.access;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A record-level access policy: which records of one entity the holders of some roles may read
 * and write. Declare one as a Spring bean; the framework pushes the scope into every generic
 * read (lists, get, groups, aggregates, widgets, ref options, search, MCP, SSE) and write
 * (create, update, delete, post, actions, import) made on a user's behalf.
 *
 * <pre>{@code
 * @Bean
 * RecordAccessPolicy tenantsByOwner() {
 *     return RecordAccessPolicy.forCatalog(Tenant.class)
 *             .appliesTo("CUSTOMER")
 *             .read(RecordScope.eq("owner", Subject.recordId()))
 *             .write(RecordScope.eq("owner", Subject.recordId()))   // defaults to the read scope
 *             .defaults(d -> d.set("owner", Subject.recordId()));   // filled on create
 * }
 * }</pre>
 *
 * <p>Applicability is deliberately simple and auditable:
 * <ol>
 *   <li>A policy <em>applies</em> to a subject holding at least one of its {@link #appliesTo} roles.</li>
 *   <li>A subject holding {@code ADMIN} or one of the policy's {@link #exemptRoles} is exempt, and
 *       exemption wins over applicability.</li>
 *   <li>Scopes of several applicable policies for the same entity are OR'ed.</li>
 *   <li>With no applicable policy the entity is unscoped; entity-level {@code @AccessControl}
 *       stays the only gate.</li>
 *   <li>A policy never widens access: the entity-level read/write grant is checked first.</li>
 * </ol>
 *
 * <p>Instances are immutable; every fluent method returns a copy.
 */
public final class RecordAccessPolicy {

    /** What kind of entity a policy was declared for, checked against the metadata at startup. */
    public enum Kind { CATALOG, DOCUMENT, REGISTER, ANY }

    private final Class<?> entityType;
    private final Kind kind;
    private final Set<String> appliesTo;
    private final Set<String> exemptRoles;
    private final RecordScope read;
    private final RecordScope write;
    private final Map<String, Object> defaults;

    private RecordAccessPolicy(Class<?> entityType, Kind kind, Set<String> appliesTo, Set<String> exemptRoles,
                               RecordScope read, RecordScope write, Map<String, Object> defaults) {
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.kind = kind;
        this.appliesTo = appliesTo;
        this.exemptRoles = exemptRoles;
        this.read = read;
        this.write = write;
        this.defaults = defaults;
    }

    /** A policy for a {@code @Catalog}. */
    public static RecordAccessPolicy forCatalog(Class<?> catalog) {
        return new RecordAccessPolicy(catalog, Kind.CATALOG, Set.of(), Set.of(), null, null, Map.of());
    }

    /** A policy for a {@code @Document}; its tabular sections inherit the document's scope. */
    public static RecordAccessPolicy forDocument(Class<?> document) {
        return new RecordAccessPolicy(document, Kind.DOCUMENT, Set.of(), Set.of(), null, null, Map.of());
    }

    /**
     * A policy for an accumulation or information register. Scopes may only use dimension fields
     * (directly or through {@link RecordScope#via(String)}), which exist in both the movement and
     * totals tables so balance reads stay correct.
     */
    public static RecordAccessPolicy forRegister(Class<?> register) {
        return new RecordAccessPolicy(register, Kind.REGISTER, Set.of(), Set.of(), null, null, Map.of());
    }

    /** A policy for any catalog, document or register; the kind is taken from the metadata. */
    public static RecordAccessPolicy of(Class<?> entity) {
        return new RecordAccessPolicy(entity, Kind.ANY, Set.of(), Set.of(), null, null, Map.of());
    }

    /** The roles this policy applies to; a subject needs at least one. */
    public RecordAccessPolicy appliesTo(String... roles) {
        return new RecordAccessPolicy(entityType, kind, roleSet(roles), exemptRoles, read, write, defaults);
    }

    /** Roles exempt from this policy (in addition to {@code ADMIN}); exemption wins over applicability. */
    public RecordAccessPolicy exemptRoles(String... roles) {
        return new RecordAccessPolicy(entityType, kind, appliesTo, roleSet(roles), read, write, defaults);
    }

    /** The scope of records the subject may read. Required. */
    public RecordAccessPolicy read(RecordScope scope) {
        return new RecordAccessPolicy(entityType, kind, appliesTo, exemptRoles,
                Objects.requireNonNull(scope, "read scope"), write, defaults);
    }

    /**
     * The scope of records the subject may change (update, delete, post, run actions on) and the
     * scope a created or updated record must still satisfy afterwards. Defaults to the read scope;
     * use {@link RecordScope#none()} to allow changes only through trusted code (e.g. actions).
     */
    public RecordAccessPolicy write(RecordScope scope) {
        return new RecordAccessPolicy(entityType, kind, appliesTo, exemptRoles, read,
                Objects.requireNonNull(scope, "write scope"), defaults);
    }

    /** Values filled into a record created by a subject this policy applies to. */
    public RecordAccessPolicy defaults(Consumer<Defaults> spec) {
        Defaults d = new Defaults(new LinkedHashMap<>(defaults));
        spec.accept(d);
        return new RecordAccessPolicy(entityType, kind, appliesTo, exemptRoles, read, write,
                Collections.unmodifiableMap(d.values));
    }

    public Class<?> entityType() {
        return entityType;
    }

    public Kind kind() {
        return kind;
    }

    public Set<String> appliesToRoles() {
        return appliesTo;
    }

    public Set<String> exemptRoleSet() {
        return exemptRoles;
    }

    /** The read scope, or {@code null} when it was never declared (rejected at startup). */
    public RecordScope readScope() {
        return read;
    }

    /** The effective write scope: the declared one, else the read scope. */
    public RecordScope writeScope() {
        return write != null ? write : read;
    }

    /** The effective scope for {@code mode}. */
    public RecordScope scope(AccessMode mode) {
        return mode == AccessMode.WRITE ? writeScope() : readScope();
    }

    /** Field name to literal or {@link Subject.Value}, in declaration order. */
    public Map<String, Object> defaultValues() {
        return defaults;
    }

    /** Whether the subject holds one of {@link #appliesTo} roles. */
    public boolean appliesTo(AccessSubject subject) {
        if (!(subject instanceof AccessSubject.User user)) return false;
        for (String role : appliesTo) {
            if (user.roles().contains(role)) return true;
        }
        return false;
    }

    /** Whether the subject is exempt from this policy ({@code ADMIN}, an exempt role, or system code). */
    public boolean isExempt(AccessSubject subject) {
        if (subject.superuser()) return true;
        for (String role : exemptRoles) {
            if (subject.roles().contains(role)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "RecordAccessPolicy[" + entityType.getSimpleName() + " appliesTo=" + appliesTo
                + (exemptRoles.isEmpty() ? "" : " exempt=" + exemptRoles)
                + " read=" + read + " write=" + writeScope() + "]";
    }

    private static Set<String> roleSet(String... roles) {
        Set<String> set = new LinkedHashSet<>();
        if (roles != null) {
            for (String role : roles) {
                String n = AccessSubject.normalizeRole(role);
                if (!n.isEmpty()) set.add(n);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /** Values filled into a newly created record; see {@link #defaults(Consumer)}. */
    public static final class Defaults {
        private final Map<String, Object> values;

        private Defaults(Map<String, Object> values) {
            this.values = values;
        }

        /** Fill {@code field} with a literal or a {@link Subject} placeholder on create. */
        public Defaults set(String field, Object value) {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("Default field must not be blank");
            }
            values.put(field, Objects.requireNonNull(value, "default value"));
            return this;
        }
    }
}
