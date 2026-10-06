package su.onno.ui.comments;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.RecordScopeCompiler;
import su.onno.ui.ScopeClause;
import su.onno.ui.UiAccessService;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the entity mentions in a comment thread to {@code {display, avatarUrl, readable}}, live
 * and per-viewer — the mention counterpart of {@link su.onno.ui.RefResolver}, batched the way
 * {@link CommentAuthorAvatars} batches author avatars. For each viewer:
 *
 * <ul>
 *   <li>a mention to an entity the viewer <em>can</em> read resolves to its current display (catalog
 *       description/code, document number) and avatar, so renames track automatically;</li>
 *   <li>a mention to an entity the viewer <em>can't</em> read (per the same per-entity read gate as
 *       everything else, {@link UiAccessService#canRead}) resolves as <em>not readable</em> with no
 *       display leaked — the client degrades it to plain text rather than a clickable 403;</li>
 *   <li>a mention to a record outside the viewer's record scope ({@code RecordAccessPolicy}) is
 *       equally not readable — indistinguishable from a deleted record, so mentions are no
 *       existence oracle.</li>
 * </ul>
 *
 * <p>Resolution groups by {@code (kind, name)} so a thread mentioning ten customers costs one query,
 * not ten. A mention to a deleted record (or one whose entity no longer exists) stays readable at the
 * entity level but carries no display, so the client falls back to the token's snapshot label.
 */
public class MentionResolver {

    private static final String AVATAR_COLUMN = "avatar_url";

    private final MetadataRegistry registry;
    private final UiAccessService access;
    private final Jdbi jdbi;
    private final RecordScopeCompiler scopes;

    public MentionResolver(MetadataRegistry registry, UiAccessService access, Jdbi jdbi) {
        this(registry, access, jdbi, RecordScopeCompiler.unrestricted(registry));
    }

    public MentionResolver(MetadataRegistry registry, UiAccessService access, Jdbi jdbi,
                           RecordScopeCompiler scopes) {
        this.registry = registry;
        this.access = access;
        this.jdbi = jdbi;
        this.scopes = scopes;
    }

    /** A mention resolved for one viewer: identity + (when readable) its live display and avatar. */
    public record ResolvedMention(String kind, String name, UUID id, String display,
                                  String avatarUrl, String entity, boolean readable) {

        public Map<String, Object> toJson() {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("id", id.toString());
            out.put("kind", kind);
            out.put("name", name);
            out.put("entity", entity);
            out.put("display", display);
            out.put("avatarUrl", avatarUrl);
            out.put("readable", readable);
            return out;
        }
    }

    /**
     * True when {@code viewer} may read the record a mention points at: the entity-level grant
     * (deny-by-default, ADMIN bypasses) and, for a record-scoped viewer, the record's scope.
     */
    public boolean canRead(AccessSubject viewer, MentionRef ref) {
        if (!access.canRead(viewer, ref.accessType(), ref.name())) {
            return false;
        }
        Class<?> type = "catalogs".equals(ref.kind())
                ? java.util.Optional.ofNullable(catalog(ref.name())).map(CatalogDescriptor::javaClass).orElse(null)
                : java.util.Optional.ofNullable(document(ref.name())).map(DocumentDescriptor::javaClass).orElse(null);
        if (type == null || !scopes.isScoped(type, viewer, AccessMode.READ)) {
            return true;
        }
        return resolve(List.of(ref), viewer).get(0).readable();
    }

    /**
     * Resolve every distinct mention in {@code refs} for {@code viewer}. Order follows {@code refs};
     * unreadable or unknown mentions are returned with {@code readable=false} and no display.
     */
    public List<ResolvedMention> resolve(Collection<MentionRef> refs, AccessSubject viewer) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        // Group the ids by their target entity so each catalog/document is queried once.
        Map<GroupKey, Set<UUID>> grouped = new java.util.LinkedHashMap<>();
        for (MentionRef ref : refs) {
            grouped.computeIfAbsent(new GroupKey(ref.kind(), ref.name()), k -> new LinkedHashSet<>()).add(ref.id());
        }

        Map<MentionRef, ResolvedMention> resolved = new HashMap<>();
        for (Map.Entry<GroupKey, Set<UUID>> entry : grouped.entrySet()) {
            GroupKey key = entry.getKey();
            resolveGroup(key, entry.getValue(), viewer, resolved);
        }

        // Re-emit in the caller's order (distinct), so a thread renders mentions deterministically.
        List<ResolvedMention> out = new ArrayList<>();
        Set<MentionRef> emitted = new LinkedHashSet<>();
        for (MentionRef ref : refs) {
            if (emitted.add(ref)) {
                out.add(resolved.getOrDefault(ref,
                        new ResolvedMention(ref.kind(), ref.name(), ref.id(), null, null, null, false)));
            }
        }
        return out;
    }

    private record GroupKey(String kind, String name) {
    }

    private void resolveGroup(GroupKey key, Set<UUID> ids, AccessSubject viewer,
                              Map<MentionRef, ResolvedMention> sink) {
        boolean readable = access.canRead(viewer, "catalogs".equals(key.kind()) ? "catalog" : "document", key.name());
        if (!readable) {
            for (UUID id : ids) {
                sink.put(new MentionRef(key.kind(), key.name(), id),
                        new ResolvedMention(key.kind(), key.name(), id, null, null, null, false));
            }
            return;
        }
        if ("catalogs".equals(key.kind())) {
            resolveCatalogGroup(key, ids, viewer, sink);
        } else {
            resolveDocumentGroup(key, ids, viewer, sink);
        }
    }

    private void resolveCatalogGroup(GroupKey key, Set<UUID> ids, AccessSubject viewer,
                                     Map<MentionRef, ResolvedMention> sink) {
        CatalogDescriptor desc = catalog(key.name());
        if (desc == null) {
            putUnresolved(key, ids, sink);
            return;
        }
        String avatarColumn = desc.attributes().stream()
                .map(AttributeDescriptor::columnName)
                .filter(c -> c.equalsIgnoreCase(AVATAR_COLUMN))
                .findFirst().orElse(null);
        ScopeClause scope = scopes.clause(desc.javaClass(), viewer, AccessMode.READ, desc.tableName());
        String sql = "SELECT _id, _description, _code"
                + (avatarColumn != null ? ", " + avatarColumn + " AS _avatar" : "")
                + " FROM " + desc.tableName() + " WHERE _id IN (<ids>) AND _deletion_mark = false" + scope.and();
        Map<UUID, Resolved> rows = jdbi.withHandle(h -> scope.bind(h.createQuery(sql))
                .bindList("ids", new ArrayList<>(ids))
                .reduceRows(new HashMap<UUID, Resolved>(), (map, rv) -> {
                    String description = rv.getColumn("_description", String.class);
                    String code = rv.getColumn("_code", String.class);
                    String display = description != null && !description.isBlank() ? description : code;
                    String avatar = avatarColumn != null ? rv.getColumn("_avatar", String.class) : null;
                    map.put(rv.getColumn("_id", UUID.class), new Resolved(display, avatar));
                    return map;
                }));
        emit(key, ids, desc.logicalName(), rows, !scope.isAll(), sink);
    }

    private void resolveDocumentGroup(GroupKey key, Set<UUID> ids, AccessSubject viewer,
                                      Map<MentionRef, ResolvedMention> sink) {
        DocumentDescriptor desc = document(key.name());
        if (desc == null) {
            putUnresolved(key, ids, sink);
            return;
        }
        ScopeClause scope = scopes.clause(desc.javaClass(), viewer, AccessMode.READ, desc.tableName());
        String sql = "SELECT _id, _number FROM " + desc.tableName()
                + " WHERE _id IN (<ids>) AND _deletion_mark = false" + scope.and();
        Map<UUID, Resolved> rows = jdbi.withHandle(h -> scope.bind(h.createQuery(sql))
                .bindList("ids", new ArrayList<>(ids))
                .reduceRows(new HashMap<UUID, Resolved>(), (map, rv) -> {
                    map.put(rv.getColumn("_id", UUID.class),
                            new Resolved(rv.getColumn("_number", String.class), null));
                    return map;
                }));
        emit(key, ids, desc.logicalName(), rows, !scope.isAll(), sink);
    }

    /**
     * Emit a readable group: each id gets the entity label plus its display/avatar when the record
     * exists. For a record-scoped viewer a record that didn't resolve (out of scope, or gone) is
     * not readable at all — the two cases are deliberately indistinguishable.
     */
    private void emit(GroupKey key, Set<UUID> ids, String entity, Map<UUID, Resolved> rows, boolean scoped,
                      Map<MentionRef, ResolvedMention> sink) {
        for (UUID id : ids) {
            Resolved hit = rows.get(id);
            if (hit == null && scoped) {
                sink.put(new MentionRef(key.kind(), key.name(), id),
                        new ResolvedMention(key.kind(), key.name(), id, null, null, null, false));
                continue;
            }
            String display = hit == null ? null : (hit.display() == null || hit.display().isBlank() ? null : hit.display());
            String avatar = hit == null ? null : hit.avatarUrl();
            sink.put(new MentionRef(key.kind(), key.name(), id),
                    new ResolvedMention(key.kind(), key.name(), id, display, avatar, entity, true));
        }
    }

    /** Readable entity, but its descriptor vanished — treat as readable-with-no-display (label fallback). */
    private void putUnresolved(GroupKey key, Set<UUID> ids, Map<MentionRef, ResolvedMention> sink) {
        for (UUID id : ids) {
            sink.put(new MentionRef(key.kind(), key.name(), id),
                    new ResolvedMention(key.kind(), key.name(), id, null, null, null, true));
        }
    }

    private record Resolved(String display, String avatarUrl) {
    }

    private CatalogDescriptor catalog(String name) {
        String normalized = normalize(name);
        return registry.allCatalogs().stream()
                .filter(d -> normalize(d.logicalName()).equals(normalized))
                .findFirst().orElse(null);
    }

    private DocumentDescriptor document(String name) {
        String normalized = normalize(name);
        return registry.allDocuments().stream()
                .filter(d -> normalize(d.logicalName()).equals(normalized))
                .findFirst().orElse(null);
    }

    /** Same case-/separator-insensitive normalization the query services use to resolve {name}. */
    private static String normalize(String name) {
        return name == null ? "" : name.replace(" ", "").replace("_", "").toLowerCase(Locale.ROOT);
    }
}
