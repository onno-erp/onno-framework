package su.onno.ui.tags;

import org.jdbi.v3.core.Jdbi;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import su.onno.events.EntityChangePublisher;
import su.onno.events.EntityChangedEvent;
import su.onno.ui.JdbiCommitCallbacks;
import java.util.*;
import java.util.function.UnaryOperator;

/** Shared tag definitions and individual record assignments, scoped to an entity type. */
public class TagService {
    private final Jdbi jdbi;
    public record Tag(UUID id, String name, String color) {}
    private final List<TagCatalog> catalogs;
    private final EntityChangePublisher events;
    private final UnaryOperator<String> entityNameForScope;
    private final ThreadLocal<TagCatalog> creatingInCatalog = new ThreadLocal<>();

    public TagService(Jdbi jdbi) { this(jdbi,List.of()); }
    public TagService(Jdbi jdbi,List<TagCatalog> catalogs) {
        this(jdbi, catalogs, event -> {}, TagService::nameFromScope);
    }

    TagService(Jdbi jdbi, List<TagCatalog> catalogs, EntityChangePublisher events,
               UnaryOperator<String> entityNameForScope) {
        this.catalogs=List.copyOf(catalogs);
        this.jdbi = jdbi;
        this.events = events;
        this.entityNameForScope = entityNameForScope;
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE IF NOT EXISTS onno_tags (id UUID PRIMARY KEY, scope VARCHAR(200) NOT NULL, name VARCHAR(500) NOT NULL, normalized VARCHAR(500) NOT NULL, color VARCHAR(7) NOT NULL, UNIQUE(scope,normalized))");
            h.execute("CREATE TABLE IF NOT EXISTS onno_tag_links (scope VARCHAR(200) NOT NULL, record_id UUID NOT NULL, tag_id UUID NOT NULL REFERENCES onno_tags(id), PRIMARY KEY(scope,record_id,tag_id))");
            h.execute("CREATE TABLE IF NOT EXISTS onno_tag_imports (scope VARCHAR(200) NOT NULL, record_id UUID NOT NULL, PRIMARY KEY(scope,record_id))");
        });
    }
    /** The application catalog that owns this scope's definitions, if one is bound. */
    public Optional<TagCatalog> catalogFor(String scope) { return catalog(scope); }
    private Optional<TagCatalog> catalog(String scope) { return catalogs.stream().filter(c -> c.scope().equals(scope)).findFirst(); }
    private List<Tag> catalogLibrary(TagCatalog catalog) {
        // Preserve legacy IDs. Imports are idempotent and never overwrite catalog edits/deletions.
        String cursor = null;
        do {
            var page = su.onno.query.KeysetWindow.fetch(jdbi, "onno_tags", "*", "scope = :scope",
                    q -> q.bind("scope", catalog.scope()),
                    List.of(new su.onno.query.KeysetWindow.Order("id", false)), cursor, 500, "tag-import:" + catalog.scope());
            for (var row : page.rows()) catalog.importTag((UUID) row.get("id"), (String) row.get("name"), (String) row.get("color"));
            cursor = page.nextCursor();
        } while (cursor != null);
        return bounded(catalog.list());
    }

    private static <T> List<T> bounded(List<T> rows) {
        if (rows.size() > 500) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "Tag selector exceeds 500 entries; use the bound catalog's paged list");
        return rows;
    }

    public static String scope(String kind, String name) { return kind + ":" + name.replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT); }
    static String nameFromScope(String scope) { return scope.substring(scope.indexOf(':') + 1); }

    private void publishChange(String scope, UUID record) {
        events.publish(new EntityChangedEvent(EntityChangedEvent.UPDATED, "tag",
                entityNameForScope.apply(scope), record, null));
    }

    private record CatalogCreate(String name, UUID id) implements TransactionSynchronization {
        boolean matches(EntityChangedEvent event) {
            return id.equals(event.id()) && sameCatalog(name, event.entityName());
        }
    }

    static boolean sameCatalog(String left, String right) {
        return scope("catalogs", left).equals(scope("catalogs", right));
    }

    boolean ownsCatalogChange(EntityChangedEvent event) {
        TagCatalog creating = creatingInCatalog.get();
        if (creating != null && sameCatalog(creating.catalogName(), event.entityName())) {
            return true;
        }
        return TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.getSynchronizations().stream()
                        .anyMatch(s -> s instanceof CatalogCreate change && change.matches(event));
    }

    private Tag createInCatalog(TagCatalog catalog, String label, String color) {
        TagCatalog previous = creatingInCatalog.get();
        creatingInCatalog.set(catalog);
        try {
            Tag created = catalog.create(label, color);
            // A repository-backed catalog may also emit a catalog event when its transaction commits.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new CatalogCreate(catalog.catalogName(), created.id()));
            }
            return created;
        } finally {
            if (previous == null) {
                creatingInCatalog.remove();
            } else {
                creatingInCatalog.set(previous);
            }
        }
    }
    public List<Tag> library(String scope) {
        return catalog(scope).map(this::catalogLibrary).orElseGet(() -> bounded(jdbi.withHandle(h -> h.createQuery("SELECT id,name,color FROM onno_tags WHERE scope=:s ORDER BY normalized LIMIT 501")
            .bind("s", scope).map((r,c) -> new Tag(r.getObject(1,UUID.class),r.getString(2),r.getString(3))).list())));
    }
    public List<Tag> assigned(String scope, UUID record) {
        if(catalog(scope).isPresent()) {
            var ids=bounded(jdbi.withHandle(h -> h.createQuery("SELECT tag_id FROM onno_tag_links WHERE scope=:s AND record_id=:r LIMIT 501").bind("s",scope).bind("r",record).mapTo(UUID.class).list()));
            return library(scope).stream().filter(tag -> ids.contains(tag.id())).toList();
        }
        return bounded(jdbi.withHandle(h -> h.createQuery("SELECT t.id,t.name,t.color FROM onno_tags t JOIN onno_tag_links l ON t.id=l.tag_id WHERE l.scope=:s AND l.record_id=:r ORDER BY t.normalized LIMIT 501")
            .bind("s",scope).bind("r",record).map((r,c) -> new Tag(r.getObject(1,UUID.class),r.getString(2),r.getString(3))).list()));
    }
    public Tag create(String scope, String name, String color) {
        String label = name == null ? "" : name.strip();
        if (label.isBlank() || label.length()>500) throw new IllegalArgumentException("Tag name must contain 1–500 characters");
        String tint = color == null ? "#8b78ff" : color;
        if (!tint.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Choose a valid tag color");
        String normalized = label.toLowerCase(Locale.ROOT);
        Tag tag = new Tag(UUID.randomUUID(), label, tint);
        Runnable publish = () -> publishChange(scope, null);
        var bound=catalog(scope);
        if(bound.isPresent()) {
            var existing=library(scope).stream().filter(t -> t.name().equalsIgnoreCase(label)).findFirst();
            if (existing.isPresent()) {
                return existing.get();
            }
            Tag created = createInCatalog(bound.get(), label, tint);
            if (!jdbi.withHandle(h -> JdbiCommitCallbacks.defer(h, publish))) {
                publish.run();
            }
            return created;
        }
        boolean deferred;
        try {
            deferred = jdbi.withHandle(h -> {
                h.createUpdate("INSERT INTO onno_tags(id,scope,name,normalized,color) VALUES (:id,:s,:n,:key,:c)")
                        .bind("id",tag.id()).bind("s",scope).bind("n",label).bind("key",normalized).bind("c",tint).execute();
                return JdbiCommitCallbacks.defer(h, publish);
            });
        } catch (org.jdbi.v3.core.statement.UnableToExecuteStatementException e) {
            return library(scope).stream().filter(t -> t.name().toLowerCase(Locale.ROOT).equals(normalized)).findFirst().orElseThrow(() -> e);
        }
        if (!deferred) {
            publish.run();
        }
        return tag;
    }
    public void assign(String scope, UUID record, UUID tag) {
        var definition=library(scope).stream().filter(t -> t.id().equals(tag)).findFirst().orElseThrow(() -> new IllegalArgumentException("Tag is not in this library"));
        // Keep a compatibility anchor for existing FK-backed assignments; catalog owns display/lifecycle.
        if(catalog(scope).isPresent()) {
            boolean anchored=jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM onno_tags WHERE id=:id").bind("id",tag).mapTo(Integer.class).one()>0);
            if(!anchored) try { jdbi.useHandle(h -> h.createUpdate("INSERT INTO onno_tags(id,scope,name,normalized,color) VALUES (:id,:s,:n,:key,:c)").bind("id",tag).bind("s",scope).bind("n",definition.name()).bind("key",tag.toString()).bind("c",definition.color()).execute()); }
            catch(org.jdbi.v3.core.statement.UnableToExecuteStatementException e) { if(!jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM onno_tags WHERE id=:id").bind("id",tag).mapTo(Integer.class).one()>0))throw e; }
        }
        Runnable publish = () -> publishChange(scope, record);
        boolean deferred;
        try {
            deferred = jdbi.withHandle(h -> {
                h.createUpdate("INSERT INTO onno_tag_links(scope,record_id,tag_id) VALUES (:s,:r,:t)")
                        .bind("s",scope).bind("r",record).bind("t",tag).execute();
                return JdbiCommitCallbacks.defer(h, publish);
            });
        }
        catch (org.jdbi.v3.core.statement.UnableToExecuteStatementException e) {
            if (assigned(scope,record).stream().noneMatch(t -> t.id().equals(tag))) throw e;
            return;
        }
        if (!deferred) {
            publish.run();
        }
    }
    public void remove(String scope, UUID record, UUID tag) {
        Runnable publish = () -> publishChange(scope, record);
        boolean publishNow = jdbi.withHandle(h -> {
            int removed = h.createUpdate("DELETE FROM onno_tag_links WHERE scope=:s AND record_id=:r AND tag_id=:t")
                    .bind("s",scope).bind("r",record).bind("t",tag).execute();
            return removed > 0 && !JdbiCommitCallbacks.defer(h, publish);
        });
        if (publishNow) {
            publish.run();
        }
    }
    /** One-time compatibility import; later removals are never resurrected from legacy text. */
    public void importLegacy(String scope, UUID record, String text) {
        boolean imported = jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM onno_tag_imports WHERE scope=:s AND record_id=:r").bind("s",scope).bind("r",record).mapTo(Integer.class).one()>0);
        if (imported) return;
        for (String name : Objects.toString(text, "").split(",")) if (!name.isBlank()) assign(scope,record,create(scope,name.strip(),null).id());
        try { jdbi.useHandle(h -> h.createUpdate("INSERT INTO onno_tag_imports VALUES (:s,:r)").bind("s",scope).bind("r",record).execute()); }
        catch (org.jdbi.v3.core.statement.UnableToExecuteStatementException e) {
            boolean exists = jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM onno_tag_imports WHERE scope=:s AND record_id=:r").bind("s",scope).bind("r",record).mapTo(Integer.class).one()>0);
            if (!exists) throw e;
        }
    }
}
