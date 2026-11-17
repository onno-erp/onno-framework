package su.onno.service;

import org.jdbi.v3.core.Jdbi;
import org.springframework.transaction.support.TransactionTemplate;
import su.onno.access.AccessSubject;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Writes a record together with the join records that relate it to others, in one transaction.
 *
 * <p>A many-to-many relationship is modelled as its own join entity — a catalog (or document) with
 * a reference to each side, plus whatever the link carries (a role, an order, a confirmation flag).
 * The UI edits the join rows of one side inline, like a tabular section, and saves them with the
 * record: the request names each relation by its join entity and the {@code via} reference that
 * points back at the record, and carries the complete row set. This service then replaces the set:
 * rows with an {@code id} are updated, rows without one are created with {@code via} set to the
 * record, and live join rows of the record that the set no longer contains are delete-marked.
 *
 * <p>Every row goes through the generic command path — write access, record policies, lifecycle
 * hooks, rules, history — and the record and all its relations commit or roll back together. A row
 * id that does not belong to the record (its {@code via} names another record) is rejected, so a
 * request cannot re-parent or delete someone else's link.
 */
public class RelationWriteService {

    /** The request body member carrying the relations. */
    public static final String BODY_KEY = "@relations";

    /** One relation of a write: the join entity, its reference back to the record, the full row set. */
    public record RelationWrite(String entity, String via, List<Map<String, Object>> rows) {
        public RelationWrite {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(via, "via");
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final TransactionTemplate transactions;
    private final CatalogQueryService catalogs;
    private final CatalogCommandService catalogCommands;
    private final DocumentQueryService documents;
    private final DocumentCommandService documentCommands;
    private final RecordAccess recordAccess;

    public RelationWriteService(MetadataRegistry registry, Jdbi jdbi, TransactionTemplate transactions,
                                CatalogQueryService catalogs, CatalogCommandService catalogCommands,
                                DocumentQueryService documents, DocumentCommandService documentCommands,
                                RecordAccess recordAccess) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.transactions = transactions;
        this.catalogs = catalogs;
        this.catalogCommands = catalogCommands;
        this.documents = documents;
        this.documentCommands = documentCommands;
        this.recordAccess = recordAccess;
    }

    /**
     * Split the relations out of a write body: removes {@value #BODY_KEY} from {@code body} and
     * returns its parsed relations (empty when absent).
     */
    public static List<RelationWrite> extract(Map<String, Object> body) {
        Object raw = body == null ? null : body.remove(BODY_KEY);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw badRequest(BODY_KEY + " must be a list");
        List<RelationWrite> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw badRequest(BODY_KEY + " entries must be objects");
            Object entity = map.get("entity");
            Object via = map.get("via");
            Object rows = map.get("rows");
            if (!(entity instanceof String e) || e.isBlank()) throw badRequest("relation entity is required");
            if (!(via instanceof String v) || v.isBlank()) throw badRequest("relation via is required");
            if (rows != null && !(rows instanceof List<?>)) throw badRequest("relation rows must be a list");
            List<Map<String, Object>> parsed = new ArrayList<>();
            if (rows != null) {
                for (Object row : (List<?>) rows) {
                    if (!(row instanceof Map<?, ?> r)) throw badRequest("relation rows must be objects");
                    Map<String, Object> copy = new LinkedHashMap<>();
                    r.forEach((k, value) -> copy.put(String.valueOf(k), value));
                    parsed.add(copy);
                }
            }
            out.add(new RelationWrite(e, v, parsed));
        }
        return out;
    }

    /**
     * Run {@code write} (the record's own create or update, returning its stored representation)
     * and then replace each relation's rows for the written record, all in one transaction. With no
     * relations the write runs as it would on its own.
     *
     * @param owner     the written record's Java class (what each relation's {@code via} must reference)
     * @param write     the record write; its result must carry the record's {@code id}
     */
    public Map<String, Object> write(Class<?> owner, List<RelationWrite> relations, AccessSubject subject,
                                     java.util.function.Supplier<Map<String, Object>> write) {
        if (relations == null || relations.isEmpty()) return write.get();
        List<Resolved> resolved = relations.stream().map(r -> resolve(owner, r, subject)).toList();
        return transactions.execute(status -> {
            Map<String, Object> result = write.get();
            UUID id = parseUuid(result.get("id"));
            if (id == null) id = parseUuid(result.get("_id"));
            if (id == null) throw new IllegalStateException("The record write returned no id");
            for (Resolved relation : resolved) replace(relation, id, subject);
            return result;
        });
    }

