package su.onno.ui.tags;

import org.jdbi.v3.core.Jdbi;
import java.util.*;

/** Shared tag definitions and individual record assignments, scoped to an entity type. */
public class TagService {
    private final Jdbi jdbi;
    public record Tag(UUID id, String name, String color) {}
    private final List<TagCatalog> catalogs;
    public TagService(Jdbi jdbi) { this(jdbi,List.of()); }
    public TagService(Jdbi jdbi,List<TagCatalog> catalogs) {
        this.catalogs=List.copyOf(catalogs);
        this.jdbi = jdbi;
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE IF NOT EXISTS onno_tags (id UUID PRIMARY KEY, scope VARCHAR(200) NOT NULL, name VARCHAR(500) NOT NULL, normalized VARCHAR(500) NOT NULL, color VARCHAR(7) NOT NULL, UNIQUE(scope,normalized))");
            h.execute("CREATE TABLE IF NOT EXISTS onno_tag_links (scope VARCHAR(200) NOT NULL, record_id UUID NOT NULL, tag_id UUID NOT NULL REFERENCES onno_tags(id), PRIMARY KEY(scope,record_id,tag_id))");
            h.execute("CREATE TABLE IF NOT EXISTS onno_tag_imports (scope VARCHAR(200) NOT NULL, record_id UUID NOT NULL, PRIMARY KEY(scope,record_id))");
        });
    }
    private Optional<TagCatalog> catalog(String scope) { return catalogs.stream().filter(c -> c.scope().equals(scope)).findFirst(); }
    private List<Tag> catalogLibrary(TagCatalog catalog) {
        // Preserve legacy IDs. Imports are idempotent and never overwrite catalog edits/deletions.
        var legacy=jdbi.withHandle(h -> h.createQuery("SELECT id,name,color FROM onno_tags WHERE scope=:s").bind("s",catalog.scope())
            .map((r,c)->new Tag(r.getObject(1,UUID.class),r.getString(2),r.getString(3))).list());
        legacy.forEach(tag -> catalog.importTag(tag.id(),tag.name(),tag.color()));
        return catalog.list();
    }
    public static String scope(String kind, String name) { return kind + ":" + name.replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT); }
    public List<Tag> library(String scope) {
        var bound=catalog(scope); if(bound.isPresent()) return catalogLibrary(bound.get());
        return jdbi.withHandle(h -> h.createQuery("SELECT id,name,color FROM onno_tags WHERE scope=:s ORDER BY normalized")
            .bind("s", scope).map((r,c) -> new Tag(r.getObject(1,UUID.class),r.getString(2),r.getString(3))).list());
    }
    public List<Tag> assigned(String scope, UUID record) {
        if(catalog(scope).isPresent()) {
            var ids=jdbi.withHandle(h -> h.createQuery("SELECT tag_id FROM onno_tag_links WHERE scope=:s AND record_id=:r").bind("s",scope).bind("r",record).mapTo(UUID.class).list());
            return library(scope).stream().filter(tag -> ids.contains(tag.id())).toList();
        }
        return jdbi.withHandle(h -> h.createQuery("SELECT t.id,t.name,t.color FROM onno_tags t JOIN onno_tag_links l ON t.id=l.tag_id WHERE l.scope=:s AND l.record_id=:r ORDER BY t.normalized")
            .bind("s",scope).bind("r",record).map((r,c) -> new Tag(r.getObject(1,UUID.class),r.getString(2),r.getString(3))).list());
    }
    public Tag create(String scope, String name, String color) {
        String label = name == null ? "" : name.strip();
        if (label.isBlank() || label.length()>500) throw new IllegalArgumentException("Tag name must contain 1–500 characters");
        String tint = color == null ? "#8b78ff" : color;
        if (!tint.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Choose a valid tag color");
        String normalized = label.toLowerCase(Locale.ROOT);
        Tag tag = new Tag(UUID.randomUUID(), label, tint);
        var bound=catalog(scope);
        if(bound.isPresent()) {
            var existing=library(scope).stream().filter(t -> t.name().equalsIgnoreCase(label)).findFirst();
            return existing.orElseGet(() -> bound.get().importTag(tag.id(),label,tint));
        }
        try {
            jdbi.useHandle(h -> h.createUpdate("INSERT INTO onno_tags(id,scope,name,normalized,color) VALUES (:id,:s,:n,:key,:c)")
                .bind("id",tag.id()).bind("s",scope).bind("n",label).bind("key",normalized).bind("c",tint).execute());
            return tag;
        } catch (org.jdbi.v3.core.statement.UnableToExecuteStatementException e) {
            return library(scope).stream().filter(t -> t.name().toLowerCase(Locale.ROOT).equals(normalized)).findFirst().orElseThrow(() -> e);
        }
    }
    public void assign(String scope, UUID record, UUID tag) {
        var definition=library(scope).stream().filter(t -> t.id().equals(tag)).findFirst().orElseThrow(() -> new IllegalArgumentException("Tag is not in this library"));
        // Keep a compatibility anchor for existing FK-backed assignments; catalog owns display/lifecycle.
        if(catalog(scope).isPresent()) {
            boolean anchored=jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM onno_tags WHERE id=:id").bind("id",tag).mapTo(Integer.class).one()>0);
            if(!anchored) try { jdbi.useHandle(h -> h.createUpdate("INSERT INTO onno_tags(id,scope,name,normalized,color) VALUES (:id,:s,:n,:key,:c)").bind("id",tag).bind("s",scope).bind("n",definition.name()).bind("key",tag.toString()).bind("c",definition.color()).execute()); }
            catch(org.jdbi.v3.core.statement.UnableToExecuteStatementException e) { if(!jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM onno_tags WHERE id=:id").bind("id",tag).mapTo(Integer.class).one()>0))throw e; }
        }
        try { jdbi.useHandle(h -> h.createUpdate("INSERT INTO onno_tag_links(scope,record_id,tag_id) VALUES (:s,:r,:t)").bind("s",scope).bind("r",record).bind("t",tag).execute()); }
        catch (org.jdbi.v3.core.statement.UnableToExecuteStatementException e) {
            if (assigned(scope,record).stream().noneMatch(t -> t.id().equals(tag))) throw e;
        }
    }
    public void remove(String scope, UUID record, UUID tag) {
        jdbi.useHandle(h -> h.createUpdate("DELETE FROM onno_tag_links WHERE scope=:s AND record_id=:r AND tag_id=:t").bind("s",scope).bind("r",record).bind("t",tag).execute());
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
