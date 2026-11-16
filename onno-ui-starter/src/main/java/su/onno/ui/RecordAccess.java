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
     * Whether a UI route is readable by {@code subject} as far as record scope goes. A route that
     * addresses no record ({@link RecordRoute#parse} is empty: a page, a list, a {@code new} draft)
     * yields {@code true}; a record route follows {@link #canReadRecord(AccessSubject, RecordRoute)}.
     * Entity grants are checked elsewhere.
     */
    public boolean canReadRoute(AccessSubject subject, String route) {
        return RecordRoute.parse(route).map(r -> canReadRecord(subject, r)).orElse(true);
    }

    /**
     * Whether {@code subject} may read record {@code id} of the catalog/document {@code kind}/{@code name}
     * ({@code name} is the decoded logical name; a former {@code previousNames} entry also resolves).
     * An id that is not a UUID is denied — it addresses no record — whether or not policies are
     * declared. Otherwise as {@link #canReadRecord(AccessSubject, RecordRoute)}.
     */
    public boolean canReadRecord(AccessSubject subject, String kind, String name, String id) {
        requireSubject(subject);
        UUID uuid = RecordRoute.uuidOrNull(id);
        return uuid != null && canReadRecord(subject, new RecordRoute(kind, name, uuid));
    }

    /**
     * Whether {@code subject} may read the record {@code route} addresses. A {@code null} subject
     * throws (as in {@link RecordPolicies#scopeFor}) whether or not policies are declared. A subject no
     * policy narrows (superuser, exempt, or not covered) is never narrowed here; for a subject some
     * policy narrows, an unknown entity is denied, since no policy can be ruled out for it. Entity
     * grants are not checked for an unnarrowed subject: to authorize access in application code use
     * {@link #can} or {@link #require}.
     */
    public boolean canReadRecord(AccessSubject subject, RecordRoute route) {
        return readable(subject, List.of(route)).contains(route);
    }

    /**
     * The subset of {@code routes} {@code subject} may read, by the rule of
     * {@link #canReadRecord(AccessSubject, RecordRoute)} — with one query per narrowed entity rather
     * than per route, for a feed or snapshot of many links.
     */
    public Set<RecordRoute> readable(AccessSubject subject, Collection<RecordRoute> routes) {
        requireSubject(subject);
        if (routes == null || routes.isEmpty()) return Set.of();
        if (!narrowsReads(subject)) return new LinkedHashSet<>(routes);
        var registry = compiler.policies().registry();
        Map<Class<?>, List<RecordRoute>> byType = new java.util.LinkedHashMap<>();
        for (RecordRoute r : routes) {
            Class<?> type = EntityNames.recordClass(registry, r.kind(), r.name());
            if (type != null) byType.computeIfAbsent(type, t -> new ArrayList<>()).add(r);
        }
        Set<RecordRoute> out = new LinkedHashSet<>();
        byType.forEach((type, typed) -> {
            if (!compiler.isScoped(type, subject, AccessMode.READ)) {
                out.addAll(typed);
                return;
            }
            Set<UUID> ids = filter(subject, type, typed.stream().map(RecordRoute::id).toList(), AccessMode.READ);
            for (RecordRoute r : typed) {
                if (ids.contains(r.id())) out.add(r);
            }
        });
        return out;
    }

    /**
     * Whether some policy narrows what {@code subject} reads (false with no policies or for a
     * superuser) — when false, no record-scope check can change an answer for it.
     */
    public boolean narrowsReads(AccessSubject subject) {
        requireSubject(subject);
        if (subject.superuser()) return false;
        for (Class<?> type : compiler.policies().all().keySet()) {
            if (compiler.isScoped(type, subject, AccessMode.READ)) return true;
        }
        return false;
    }

    private static void requireSubject(AccessSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("AccessSubject is required; pass AccessSubject.system() for trusted code");
        }
    }

    /** Normalize notification targets with the same parser used by route authorization. */
    public static RecordRoute routeTarget(String link) {
        return RecordRoute.parse(link)
                .map(r -> new RecordRoute(r.kind(), normalizedRouteName(r.name()), r.id())).orElse(null);
    }

    private static String normalizedRouteName(String name) {
        return name.replace(" ", "").replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** SQL equivalent of readable(), applied before the notification page limit and badge count. */
    public ScopeClause notificationScope(AccessSubject subject) {
        requireSubject(subject);
        if (!narrowsReads(subject)) return ScopeClause.ALL;
        var registry = compiler.policies().registry();
        Map<Class<?>, List<String>> names = new java.util.LinkedHashMap<>();
        registry.allCatalogs().forEach(d -> {
            var aliases = new ArrayList<String>(); aliases.add(d.logicalName());
            if (d.previousNames() != null) aliases.addAll(d.previousNames());
            names.put(d.javaClass(), aliases);
        });
        registry.allDocuments().forEach(d -> {
            var aliases = new ArrayList<String>(); aliases.add(d.logicalName());
            if (d.previousNames() != null) aliases.addAll(d.previousNames());
            names.put(d.javaClass(), aliases);
        });
        List<String> visible = new ArrayList<>();
        visible.add("_target_kind IS NULL"); // External links and non-record routes carry no target.
        Map<String, Object> bindings = new java.util.LinkedHashMap<>();
        int index = 0;
        for (var entry : names.entrySet()) {
            Class<?> type = entry.getKey();
            ScopedEntity entity = compiler.policies().entity(type);
            String kind = entity.kind() == ScopedEntity.Kind.CATALOG ? "catalogs" : "documents";
            String prefix = "nt" + index++;
            String alias = "_" + prefix;
            List<String> matchingNames = new ArrayList<>();
            for (String name : entry.getValue()) {
                // Current names win over previousNames belonging to another entity.
                if (EntityNames.recordClass(registry, kind, name) != type) continue;
                String parameter = prefix + "name" + matchingNames.size();
                matchingNames.add("_target_name = :" + parameter);
                bindings.put(parameter, normalizedRouteName(name));
            }
            bindings.put(prefix + "kind", kind);
            String target = "_target_kind = :" + prefix + "kind AND (" + String.join(" OR ", matchingNames) + ")";
            if (compiler.isScoped(type, subject, AccessMode.READ)) {
                ScopeClause scope = compiler.clause(type, subject, AccessMode.READ, alias);
                String predicate = scope.sql();
                for (var bound : scope.bindings().entrySet()) {
                    predicate = predicate.replaceAll(":" + java.util.regex.Pattern.quote(bound.getKey()) + "(?![A-Za-z0-9_])",
                            java.util.regex.Matcher.quoteReplacement(":" + prefix + bound.getKey()));
                    bindings.put(prefix + bound.getKey(), bound.getValue());
                }
                target += " AND EXISTS (SELECT 1 FROM " + entity.tableName() + " " + alias
                        + " WHERE " + alias + "._id = _target_id"
                        + (predicate.isEmpty() ? "" : " AND (" + predicate + ")")
                        + (entityGrant(subject, entity, AccessMode.READ) ? "" : " AND 1 = 0") + ")";
            }
            visible.add("(" + target + ")");
        }
        // Unknown record entities remain hidden for narrowed subjects, matching readable().
        return new ScopeClause("(" + String.join(" OR ", visible) + ")", bindings);
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