    private void replace(Resolved relation, UUID ownerId, AccessSubject subject) {
        // The record's join rows the caller can see: exactly what their form listed. Rows a record
        // policy hides from them are neither offered nor treated as removed.
        ScopeClause scope = recordAccess.clause(subject, relation.type(), su.onno.access.AccessMode.READ, relation.table());
        Set<UUID> existing = new LinkedHashSet<>(jdbi.withHandle(h -> scope.bind(h.createQuery(
                        "SELECT _id FROM " + relation.table() + " WHERE " + relation.viaColumn()
                                + " = :owner AND _deletion_mark = false" + scope.and()))
                .bind("owner", ownerId)
                .mapTo(UUID.class)
                .list()));
        Set<UUID> kept = new LinkedHashSet<>();
        for (Map<String, Object> row : relation.write().rows()) {
            Map<String, Object> body = new LinkedHashMap<>(row);
            UUID rowId = parseUuid(body.remove("id"));
            body.remove("_id");
            body.put(relation.write().via(), ownerId.toString());
            if (rowId == null) {
                relation.create().apply(body);
            } else {
                if (!existing.contains(rowId)) {
                    throw new ServiceException(ServiceException.Status.BAD_REQUEST,
                            relation.logicalName() + " " + rowId + " does not belong to this record");
                }
                if (!kept.add(rowId)) {
                    throw badRequest(relation.logicalName() + " " + rowId + " is listed twice");
                }
                relation.update().update(rowId, body);
            }
        }
        for (UUID stale : existing) {
            if (!kept.contains(stale)) relation.delete().accept(stale);
        }
    }

    private Resolved resolve(Class<?> owner, RelationWrite write, AccessSubject subject) {
        CatalogDescriptor catalog = findCatalog(write.entity());
        if (catalog != null) {
            AttributeDescriptor via = requireVia(owner, catalog.logicalName(), catalog.attributes(), write.via());
            return new Resolved(write, catalog.javaClass(), catalog.logicalName(), catalog.tableName(), via.columnName(),
                    body -> catalogCommands.create(catalog, body, subject),
                    (id, body) -> catalogCommands.update(catalog, id, body, subject),
                    id -> catalogCommands.delete(catalog, id, subject));
        }
        DocumentDescriptor document = findDocument(write.entity());
        if (document != null) {
            AttributeDescriptor via = requireVia(owner, document.logicalName(), document.attributes(), write.via());
            return new Resolved(write, document.javaClass(), document.logicalName(), document.tableName(), via.columnName(),
                    body -> documentCommands.create(document, body, subject),
                    (id, body) -> documentCommands.update(document, id, body, subject),
                    id -> documentCommands.delete(document, id, subject));
        }
        throw new ServiceException(ServiceException.Status.NOT_FOUND,
                "Relation entity is not a catalog or document: " + write.entity());
    }

    private AttributeDescriptor requireVia(Class<?> owner, String joinName, List<AttributeDescriptor> attributes,
                                           String via) {
        AttributeDescriptor attr = attributes.stream()
                .filter(a -> a.fieldName().equals(via))
                .findFirst()
                .orElseThrow(() -> badRequest(joinName + " has no field " + via));
        String ownerName = logicalName(owner);
        if (!attr.isRef() || attr.isPolymorphicRef() || ownerName == null || !ownerName.equals(attr.refTarget())) {
            throw badRequest(joinName + "." + via + " is not a reference to " + (ownerName == null ? owner.getSimpleName() : ownerName));
        }
        return attr;
    }

    private String logicalName(Class<?> type) {
        CatalogDescriptor c = registry.getCatalogDescriptor(type);
        if (c != null) return c.logicalName();
        DocumentDescriptor d = registry.getDocumentDescriptor(type);
        return d == null ? null : d.logicalName();
    }

    private CatalogDescriptor findCatalog(String name) {
        try {
            return catalogs.require(name);
        } catch (ServiceException e) {
            return null;
        }
    }

    private DocumentDescriptor findDocument(String name) {
        try {
            return documents.require(name);
        } catch (ServiceException e) {
            return null;
        }
    }

    private static UUID parseUuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID u) return u;
        String s = value.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid id: " + s);
        }
    }

    private static ServiceException badRequest(String message) {
        return new ServiceException(ServiceException.Status.BAD_REQUEST, message);
    }

    @FunctionalInterface
    private interface Update {
        void update(UUID id, Map<String, Object> body);
    }

    private record Resolved(RelationWrite write, Class<?> type, String logicalName, String table, String viaColumn,
                            Function<Map<String, Object>, Map<String, Object>> create,
                            Update update,
                            java.util.function.Consumer<UUID> delete) {
    }
}
