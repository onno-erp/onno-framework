package su.onno.ui;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.access.RecordPolicies;
import su.onno.access.ScopedEntity;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.ReferenceTargetDescriptor;
import su.onno.types.PolyRef;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The record-policy half of the generic write path, shared by {@link CatalogCommandService} and
 * {@link DocumentCommandService}:
 *
 * <ul>
 *   <li><b>existing records</b> (update, delete, post, unpost, actions) must be inside the
 *       subject's <em>write</em> scope — otherwise 404, exactly like a missing record;</li>
 *   <li><b>created/updated records</b> must still satisfy the write scope afterwards (the
 *       post-image is checked inside the write transaction, so a failing write rolls back) — a
 *       customer cannot create a record owned by someone else, nor move one out of their scope;</li>
 *   <li><b>policy defaults</b> are filled into a create;</li>
 *   <li><b>ref values</b> on write must point at records the subject may read — otherwise 422, so a
 *       record can't be attached to someone else's;</li>
 *   <li><b>restricted refs</b> (withheld from the subject on read) are preserved on update rather
 *       than cleared by the null the form echoes back.</li>
 * </ul>
 */
final class RecordWriteGuard {

    private final MetadataRegistry registry;
    private final RecordScopeCompiler scopes;
    private final UiAccessService access;
    private final Jdbi jdbi;

    RecordWriteGuard(MetadataRegistry registry, RecordScopeCompiler scopes, UiAccessService access, Jdbi jdbi) {
        this.registry = registry;
        this.scopes = scopes;
        this.access = access;
        this.jdbi = jdbi;
    }

    RecordPolicies policies() {
        return scopes.policies();
    }

    /** Fill the subject's policy defaults into a create body (keyed by field name); they win over the body. */
    void applyDefaults(Class<?> type, Map<String, Object> body, AccessSubject subject) {
        RecordPolicies policies = scopes.policies();
        ScopedEntity entity = policies.entity(type);
        if (entity == null) return;
        policies.defaultsFor(type, subject).forEach((field, value) -> {
            ScopedEntity.Field f = entity.field(field);
            Object coerced = f == null ? value : policies.coerce(f, value);
            if (coerced != null && coerced != RecordPolicies.UNMATCHABLE) {
                body.put(field, coerced);
            }
        });
    }

