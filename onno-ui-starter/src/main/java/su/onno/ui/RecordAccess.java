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
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
     * trailing segments allowed) — is readable by a record-scoped {@code subject}. Any other route
     * yields {@code true}: a page, a list (also one focused on a record) or a {@code new} draft has no
     * record id in its third segment. A record route follows {@link #canReadRecord}, with its
     * percent-encoded entity segment decoded here (a malformed one names no entity). This narrows by
     * record scope only; entity grants are checked elsewhere.
     */
    public boolean canReadRoute(AccessSubject subject, String route) {
        if (route == null) return true;
        String[] parts = java.util.Arrays.stream(route.split("[/?#]"))
                .filter(p -> !p.isBlank()).toArray(String[]::new);
        if (parts.length < 3) return true;
        if (!"catalogs".equals(parts[0]) && !"documents".equals(parts[0])) return true;
        if (parseUuid(parts[2]) == null) return true;
        String name;
        try {
            name = UriUtils.decode(parts[1], StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            name = null;
        }
        return canReadRecord(subject, parts[0], name, parts[2]);
    }

    /**
     * Whether {@code subject} may read record {@code id} of the catalog/document {@code kind}/{@code name}
     * ({@code name} is the decoded logical name; a former {@code previousNames} entry also resolves).
     * A {@code null} subject throws (as in {@link RecordPolicies#scopeFor}) and an id that is not a
     * UUID is denied — it addresses no record — whether or not policies are declared. A subject no
     * policy narrows (superuser, exempt, or not covered) is never narrowed here; for a subject some
     * policy narrows, an unknown entity is denied, since no policy can be ruled out for it. Entity
     * grants are not checked here: to authorize access in application code use {@link #can} or
     * {@link #require}.
     */
    public boolean canReadRecord(AccessSubject subject, String kind, String name, String id) {
        if (subject == null) {
            throw new IllegalArgumentException("AccessSubject is required; pass AccessSubject.system() for trusted code");
        }
        UUID uuid = parseUuid(id);
        if (uuid == null) return false;
        if (!narrowsReads(subject)) return true;
        Class<?> type = entityClass(kind, name);
        if (type == null) return false;
        if (!compiler.isScoped(type, subject, AccessMode.READ)) return true;
        return !filter(subject, type, List.of(uuid), AccessMode.READ).isEmpty();
    }

    /** Whether some policy narrows what {@code subject} reads (false with no policies or for a superuser). */
    private boolean narrowsReads(AccessSubject subject) {
        if (subject.superuser()) return false;
        for (Class<?> type : compiler.policies().all().keySet()) {
            if (compiler.isScoped(type, subject, AccessMode.READ)) return true;
        }
        return false;
    }

    private static UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** The catalog/document class named {@code name} under {@code kind}, by current then former name. */
    private Class<?> entityClass(String kind, String name) {
        if (name == null) return null;
        var registry = compiler.policies().registry();
        List<NamedEntity> candidates = switch (kind == null ? "" : kind) {
            case "catalogs" -> registry.allCatalogs().stream()
                    .map(d -> new NamedEntity(d.logicalName(), d.previousNames(), d.javaClass())).toList();
            case "documents" -> registry.allDocuments().stream()
                    .map(d -> new NamedEntity(d.logicalName(), d.previousNames(), d.javaClass())).toList();
            default -> List.of();
        };
        String key = nameKey(name);
        for (NamedEntity e : candidates) {
            if (nameKey(e.logicalName()).equals(key)) return e.type();
        }
        // A durable link (a task subject, a notification) may still carry the name before a rename.
        for (NamedEntity e : candidates) {
            for (String former : e.previousNames() == null ? List.<String>of() : e.previousNames()) {
                if (nameKey(former).equals(key)) return e.type();
            }
        }
        return null;
    }

    private record NamedEntity(String logicalName, List<String> previousNames, Class<?> type) {
    }

    private static String nameKey(String name) {
        return name.replace(" ", "").replace("_", "").toLowerCase(Locale.ROOT);
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
