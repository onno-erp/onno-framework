package com.onec.ui;

import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.MetadataRegistry;

import org.jdbi.v3.core.Jdbi;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-side queries for catalogs, shared by the REST API and the DivKit emitters
 * so the SQL and ref-resolution live in one place. Pure data access — access
 * control stays with the callers.
 */
public class CatalogQueryService {

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RefResolver refResolver;

    public CatalogQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.refResolver = new RefResolver(registry, jdbi);
    }

    public CatalogDescriptor require(String name) {
        String normalized = name.replace("_", "").toLowerCase();
        return registry.allCatalogs().stream()
                .filter(d -> d.logicalName().replace(" ", "").toLowerCase().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Catalog not found: " + name));
    }

    public List<Map<String, Object>> list(CatalogDescriptor desc) {
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _deletion_mark = false")
                        .mapToMap()
                        .list()
        );
        refResolver.resolveAttributes(rows, desc.attributes());
        return rows;
    }

    public long count(CatalogDescriptor desc) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE _deletion_mark = false")
                        .mapTo(Long.class)
                        .one());
    }

    public List<Map<String, Object>> list(CatalogDescriptor desc, int offset, int limit) {
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false ORDER BY _code LIMIT :limit OFFSET :offset")
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .mapToMap()
                        .list()
        );
        refResolver.resolveAttributes(rows, desc.attributes());
        return rows;
    }

    public List<Map<String, Object>> children(CatalogDescriptor desc, UUID parent) {
        if (!desc.hierarchical()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Catalog is not hierarchical: " + desc.logicalName());
        }
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            String sql = "SELECT * FROM " + desc.tableName() +
                    " WHERE _deletion_mark = false AND " +
                    (parent == null ? "_parent IS NULL" : "_parent = :parent") +
                    " ORDER BY _is_folder DESC, _description";
            var query = h.createQuery(sql);
            if (parent != null) query.bind("parent", parent);
            return query.mapToMap().list();
        });
        refResolver.resolveAttributes(rows, desc.attributes());
        return rows;
    }

    public List<Map<String, Object>> tree(CatalogDescriptor desc) {
        if (!desc.hierarchical()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Catalog is not hierarchical: " + desc.logicalName());
        }
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false ORDER BY _is_folder DESC, _description")
                        .mapToMap()
                        .list()
        );
        refResolver.resolveAttributes(rows, desc.attributes());
        return buildTree(rows, null);
    }

    public Map<String, Object> get(CatalogDescriptor desc, UUID id) {
        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _id = :id")
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
        );
        refResolver.resolveAttributes(List.of(row), desc.attributes());
        return row;
    }

    private List<Map<String, Object>> buildTree(List<Map<String, Object>> rows, UUID parent) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID rowParent = parseUuid(row.get("_parent"));
            if (!Objects.equals(rowParent, parent)) continue;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("children", buildTree(rows, parseUuid(row.get("_id"))));
            result.add(copy);
        }
        return result;
    }

    private static UUID parseUuid(Object value) {
        if (value == null || "".equals(value)) return null;
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