    /** 404 unless record {@code id} of {@code type} is inside the subject's scope for {@code mode}. */
    void requireInScope(Class<?> type, String table, UUID id, AccessSubject subject, AccessMode mode) {
        ScopeClause scope = scopes.clause(type, subject, mode, table);
        if (scope.isAll()) return;
        long n = jdbi.withHandle(h -> scope.bind(h.createQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE _id = :_rid" + scope.and()))
                .bind("_rid", id)
                .mapTo(Long.class).one());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /**
     * Inside the write transaction: the just-written record must satisfy the subject's write scope,
     * else 403 (and the caller's transaction rolls back).
     */
    void requirePostImage(Handle handle, Class<?> type, String table, UUID id, AccessSubject subject) {
        ScopeClause scope = scopes.clause(type, subject, AccessMode.WRITE, table);
        if (scope.isAll()) return;
        long n = scope.bind(handle.createQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE _id = :_rid" + scope.and()))
                .bind("_rid", id)
                .mapTo(Long.class).one();
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The record would be outside what you are allowed to change");
        }
    }

    /**
     * Every non-null reference in {@code body} (keyed by field name) must point at a record the
     * subject may read: inside the target's record scope, and — for a subject restricted by any
     * record policy — with an entity-level read grant on the target. 422 otherwise.
     */
    void requireReadableRefs(List<AttributeDescriptor> attributes, Map<String, Object> body, AccessSubject subject) {
        if (subject.superuser() || scopes.policies().isEmpty()) return;
        boolean restricted = scopes.policies().restricts(subject);
        for (AttributeDescriptor attr : attributes) {
            if (!attr.isRef() || !body.containsKey(attr.fieldName())) continue;
            Object value = body.get(attr.fieldName());
            if (value == null || "".equals(value)) continue;
            Target target;
            UUID id;
            try {
                if (attr.isPolymorphicRef()) {
                    PolyRef ref = polyRef(attr, value);
                    target = target(attr.refTargets().stream()
                            .filter(t -> t.javaTypeName().equals(ref.type().getName()))
                            .map(ReferenceTargetDescriptor::logicalName)
                            .findFirst().orElse(null));
                    id = ref.id();
                } else {
                    target = target(attr.refTarget());
                    id = value instanceof UUID u ? u : UUID.fromString(value.toString());
                }
            } catch (IllegalArgumentException malformed) {
                continue; // the regular coercion reports malformed values
            }
            if (target == null) continue;
            if (!readable(target, id, subject, restricted)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Unknown or inaccessible reference for " + attr.fieldName());
            }
        }
    }

    /**
     * On update, drop a submitted {@code null} for a reference whose stored target the subject may
     * not read — reads withhold such ids, so the form echoes {@code null} back; that must not clear
     * a link the subject can't even see. Also covers a catalog's {@code parent}.
     */
    void preserveRestrictedRefs(String table, List<AttributeDescriptor> attributes, UUID id,
                                Map<String, Object> body, AccessSubject subject, Class<?> parentType) {
        if (subject.superuser()) return;
        Map<String, Object> stored = null;
        for (AttributeDescriptor attr : attributes) {
            if (!attr.isRef() || !body.containsKey(attr.fieldName())) continue;
            Object value = body.get(attr.fieldName());
            if (value != null && !"".equals(value)) continue;
            if (stored == null) stored = loadRaw(table, id);
            if (stored == null) return;
            Object current = stored.get(attr.columnName());
            if (current == null) continue;
            Target target;
            UUID targetId;
            try {
                if (attr.isPolymorphicRef()) {
                    PolyRef ref = PolyRef.parse(current.toString());
                    target = target(attr.refTargets().stream()
                            .filter(t -> t.javaTypeName().equals(ref.type().getName()))
                            .map(ReferenceTargetDescriptor::logicalName)
                            .findFirst().orElse(null));
                    targetId = ref.id();
                } else {
                    target = target(attr.refTarget());
                    targetId = current instanceof UUID u ? u : UUID.fromString(current.toString());
                }
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            if (target != null && !readable(target, targetId, subject, true)) {
                body.remove(attr.fieldName());
            }
        }
        if (parentType != null && body.containsKey("parent")
                && (body.get("parent") == null || "".equals(body.get("parent")))) {
            if (stored == null) stored = loadRaw(table, id);
            Object current = stored == null ? null : stored.get("_parent");
            if (current != null) {
                UUID parentId = current instanceof UUID u ? u : UUID.fromString(current.toString());
                ScopeClause scope = scopes.clause(parentType, subject, AccessMode.READ, table);
                if (!scope.isAll() && !exists(table, parentId, scope)) {
                    body.remove("parent");
                }
            }
        }
    }

    private Map<String, Object> loadRaw(String table, UUID id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + table + " WHERE _id = :id")
                .bind("id", id).mapToMap().findOne().orElse(null));
    }

    private record Target(String kind, String logicalName, Class<?> type, String table) {}

    private Target target(String logicalName) {
        if (logicalName == null) return null;
        for (CatalogDescriptor c : registry.allCatalogs()) {
            if (c.logicalName().equals(logicalName)) {
                return new Target("catalog", c.logicalName(), c.javaClass(), c.tableName());
            }
        }
        for (DocumentDescriptor d : registry.allDocuments()) {
            if (d.logicalName().equals(logicalName)) {
                return new Target("document", d.logicalName(), d.javaClass(), d.tableName());
            }
        }
        return null;
    }

    private boolean readable(Target target, UUID id, AccessSubject subject, boolean checkGrant) {
        if (checkGrant && !access.canRead(subject, target.kind(), target.logicalName())) {
            return false;
        }
        ScopeClause scope = scopes.clause(target.type(), subject, AccessMode.READ, target.table());
        return scope.isAll() || exists(target.table(), id, scope);
    }

    private boolean exists(String table, UUID id, ScopeClause scope) {
        return jdbi.withHandle(h -> scope.bind(h.createQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE _id = :_rid" + scope.and()))
                .bind("_rid", id)
                .mapTo(Long.class).one()) > 0;
    }

    private static PolyRef polyRef(AttributeDescriptor attr, Object value) {
        if (value instanceof PolyRef ref) return ref;
        if (value instanceof Map<?, ?> map) {
            Object type = map.get("type");
            Object id = map.get("id");
            if (type == null || id == null) throw new IllegalArgumentException("incomplete");
            String javaType = attr.refTargets().stream()
                    .filter(t -> t.javaTypeName().equals(type.toString()) || t.logicalName().equals(type.toString()))
                    .map(ReferenceTargetDescriptor::javaTypeName)
                    .findFirst().orElse(type.toString());
            return PolyRef.parse(javaType + "|" + id);
        }
        return PolyRef.parse(value.toString());
    }
}
