package su.onno.ui;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
import su.onno.access.RecordScopeEvaluator;
import su.onno.access.ScopedEntity;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Record-level access checks for application code — custom controllers, page actions, widget
 * backends and {@code @McpTool}s that serve external users. Applies the same rules as the generic
 * surfaces: the entity-level grant ({@code @AccessControl} + {@link UiEntityAccessPolicy}) first,
 * then the subject's {@link su.onno.access.RecordAccessPolicy} scope.
 *
 * <pre>{@code
 * recordAccess.require(subject, Tenant.class, id, AccessMode.READ);   // 404 if not
 * boolean ok = recordAccess.can(subject, Tenant.class, id, AccessMode.WRITE);
 * ScopeClause scope = recordAccess.clause(subject, Tenant.class, AccessMode.READ, "t"); // for own SQL
 * }</pre>
 *
 * <p>The typed repositories are trusted and <em>not</em> scoped; code that reads them on a user's
 * behalf must check through this API.
 */
public class RecordAccess {

    private static final Logger log = LoggerFactory.getLogger(RecordAccess.class);

    private final RecordScopeCompiler compiler;
    private final UiAccessService access;
    private final Jdbi jdbi;
    private final RecordScopeEvaluator evaluator;
    private final boolean logDenials;

    public RecordAccess(RecordScopeCompiler compiler, UiAccessService access, Jdbi jdbi, boolean logDenials) {
        this.compiler = compiler;
        this.access = access;
        this.jdbi = jdbi;
        this.evaluator = new RecordScopeEvaluator(compiler.policies());
        this.logDenials = logDenials;
    }

    public RecordPolicies policies() {
        return compiler.policies();
    }

    public RecordScopeCompiler compiler() {
        return compiler;
    }

    /** The effective scope of {@code type} for {@code subject} ({@link RecordScope#all()} when unscoped). */
    public RecordScope scopeFor(AccessSubject subject, Class<?> type, AccessMode mode) {
        return compiler.policies().scopeFor(type, subject, mode);
    }

    /** The compiled scope of {@code type} for {@code subject}, qualified by {@code qualifier}. */
    public ScopeClause clause(AccessSubject subject, Class<?> type, AccessMode mode, String qualifier) {
        return compiler.clause(type, subject, mode, qualifier);
    }

    /** Whether {@code subject} may access record {@code id} of {@code type} in {@code mode}. */
    public boolean can(AccessSubject subject, Class<?> type, UUID id, AccessMode mode) {
        return id != null && !filter(subject, type, List.of(id), mode).isEmpty();
    }

    /** As {@link #can} but throws 404 (indistinguishable from a missing record) when not. */
    public void require(AccessSubject subject, Class<?> type, UUID id, AccessMode mode) {
        if (!can(subject, type, id, mode)) {
            throw notFound(subject, type, id, mode);
        }
    }

    /**
     * The subset of {@code ids} that {@code subject} may access in {@code mode} (one query). Ids that
     * don't exist are dropped too.
     */
    public Set<UUID> filter(AccessSubject subject, Class<?> type, Collection<UUID> ids, AccessMode mode) {
        ScopedEntity entity = requireEntity(type);
        if (ids == null || ids.isEmpty() || !entityGrant(subject, entity, mode)) return Set.of();
        ScopeClause clause = compiler.clause(type, subject, mode, entity.tableName());
        List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        return jdbi.withHandle(h -> clause.bind(h.createQuery(
                        "SELECT _id FROM " + entity.tableName() + " WHERE _id IN (<ids>)" + clause.and()))
                .bindList("ids", distinct)
                .mapTo(UUID.class)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }

    /**
     * Whether an in-memory record (field name to value) would be in {@code subject}'s scope — e.g. a
     * record about to be created. Entity-level grants are not checked here.
     */
    public boolean matches(AccessSubject subject, Class<?> type, Map<String, ?> values, AccessMode mode) {
        ScopedEntity entity = requireEntity(type);
        RecordScope scope = compiler.policies().scopeFor(type, subject, mode);
        return evaluator.matches(entity, scope, values, subject, (target, id, targetScope) -> {
            ScopeClause clause = compiler.compile(target, targetScope, subject, target.tableName());
            return jdbi.withHandle(h -> clause.bind(h.createQuery(
                            "SELECT COUNT(*) FROM " + target.tableName() + " WHERE _id = :_rid" + clause.and()))
                    .bind("_rid", id)
                    .mapTo(Long.class).one() > 0);
        });
    }

    /**
     * Whether a UI route to one record — {@code catalogs|documents/{name}/{id}} (leading slash and
     * trailing segments allowed) — is readable by a record-scoped {@code subject}. Anything that is
     * not such a route (a page, a list, a non-UUID id), an unknown entity, or an unscoped subject
     * yields {@code true}: this narrows by record scope only; entity grants are checked elsewhere.
     */
    public boolean canReadRoute(AccessSubject subject, String route) {
        if (route == null || compiler.policies().isEmpty()) return true;
        String[] parts = java.util.Arrays.stream(route.split("[/?#]"))
                .filter(p -> !p.isBlank()).toArray(String[]::new);
        if (parts.length < 3) return true;
        return canReadRecord(subject, parts[0], parts[1], parts[2]);
    }

    /** As {@link #canReadRoute} for an already split {@code kind}/{@code name}/{@code id}. */
    public boolean canReadRecord(AccessSubject subject, String kind, String name, String id) {
        if (subject == null || compiler.policies().isEmpty()) return true;
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException notARecord) {
            return true;
        }
        Class<?> type = entityClass(kind, name);
        if (type == null || !compiler.isScoped(type, subject, AccessMode.READ)) return true;
        return !filter(subject, type, List.of(uuid), AccessMode.READ).isEmpty();
    }

    private Class<?> entityClass(String kind, String name) {
        String normalized = name == null ? "" : java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replace(" ", "").replace("_", "").toLowerCase(java.util.Locale.ROOT);
        var registry = compiler.policies().registry();
        if ("catalogs".equals(kind)) {
            for (var d : registry.allCatalogs()) {
                if (d.logicalName().replace(" ", "").replace("_", "").toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                    return d.javaClass();
                }
            }
        } else if ("documents".equals(kind)) {
            for (var d : registry.allDocuments()) {
                if (d.logicalName().replace(" ", "").replace("_", "").toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                    return d.javaClass();
                }
            }
        }
        return null;
    }

    /** Entity-level grant (role + {@link UiEntityAccessPolicy}) for {@code mode}. */
    boolean entityGrant(AccessSubject subject, ScopedEntity entity, AccessMode mode) {
        String kind = kind(entity);
        return mode == AccessMode.WRITE
                ? access.canWrite(subject, kind, entity.logicalName())
                : access.canRead(subject, kind, entity.logicalName());
    }

    ResponseStatusException notFound(AccessSubject subject, Class<?> type, UUID id, AccessMode mode) {
        if (logDenials && log.isDebugEnabled()) {
            log.debug("Record access denied: subject={} entity={} id={} mode={}",
                    subject instanceof AccessSubject.User u ? u.username() : subject, type.getSimpleName(), id, mode);
        }
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /** Log a scope-based 404 decided elsewhere (query/command services), when enabled. */
    void logDenial(AccessSubject subject, String entity, Object id, AccessMode mode) {
        if (logDenials && log.isDebugEnabled()) {
            log.debug("Record access denied: subject={} entity={} id={} mode={}",
                    subject instanceof AccessSubject.User u ? u.username() : subject, entity, id, mode);
        }
    }

    private ScopedEntity requireEntity(Class<?> type) {
        ScopedEntity entity = compiler.policies().entity(type);
        if (entity == null) {
            throw new IllegalArgumentException(type.getName() + " is not a registered catalog, document or register");
        }
        return entity;
    }

    static String kind(ScopedEntity entity) {
        return switch (entity.kind()) {
            case CATALOG -> "catalog";
            case DOCUMENT -> "document";
            case ACCUMULATION_REGISTER -> "register";
            case INFORMATION_REGISTER -> "information register";
        };
    }
}
